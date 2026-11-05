// Included inside streamed::gpu_numeric_stage_hook_tests to reuse its private
// Fixture, not its CPU-mirror delegate. These tests must run ONE PER PROCESS:
// the native session registry deliberately cannot be reopened after retirement.
#[cfg(feature = "gpu-diffusion-research")]
mod actual_session_chain {
    use super::*;
    use crate::{gpu_session as session, gpu_session_contract::StageTicket};
    use serde_json::{json, Value};
    use std::sync::Mutex;

    const EPOCH: u64 = 7001;
    // Test-only policy reservation, NOT measured RSS or production admission.
    // The external test supervisor must separately enforce its actual RSS cap.
    const PREPAID: u64 = 2 * 1024 * 1024 * 1024;
    const FIXED: u64 = 256 * 1024 * 1024;

    struct Audit {
        generation: u64,
        rng_mode: crate::research_grain::RngMode,
        sequence: u64,
        records: Vec<Value>,
    }
    struct NativeDelegate {
        audit: Arc<Mutex<Audit>>,
        ticket: Option<GpuSessionTicket>,
        poll: Option<String>,
    }
    impl GpuDiffusionDelegate for NativeDelegate {
        fn available_disk_bytes(&mut self) -> Option<u64> { Some(u64::MAX) }
        fn begin(&mut self, wire: &str) -> Option<bool> {
            let v: Value = serde_json::from_str(wire).unwrap();
            let token = v["token"].as_u64().unwrap();
            let total = v["totalPixels"].as_u64().unwrap();
            let operation = v["operation"].as_str().unwrap_or("fullCameraDiffusion");
            if operation == "fullFilmGrain" {
                let manifest: Value = serde_json::from_slice(&fs::read(
                    Path::new(v["jobDir"].as_str().unwrap()).join("manifest.json")
                ).unwrap()).unwrap();
                let grain: crate::research_grain::GrainPlan = serde_json::from_value(manifest["grainConfig"].clone()).unwrap();
                assert_eq!(grain.rng_mode, self.audit.lock().unwrap().rng_mode);
                assert!(grain.active && grain.sublayers && grain.micro_active);
                assert!(grain.final_sigma > 0. && grain.micro_spread > 0.);
                assert!(grain.layers.iter().flatten().all(|layer| layer.cloud_sigma > 0.),
                    "fixture must execute original dye-cloud blur for every layer");
            }
            let ticket = {
                let mut audit = self.audit.lock().unwrap();
                audit.sequence += 1;
                StageTicket { epoch: EPOCH, generation: audit.generation, sequence: audit.sequence }
            };
            self.ticket = Some(GpuSessionTicket { epoch: ticket.epoch,
                generation: ticket.generation, sequence: ticket.sequence, token });
            // This test-only wrapper enters a thread-local software-adapter
            // scope HERE on the export worker, then invokes the same native
            // plan/prepayment/run_stage/queue-fence/seal path as JNI. It never
            // calls a host arithmetic mirror and never changes runtime policy.
            let result = crate::gpu_session_jni::run_stage_for_test(
                ticket, operation, Path::new(v["jobDir"].as_str().unwrap()),
                v["budgetBytes"].as_u64().unwrap(), token,
                v["manifestSha256"].as_str().unwrap(),
            ).unwrap_or_else(|code| panic!("actual Vulkan stage {operation}, frame {}, sequence {} failed: {code}", ticket.generation, ticket.sequence));
            eprintln!("ACTUAL_SOFTWARE_VULKAN_STAGE {result}");
            assert_eq!(result["stageSealed"], true, "{result}");
            assert_eq!(result["epoch"], ticket.epoch);
            assert_eq!(result["generation"], ticket.generation);
            assert_eq!(result["sequence"], ticket.sequence);
            assert_eq!(result["summary"]["gpuUsed"], true, "host substitute: {result}");
            assert!(result["summary"]["dispatches"].as_u64().is_some_and(|n| n > 0), "no device dispatch: {result}");
            let device=session::adapter_info_for_test(ticket.epoch).expect("actual retained device identity");
            assert_eq!(result["summary"]["vendor"].as_u64(),Some(device.vendor as u64),"stage vendor does not match actual retained device: {operation}");
            let adapter = device.name.to_ascii_lowercase();
            assert!(["swiftshader", "llvmpipe", "lavapipe", "software"].iter().any(|name| adapter.contains(name)),
                "this gate must use software Vulkan, got {adapter}; hardware qualification is separate");
            self.poll = Some(json!({"schemaVersion":2,"epoch":EPOCH,
                "frameGeneration":ticket.generation,"sequence":ticket.sequence,"token":token,
                "status":"success_sealed","completedPixels":total,"totalPixels":total,
                "code":"OK","summary":result["summary"]}).to_string());
            self.audit.lock().unwrap().records.push(result);
            Some(true)
        }
        fn poll(&mut self, token: u64) -> Option<String> {
            assert_eq!(self.ticket.unwrap().token, token);
            self.poll.clone()
        }
        fn cancel(&mut self, _: u64) { let _ = session::request_stop(EPOCH); }
        fn session_ticket(&self) -> Option<GpuSessionTicket> { self.ticket }
    }

