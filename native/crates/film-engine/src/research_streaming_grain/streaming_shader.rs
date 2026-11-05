//! Integrate under independent research_streaming_grain after coordinated review.
//! It builds NEW pipeline strings, never edits resident or MT source files.
use crate::research_grain::{GrainPlan, guarded_graph};
use sha2::{Digest, Sha256};
type R<T> = Result<T, String>;
const ERROR: &str = "GPU_GRAIN_STREAM_SHADER_VERSION";
const ORIGINAL: &str = "fn gg_rng(index:u32)->SoftRng{let old=grain_rng(p.u[2].xy,p.u[7].x+index%p.u[0].x,p.u[7].y+index/p.u[0].x);return SoftRng(old.state,0u,0u,0u,p.u[2].w);}";
const ADAPTER: &str = include_str!("streaming_rng.wgsl");

pub fn shaders_for_plan(plan: &GrainPlan) -> R<Vec<(Vec<u32>, String)>> {
    if plan.rng_mode != guarded_graph::RngMode::CoordinateSplitMix64 {
        return Err("INVALID_GRAIN_RNG_MODE".into());
    }
    // Pin source graph identity independently of a future upstream hash update.
    if format!(
        "{:x}",
        Sha256::digest(guarded_graph::GRAPH_SHADER.as_bytes())
    ) != "2729a8ad8b16459527d939f19fe8c30c73e92e7b098edf2cfb829dcc09a780b6"
    {
        return Err(ERROR.into());
    }
    let mut families = guarded_graph::opcode_families::shaders_for_plan(plan)?;
    for (_, shader) in &mut families {
        let matches = shader.matches("fn gg_rng(").count();
        if matches == 0 {
            continue;
        }
        if matches != 1
            || shader.matches(ORIGINAL).count() != 1
            || shader.contains("gg_rng_resident_reference")
        {
            return Err(ERROR.into());
        }
        // Keep the complete original body byte-identical, apart from its name.
        let retained = ORIGINAL.replacen("fn gg_rng(", "fn gg_rng_resident_reference(", 1);
        *shader = shader.replacen(ORIGINAL, &(retained + "\n" + ADAPTER), 1);
    }
    Ok(families)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    pub(crate) fn plan() -> GrainPlan {
        GrainPlan {
            width: 6000,
            height: 6000,
            active: true,
            sublayers: false,
            positive: false,
            monochrome: false,
            seed: 42,
            origin: [u32::MAX - 3, 209],
            rng_mode: guarded_graph::RngMode::CoordinateSplitMix64,
            layers: std::array::from_fn(|ch| {
                vec![crate::research_grain::Layer {
                    seed: 42 + ch as u64,
                    density_min: 0.03,
                    density_max: 2.43,
                    particles: 2300.,
                    uniformity: 0.97,
                    axis: vec![],
                    values: vec![],
                    cloud_sigma: 0.,
                }]
            }),
            amount: 0.6,
            density_min: vec![0.03; 3],
            final_sigma: 0.65,
            micro_active: false,
            micro_spread: 0.,
            micro_sigma_log: 0.,
            micro_mu: 0.,
        }
    }
    #[test]
    fn only_rng_addressing_changes_and_every_generated_family_validates() {
        let mut p = plan();
        for micro in [false, true] {
            p.micro_active = micro;
            let old = guarded_graph::opcode_families::shaders_for_plan(&p).unwrap();
            let new = shaders_for_plan(&p).unwrap();
            assert_eq!(old.len(), new.len());
            let retained = ORIGINAL.replacen("fn gg_rng(", "fn gg_rng_resident_reference(", 1)
                + "\n"
                + ADAPTER;
            let mut replaced = 0;
            for ((old_ops, old_source), (ops, source)) in old.iter().zip(&new) {
                assert_eq!(old_ops, ops);
                if old_source.contains(ORIGINAL) {
                    assert_eq!(*old_source, source.replace(&retained, ORIGINAL));
                    replaced += 1;
                } else {
                    assert_eq!(old_source, source);
                }
                let module = naga::front::wgsl::parse_str(source)
                    .unwrap_or_else(|e| panic!("{}", e.emit_to_string(source)));
                naga::valid::Validator::new(
                    naga::valid::ValidationFlags::all(),
                    naga::valid::Capabilities::empty(),
                )
                .validate(&module)
                .unwrap();
            }
            assert!(replaced > 0);
        }
    }
    #[test]
    fn streaming_semantics_retain_original_checks_and_reject_mt() {
        let p = plan();
        assert!(p.validate().is_err(), "resident >128MiB remains rejected");
        let stream =
            crate::research_streaming_grain::StreamGrainPlan::from_plan(p.clone()).unwrap();
        assert_eq!(stream.parameters().width, 6000);
        for bad in 0..4 {
            let mut p = p.clone();
            match bad {
                0 => p.layers[0][0].uniformity = 1.1,
                1 => p.width = u32::MAX,
                2 => p.rng_mode = guarded_graph::RngMode::SequentialMt19937,
                _ => p.layers[0][0].particles = guarded_graph::MAX_RATE + 1.,
            }
            assert!(crate::research_streaming_grain::StreamGrainPlan::from_plan(p).is_err());
        }
    }
}
