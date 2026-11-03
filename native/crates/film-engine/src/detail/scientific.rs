//! Full working-grid scientific detail cache. Crops never re-run stochastic stages.
use super::*;
use std::io::{Seek, SeekFrom};

pub fn requested(p: &MobileParams) -> bool {
    p.gpu_science_experimental
        || p.gpu_diffusion_experimental
        || p.gpu_exposure_experimental
        || p.gpu_optical_experimental
        || p.gpu_dir_experimental
        || p.gpu_scanner_experimental
}
fn processing() -> DetailError {
    DetailError::Processing("Scientific detail processing failed".into())
}
pub fn plan(c: &DetailConfig) -> Result<ResourcePlan, DetailError> {
    c.params.validate().map_err(|_| processing())?;
    if [&c.source_revision, &c.mapping_revision, &c.data_revision]
        .iter()
        .any(|v| v.is_empty() || v.len() > 1024)
    {
        return Err(DetailError::InvalidGeometry(
            "Source, mapping and data revisions are required".into(),
        ));
    }
    if c.width == 0 || c.height == 0 || c.width > 1536 || c.height > 1536 {
        return Err(DetailError::InvalidGeometry(
            "Detail grid exceeds limit".into(),
        ));
    }
    let p = crate::mobile::plan_export(c.width as i32, c.height as i32, &c.params)
        .map_err(|_| processing())?;
    // One bounded row plus the native scientific working set and retained RGB spool.
    let peak = usize::try_from(p.estimated_native_working_bytes)
        .map_err(|_| processing())?
        .checked_add(2 * 1024 * 1024)
        .and_then(|v| {
            v.checked_add(if c.region_output {
                c.width as usize * c.height as usize * 28 + 128 * 1024
            } else {
                258 * 258 * 28
            })
        })
        .ok_or_else(processing)?;
    if peak > c.memory_budget_bytes {
        return Err(DetailError::MemoryPlanExceeded {
            required: peak,
            budget: c.memory_budget_bytes,
        });
    }
    if p.estimated_total_local_bytes > c.disk_budget_bytes {
        return Err(DetailError::StorageBudgetExceeded {
            required: p.estimated_total_local_bytes,
            budget: c.disk_budget_bytes,
        });
    }
    Ok(ResourcePlan {
        region_output: c.region_output,
        peak_reserved_bytes: peak,
        disk_reserved_bytes: p.estimated_total_local_bytes,
        stage_cache_bytes: 0,
        spectrum_cache_bytes: 0,
        max_dimension: 1536,
    })
}
pub struct ScientificContext {
    identity: DetailIdentity,
    plan: ResourcePlan,
    backend: DetailBackendInfo,
    metered: Option<f32>,
    metrics: Option<crate::streamed::StreamedMetrics>,
    output: File,
}
impl ScientificContext {
    pub fn build(
        c: DetailConfig,
        data: &Path,
        source: &Path,
        root: &Path,
        parent: i64,
        delegates: crate::streamed::GpuStageDelegates,
        cancel: &CancellationToken,
    ) -> Result<Arc<Self>, DetailError> {
        let plan = plan(&c)?;
        cancel.check()?;
        let cache = root
            .parent()
            .and_then(|p| p.parent())
            .and_then(|p| p.parent())
            .ok_or_else(processing)?;
        // The session owns this exclusive directory until Java confirms helper drain.
        let work = root.join("scientific-grid");
        fs::create_dir(&work)?;
        let output = work.join("pixels.f32le");
        let scratch = work.join("scratch");
        let film = load_profile_by_name(data, &c.params.film_stock).map_err(|_| processing())?;
        let mut runtime = c.params.runtime_for_film(&film);
        runtime.io.output_cctf_encoding = false;
        let stages = crate::mobile::gpu_stage_requests(cache, parent, &c.params, delegates);
        let _interactive = crate::export_execution::interactive_work();
        let metrics = crate::streamed::render_scientific_detail_file(
            data,
            &c.params.film_stock,
            &c.params.paper_stock,
            runtime,
            source,
            &output,
            &scratch,
            c.width,
            c.height,
            c.params.memory_budget_mi_b,
            c.params.brightness_ev,
            stages,
            || cancel.cancelled(),
        )
        .map_err(|e| {
            if e.code == "CANCELLED" {
                DetailError::Cancelled
            } else {
                processing()
            }
        })?;
        cancel.check()?;
        let mut options = OpenOptions::new();
        options.read(true);
        #[cfg(any(target_os = "android", target_os = "linux"))]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.custom_flags(libc::O_NOFOLLOW);
        }
        let file = options.open(&output)?;
        let meta = file.metadata()?;
        if !meta.is_file() || meta.len() != u64::from(c.width) * u64::from(c.height) * 12 {
            return Err(processing());
        }
        let actual = crate::jni_bridge::scientific_backend(&metrics);
        let backend = DetailBackendInfo {
            actual_backend: actual,
            requested_gpu: requested(&c.params),
            cpu_fallback: actual != "gpu_f32",
        };
        let identity = DetailIdentity {
            source_revision: c.source_revision,
            mapping_revision: c.mapping_revision,
            data_revision: c.data_revision,
            parameters: serde_json::to_string(&c.params).map_err(|_| processing())?,
            width: c.width,
            height: c.height,
            algorithm: "owned-detail-v3-guarded64-resident-fullgrid-gutter1",
        };
        Ok(Arc::new(Self {
            identity,
            plan,
            backend,
            metered: metrics.metered_ev,
            metrics: Some(metrics),
            output: file,
        }))
    }
    pub fn identity(&self) -> &DetailIdentity {
        &self.identity
    }
    pub fn resource_plan(&self) -> &ResourcePlan {
        &self.plan
    }
    pub fn backend_info(&self) -> DetailBackendInfo {
        self.backend
    }
    pub fn metered_ev(&self) -> Option<f32> {
        self.metered
    }
    pub fn metrics(&self) -> Option<&crate::streamed::StreamedMetrics> {
        self.metrics.as_ref()
    }
    pub fn render_region_into(
        &self,
        core: Rect,
        out: &mut [f32],
        cancel: &CancellationToken,
    ) -> Result<(), DetailError> {
        let (w, h) = (self.identity.width as usize, self.identity.height as usize);
        if !core.valid(w, h) {
            return Err(DetailError::InvalidGeometry(
                "Invalid detail rectangle".into(),
            ));
        }
        let sample = core.expand(1, w, h);
        if out.len() != sample.w * sample.h * 3 {
            return Err(DetailError::InvalidGeometry(
                "Invalid detail output length".into(),
            ));
        }
        let mut file = self.output.try_clone()?;
        let mut row = vec![0u8; sample.w * 12];
        let mut staged=vec![0f32;out.len()];
        for y in 0..sample.h {
            cancel.check()?;
            file.seek(SeekFrom::Start(((sample.y + y) * w + sample.x) as u64 * 12))?;
            file.read_exact(&mut row)?;
            for (i, b) in row.chunks_exact(4).enumerate() {
                let v = f32::from_le_bytes(b.try_into().unwrap());
                if !v.is_finite() {
                    return Err(processing());
                }
                staged[y * sample.w * 3 + i] = v;
            }
        }
        cancel.check()?;
        out.copy_from_slice(&staged);
        Ok(())
    }
    pub fn render_cell(
        &self,
        x: usize,
        y: usize,
        cancel: &CancellationToken,
    ) -> Result<OwnedDetailTile, DetailError> {
        let (w, h) = (self.identity.width as usize, self.identity.height as usize);
        let (x, y) = (
            x.checked_mul(256).ok_or_else(processing)?,
            y.checked_mul(256).ok_or_else(processing)?,
        );
        if x >= w || y >= h {
            return Err(DetailError::InvalidGeometry("Invalid detail cell".into()));
        }
        let core = Rect {
            x,
            y,
            w: 256.min(w - x),
            h: 256.min(h - y),
        };
        let sample = core.expand(1, w, h);
        let mut rgb = vec![0.; sample.w * sample.h * 3];
        self.render_region_into(core, &mut rgb, cancel)?;
        Ok(OwnedDetailTile {
            identity: self.identity.clone(),
            backend: self.backend,
            core_rect: core,
            sample_rect: sample,
            rgb,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture(w: u32, h: u32) -> (ScientificContext, PathBuf, Vec<f32>) {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let p = std::env::temp_dir().join(format!(
            "science-detail-cache-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let values: Vec<f32> = (0..w as usize * h as usize * 3)
            .map(|i| (i as f32 - 30.) / 100.)
            .collect();
        let mut f = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&p)
            .unwrap();
        for v in &values {
            f.write_all(&v.to_le_bytes()).unwrap();
        }
        let c = ScientificContext {
            identity: DetailIdentity {
                source_revision: "s".into(),
                mapping_revision: "m".into(),
                data_revision: "d".into(),
                parameters: "{}".into(),
                width: w,
                height: h,
                algorithm: "owned-detail-v3-guarded64-resident-fullgrid-gutter1",
            },
            plan: ResourcePlan {
                region_output: true,
                peak_reserved_bytes: 0,
                disk_reserved_bytes: 0,
                stage_cache_bytes: 0,
                spectrum_cache_bytes: 0,
                max_dimension: 1536,
            },
            backend: DetailBackendInfo {
                actual_backend: "mixed_gpu_f32_cpu_f64",
                requested_gpu: true,
                cpu_fallback: true,
            },
            metered: None,
            metrics: None,
            output: f,
        };
        (c, p, values)
    }
    #[test]
    fn edge_tiles_and_regions_read_same_absolute_fullgrid() {
        let (c, p, values) = fixture(259, 263);
        let cancel = CancellationToken::new();
        let t = c.render_cell(1, 1, &cancel).unwrap();
        assert_eq!(
            t.sample_rect,
            Rect {
                x: 255,
                y: 255,
                w: 4,
                h: 8
            }
        );
        for y in 0..t.sample_rect.h {
            for x in 0..t.sample_rect.w {
                for ch in 0..3 {
                    assert_eq!(
                        t.rgb[(y * t.sample_rect.w + x) * 3 + ch].to_bits(),
                        values[((255 + y) * 259 + 255 + x) * 3 + ch].to_bits()
                    );
                }
            }
        }
        let mut out = vec![0.; t.rgb.len()];
        c.render_region_into(t.core_rect, &mut out, &cancel)
            .unwrap();
        assert_eq!(out, t.rgb);
        assert_eq!(
            t.identity.algorithm,
            "owned-detail-v3-guarded64-resident-fullgrid-gutter1"
        );
        drop(c);
        fs::remove_file(p).unwrap();
    }
    #[test]
    fn cancelled_or_bad_geometry_does_not_publish() {
        let (c, p, _) = fixture(4, 3);
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(matches!(
            c.render_cell(0, 0, &cancel),
            Err(DetailError::Cancelled)
        ));
        assert!(c.render_cell(1, 0, &CancellationToken::new()).is_err());
        assert!(c
            .render_region_into(
                Rect {
                    x: 0,
                    y: 0,
                    w: 4,
                    h: 3
                },
                &mut [0.; 3],
                &CancellationToken::new()
            )
            .is_err());
        drop(c);
        fs::remove_file(p).unwrap();
    }
    #[test]fn cancelled_after_first_row_keeps_caller_buffer_unchanged(){let(c,p,_)=fixture(4,3);let cancel=CancellationToken::new();cancel.cancel_after_checks(2);let mut output=vec![123.25;36];assert!(matches!(c.render_region_into(Rect{x:0,y:0,w:4,h:3},&mut output,&cancel),Err(DetailError::Cancelled)));assert!(output.iter().all(|x|*x==123.25));drop(c);fs::remove_file(p).unwrap();}

}