    fn requests(f: &Fixture, route: ScienceRoute, base: u64, audit: &Arc<Mutex<Audit>>) -> GpuStageRequests {
        let request = |token| GpuDiffusionRequest { parent_handle: 1, token,
            stage_dir: Some(f.root.join(format!("gpu-stage-{token}"))),
            delegate: Some(Box::new(NativeDelegate { audit: audit.clone(), ticket: None, poll: None })) };
        GpuStageRequests { route,
            diffusion: Some(request(base)), exposure: Some(request(base+1)),
            optical: Some(request(base+2)), dir: Some(request(base+3)), scanner: Some(request(base+4)),
            science: ScienceKind::ALL.into_iter().enumerate()
                .map(|(i, k)| (k, request(base+10+i as u64))).collect() }
    }

    fn assert_complete_chain(m: &StreamedMetrics, records: &[Value]) {
        let expected = ["fullCameraDiffusion", "fullAutoExposure", "fullHighlightBoost",
            "fullFilmExposure", "fullFilmOptics", "fullExposureLogDir", "fullFilmGrain",
            "fullPrintExposure", "fullPrintDiffusion", "fullFinishTail"];
        let actual: Vec<_> = records.iter().map(|r| r["operation"].as_str().unwrap()).collect();
        assert_eq!(actual.len(), expected.len(), "missing or duplicate enabled operations: {actual:?}");
        for operation in expected { assert_eq!(actual.iter().filter(|&&x| x == operation).count(), 1, "{operation}: {actual:?}"); }
        for (stage, backend, attempted, reason) in [
            ("camera diffusion",m.gpu_diffusion.backend,m.gpu_diffusion.attempted,m.gpu_diffusion.fallback_reason),
            ("exposure",m.gpu_exposure.backend,m.gpu_exposure.attempted,m.gpu_exposure.fallback_reason),
            ("optics",m.gpu_optical.backend,m.gpu_optical.attempted,m.gpu_optical.fallback_reason),
            ("log/DIR",m.gpu_log_dir.backend,m.gpu_log_dir.attempted,m.gpu_log_dir.fallback_reason),
        ] { assert!(attempted && backend.starts_with("gpu_"), "{stage} fell back: {backend}, {reason}"); }
        for k in ScienceKind::ALL {
            let metric = &m.gpu_science[&k];
            assert_eq!(metric.backend, k.backend(), "{k:?} fell back: {}", metric.fallback_reason);
            if matches!(k, ScienceKind::ExposureLog | ScienceKind::ScanTail) {
                assert_eq!(metric.fallback_reason, "included_in_resident");
            } else { assert!(metric.attempted, "{k:?} not executed"); }
        }
        assert_eq!(m.gpu_scanner.fallback_reason,"included_in_resident");
        assert_eq!(m.gpu_dir.fallback_reason,"included_in_resident");
    }

    fn whole_image_errors(cpu: &[u8], gpu: &[u8], pixels: usize) -> Value {
        assert_eq!(cpu.len(), pixels * 3 * 4, "full RGB f32 reference");
        assert_eq!(gpu.len(), cpu.len());
        let mut errors = Vec::with_capacity(pixels * 3);
        for (a,b) in cpu.chunks_exact(4).zip(gpu.chunks_exact(4)) {
            let a = f32::from_le_bytes(a.try_into().unwrap()) as f64;
            let b = f32::from_le_bytes(b.try_into().unwrap()) as f64;
            assert!(a.is_finite() && b.is_finite(), "non-finite full image output");
            errors.push((a-b).abs());
        }
        errors.sort_by(f64::total_cmp);
        let percentile = |p: f64| errors[((errors.len()-1) as f64 * p).ceil() as usize];
        json!({"samples":errors.len(),"maxAbs":errors.last().unwrap(),
            "rms":(errors.iter().map(|v|v*v).sum::<f64>() / errors.len() as f64).sqrt(),
            "p95":percentile(0.95),"p99":percentile(0.99)})
    }

