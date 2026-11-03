//! Research only; output labels distinguish CPU emulation from Vulkan execution.
use film_engine::{
    disk_diffusion,
    research_diffusion::{Plan, compare, emulate_f32},
};
use spektrafilm_model::{diffusion::DiffusionFilter, diffusion::ExactDiffusionKernel};
use std::{fs, io::Write, time::Instant};
fn config(family: &'static str) -> DiffusionFilter<'static> {
    DiffusionFilter {
        family,
        strength: 0.62,
        spatial_scale: 1.,
        halo_warmth: 0.,
        core_intensity: 1.,
        core_size: 1.,
        halo_intensity: 1.,
        halo_size: 1.,
        bloom_intensity: 1.,
        bloom_size: 1.,
    }
}
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let gpu = std::env::args().any(|v| v == "--gpu");
    #[cfg(not(feature = "gpu-diffusion-research"))]
    if gpu {
        return Err(
            "--gpu requires gpu-diffusion-research; CPU emulator is not GPU execution".into(),
        );
    }
    let root =
        std::env::temp_dir().join(format!("filmroom-gpu-fft-research-{}", std::process::id()));
    fs::create_dir(&root)?;
    struct Cleanup(std::path::PathBuf);
    impl Drop for Cleanup {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    let _cleanup = Cleanup(root.clone());
    let mut results = Vec::new();
    for family in ["black_pro_mist", "cinebloom"] {
        for (w, h) in [(96usize, 64usize), (257, 193), (1, 65)] {
            for channel in 0..3 {
                for fixture in ["hdr_edges", "flat", "impulse"] {
                    let begin = Instant::now();
                    let kernel = ExactDiffusionKernel::new(&config(family), 10., w, h)
                        .ok_or("unknown kernel family")?;
                    let kernel_ms = begin.elapsed().as_secs_f64() * 1000.;
                    let plan_start = Instant::now();
                    let max_side = if w == 257 && fixture == "hdr_edges" {
                        256
                    } else {
                        1024
                    };
                    let plan = Plan::new(w, h, &kernel, channel, 64 * 1024 * 1024, max_side)?;
                    let plan_ms = plan_start.elapsed().as_secs_f64() * 1000.0;
                    let input: Vec<f64> = (0..w * h)
                        .map(|i| match fixture {
                            "flat" => 2.5,
                            "impulse" => {
                                if i == (h / 2) * w + w / 2 {
                                    8.0
                                } else {
                                    0.0
                                }
                            }
                            _ => {
                                let x = i % w;
                                let y = i / w;
                                let base = ((x * 19 + y * 31) % 997) as f64 / 200.;
                                if x == 0 || y == 0 {
                                    base + 16.0
                                } else {
                                    base - 0.1
                                }
                            }
                        })
                        .collect();
                    let id = results.len();
                    let src = root.join(format!("in-{id}"));
                    let dst = root.join(format!("out-{id}"));
                    let mut f = fs::File::create(&src)?;
                    for v in &input {
                        f.write_all(&v.to_le_bytes())?;
                    }
                    drop(f);
                    let start = Instant::now();
                    let reference_metrics = disk_diffusion::apply_plane(
                        &src,
                        &dst,
                        &root.join(format!("work-{id}")),
                        w as u32,
                        h as u32,
                        &kernel,
                        channel,
                        || false,
                        |_, _| {},
                    )?;
                    let reference_ms = start.elapsed().as_secs_f64() * 1000.;
                    let reference: Vec<f64> = fs::read(&dst)?
                        .chunks_exact(8)
                        .map(|b| f64::from_le_bytes(b.try_into().unwrap()))
                        .collect();
                    let start = Instant::now();
                    let candidate = emulate_f32(&plan, &input)?;
                    let emulation_ms = start.elapsed().as_secs_f64() * 1000.;
                    let error = compare(&reference, &candidate)?;
                    let mut row = serde_json::json!({"family":family,"fixture":fixture,"width":w,"height":h,"channel":channel,"pixelPitchUm":10.0,"seed":"deterministic fixture, no RNG substitution","kernelRadius":kernel.radius,"referenceFftSide":reference_metrics.fft_side,"candidateFftSide":plan.side,"tiles":plan.tiles(),"plannedDeviceBytes":plan.device_bytes,"kernelBuildMs":kernel_ms,"candidatePlanPreparationMs":plan_ms,"cpuStageIncludingKernelMs":kernel_ms+reference_ms,"hostF32StageIncludingPreparationMs":kernel_ms+plan_ms+emulation_ms,"cpuDiskStageMs":reference_ms,"hostF32EmulationMs":emulation_ms,"hostF32Error":error,"gpuExecuted":false,"endToEndAppMeasured":false,"timingWarning":"CPU disk and host f32 RAM routes differ; this is NOT GPU performance or phone speedup"});
                    #[cfg(feature = "gpu-diffusion-research")]
                    if gpu {
                        let start = Instant::now();
                        match film_engine::research_diffusion::gpu::Runner::new(&plan, true) {
                            Ok(runner) => {
                                let init_ms = start.elapsed().as_secs_f64() * 1000.;
                                let (v, m) = runner.run(&plan, &input, &mut || false)?;
                                row["gpuExecuted"] = m.hardware_gpu.into();
                                row["vulkanExecuted"] = true.into();
                                row["gpuInitializationMs"] = init_ms.into();
                                row["gpuStageIncludingPreparationMs"] =
                                    (kernel_ms + plan_ms + init_ms + m.stage_total_ms).into();
                                row["gpuErrorVsCpu"] =
                                    serde_json::to_value(compare(&reference, &v)?)?;
                                row["gpuErrorVsEmulation"] =
                                    serde_json::to_value(compare(&candidate, &v)?)?;
                                row["gpuMetrics"] = serde_json::to_value(m)?;
                            }
                            Err(e) => row["gpuUnavailableReason"] = e.into(),
                        }
                    }
                    println!("{}", serde_json::to_string(&row)?);
                    results.push(row);
                }
            }
        }
    }
    eprintln!(
        "{}",
        serde_json::to_string(
            &serde_json::json!({"cases":results.len(),"scope":"stage only; no export end-to-end or Adreno performance claim"})
        )?
    );
    Ok(())
}