    fn run_chain_sized(route: ScienceRoute, width: u32, height: u32, frames: u64) {
        assert!(frames >= 2 && frames <= 4);
        // Pending grain admission must FAIL explicitly, never skip the grain or
        // silently pass by measuring a CPU fallback. Do not change this flag here.
        assert!(crate::research_grain::guarded_graph::SOURCE_ADMITTED,
            "full-chain gate blocked: guarded grain SOURCE_ADMITTED is false");
        let f = Fixture::sized(width,height,42,"kodak_portra_400","kodak_portra_endura",true);
        let original = fs::read(&f.input).unwrap();
        let (stop,_) = flags();
        let mut params = f.params.clone();
        params.camera.auto_exposure = true;
        params.camera.diffusion_filter.active = true;
        params.camera.diffusion_filter.filter_family = "black_pro_mist".into();
        params.film_render.halation.active = true;
        params.film_render.halation.boost_ev = 0.1;
        params.film_render.dir_couplers.active = true;
        params.film_render.dir_couplers.diffusion_size_um = 5.;
        params.film_render.dir_couplers.diffusion_tail_um = 2.;
        params.film_render.grain.active = true;
        params.film_render.grain.amount = 1.;
        params.film_render.grain.sublayers_active = true;
        // Explicitly cross activation thresholds: these are not disabled to
        // make the device comparison pass. Keep the 10um physical pitch fixed.
        params.film_render.grain.blur_dye_clouds_um = 20.;
        params.film_render.grain.micro_structure = [5.,600.];
        params.film_render.grain.blur = 0.65;
        params.enlarger.diffusion_filter.active = true;
        params.enlarger.diffusion_filter.filter_family = "glimmerglass".into();
        params.print_render.glare.active = true;
        params.print_render.glare.percent = 0.3;
        params.print_render.glare.blur = 0.6;
        params.scanner.lens_blur = 0.4;
        params.scanner.unsharp_mask = [0.7,0.3];
        session::activate(EPOCH, PREPAID, FIXED, 256).unwrap();
        let audit = Arc::new(Mutex::new(Audit { generation: 0, rng_mode: if route == ScienceRoute::Preview { crate::research_grain::RngMode::SequentialMt19937 } else { crate::research_grain::RngMode::CoordinateSplitMix64 }, sequence: 0, records: Vec::new() }));
        let out = std::env::var_os("FILM_GPU_QUALITY_RESULTS").map(PathBuf::from)
            .unwrap_or_else(||std::env::temp_dir().join(format!("actual-vulkan-chain-{}",std::process::id())));
        fs::create_dir_all(&out).unwrap();
        let out = out.join(format!("{route:?}-{width}x{height}-{}", std::process::id()));
        fs::create_dir(&out).expect("whole-chain evidence directory must be new");
        let mut reports = Vec::new();
        let mut cold_gpu = Vec::new();
        let mut cold_cpu = Vec::new();
        let mut cold_pipelines = 0;
        let mut previous_hits = (0,0);
        for generation in 1..=frames {
            if generation == 3 {
                // Isolate LUT changes: other effects cannot mask a stale table.
                params.enlarger.y_filter_shift = 15.;
                params.enlarger.m_filter_shift = -10.;
                params.print_render.density_curve_gamma = 1.3;
            }
            if generation == 4 {
                params.camera.exposure_compensation_ev = 0.35;
                params.film_render.grain.seed = 103;
                params.film_render.grain.amount = 1.2;
                params.print_render.glare.percent = 0.45;
                params.scanner.unsharp_mask = [0.9,0.4];
            }
            let cpu_name = format!("cpu-{generation}");
            let gpu_name = format!("gpu-{generation}");
            let cpu_metrics = f.run_requests(&cpu_name,params.clone(),None,
                GpuStageRequests { route,..GpuStageRequests::default() },stop.clone()).unwrap();
            session::begin_frame(EPOCH,generation).unwrap();
            { let mut a = audit.lock().unwrap(); a.generation = generation; a.records.clear(); }
            let metrics = f.run_requests(&gpu_name,params.clone(),None,
                requests(&f,route,1000*generation,&audit),stop.clone()).unwrap();
            let stats = session::finish_frame(EPOCH,generation).unwrap();
            let records = audit.lock().unwrap().records.clone();
            eprintln!("FULL_CHAIN_ROUTE_METRICS camera={}/{} exposure={}/{} optical={}/{} log_dir={}/{}", metrics.gpu_diffusion.backend, metrics.gpu_diffusion.fallback_reason, metrics.gpu_exposure.backend, metrics.gpu_exposure.fallback_reason, metrics.gpu_optical.backend, metrics.gpu_optical.fallback_reason, metrics.gpu_log_dir.backend, metrics.gpu_log_dir.fallback_reason);
            assert_complete_chain(&metrics,&records);
            assert_eq!(metrics.grain_mode,cpu_metrics.grain_mode);
            let cpu = fs::read(f.root.join(&cpu_name)).unwrap();
            let gpu = fs::read(f.root.join(&gpu_name)).unwrap();
            let errors = whole_image_errors(&cpu,&gpu,(f.width*f.height) as usize);
            let report = json!({"testOnly":true,"actualVulkanExecuted":true,
                "deviceClass":"software Vulkan; not an Adreno performance or device qualification",
                "productionMemoryAdmissionProven":false,"testPrepaidBytes":PREPAID,
                "route":format!("{route:?}"),"generation":generation,"grainMode":metrics.grain_mode,
                "width":f.width,"height":f.height,"parameters":params,"errors":errors,
                "session":stats,"stages":records});
            eprintln!("ACTUAL_SOFTWARE_VULKAN_FULL_CHAIN {report}");
            fs::write(out.join(format!("{route:?}-{generation}-cpu.rgb.f32le")),&cpu).unwrap();
            fs::write(out.join(format!("{route:?}-{generation}-gpu.rgb.f32le")),&gpu).unwrap();
            reports.push(report);
            fs::write(out.join(format!("{route:?}-full-chain.json")),serde_json::to_vec_pretty(&reports).unwrap()).unwrap();
            // Fixed whole-image quality gate, not selected samples or thresholds
            // fitted to this run. Same envelope as the existing full-chain oracle.
            assert!(errors["maxAbs"].as_f64().unwrap() <= 0.01 && errors["rms"].as_f64().unwrap() <= 0.002,
                "full image quality gate: {errors}; artifacts: {}",out.display());
            if generation == 1 { cold_gpu = gpu.clone(); cold_cpu = cpu.clone(); cold_pipelines = stats.pipeline_slots; }
            else {
                assert_eq!(stats.pipeline_slots,cold_pipelines,"unchanged graph must reuse pipelines");
                assert!(stats.pipeline_hits > previous_hits.0 && stats.buffer_hits > previous_hits.1,"warm session did not reuse pipelines and buffers");
                if generation == 2 { assert_eq!(cpu,cold_cpu); assert_eq!(gpu,cold_gpu,"identical warm frame changed"); }
                else {
                    assert_ne!(cpu,cold_cpu,"changed parameters failed to alter reference");
                    assert_ne!(gpu,cold_gpu,"stale GPU buffers or LUTs");
                    if generation == 3 {
                        let delta=whole_image_errors(&cold_cpu,&cpu,(f.width*f.height) as usize)["rms"].as_f64().unwrap();
                        assert!(delta>0.,"LUT-only fixture has no sensitivity");
                        assert!(errors["rms"].as_f64().unwrap()<delta*0.25,"candidate follows stale LUT more closely than changed CPU reference");
                    }
                }
            }
            previous_hits = (stats.pipeline_hits,stats.buffer_hits);
            assert_eq!(fs::read(&f.input).unwrap(),original,"renderer mutated input");
        }
        session::request_stop(EPOCH).unwrap();
    }

    #[test]
    #[ignore = "actual 384x512 software Vulkan whole preview; isolated process and external resource supervisor required"]
    fn actual_vulkan_preview_mt19937_384x512_full_chain_cold_warm() {
        // Same original 10um pitch, all effects, exact MT sequence and complete
        // output comparison. This is not Android memory admission or phone speed.
        run_chain_sized(ScienceRoute::Preview, 384, 512, 2);
    }

    #[test]
    fn full_chain_packet_directories_match_production_token_contract() {
        let f=Fixture::sized(5,3,42,"kodak_portra_400","kodak_portra_endura",true);
        let audit=Arc::new(Mutex::new(Audit { generation:0, rng_mode:crate::research_grain::RngMode::CoordinateSplitMix64, sequence:0, records:Vec::new() }));
        let r=requests(&f,ScienceRoute::Export,1000,&audit);
        let mut all=vec![r.diffusion.unwrap(),r.exposure.unwrap(),r.optical.unwrap(),r.dir.unwrap(),r.scanner.unwrap()];
        all.extend(r.science.into_values());
        for request in all {
            let path=request.stage_dir.unwrap();
            assert_eq!(path.file_name().unwrap().to_str().unwrap(),format!("gpu-stage-{}",request.token));
            assert_eq!(path.parent().unwrap(),f.root);
            assert!(!path.exists());
        }
    }

    #[test]
    #[ignore = "actual software Vulkan full chain; requires grain admission, external RSS supervisor, isolated process"]
    fn actual_vulkan_export_coordinate_full_chain_cold_warm_changed() { run_chain_sized(ScienceRoute::Export, 5, 3, 4); }

    #[test]
    #[ignore = "actual software Vulkan full chain; requires grain admission, external RSS supervisor, isolated process"]
    fn actual_vulkan_preview_mt19937_full_chain_cold_warm_changed() { run_chain_sized(ScienceRoute::Preview, 5, 3, 4); }
}
