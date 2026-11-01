// Stochastic grain generation.
// Poisson-binomial particle model with dye cloud blur and lognormal micro-structure.

use spektrafilm_gpu::ComputeBackend;
use spektrafilm_math::gaussian;
use spektrafilm_math::image::ImageBuf;
use spektrafilm_math::precision::{Scalar, ZERO, from_f64};
use rayon::prelude::*;
use std::time::Instant;

/// SplitMix64 stream keyed by absolute output coordinates, channel/layer seed.
/// Only the optional tiled-export mode uses this; reference MT19937 is retained.
struct CoordinateRng(u64);
impl CoordinateRng {
    fn new(seed:u64,x:u32,y:u32)->Self {
        let mut r=Self(seed ^ (x as u64).wrapping_mul(0x9e3779b97f4a7c15)
            ^ (y as u64).wrapping_mul(0xd1b54a32d192ed03));
        use rand::RngCore; let mixed=r.next_u64(); Self(mixed)
    }
}
impl rand::RngCore for CoordinateRng {
    fn next_u64(&mut self)->u64 {
        self.0=self.0.wrapping_add(0x9e3779b97f4a7c15);
        let mut z=self.0;
        z=(z^(z>>30)).wrapping_mul(0xbf58476d1ce4e5b9);
        z=(z^(z>>27)).wrapping_mul(0x94d049bb133111eb);
        z^(z>>31)
    }
    fn next_u32(&mut self)->u32 { (self.next_u64()>>32) as u32 }
    fn fill_bytes(&mut self,dst:&mut [u8]) {for chunk in dst.chunks_mut(8) {let bytes=self.next_u64().to_le_bytes();chunk.copy_from_slice(&bytes[..chunk.len()]);}}
}

/// Three sensitometric sublayers, ported from the pinned Python reference.
/// Each channel/layer is processed separately; no HxWx9 intermediate is kept.
#[allow(clippy::too_many_arguments)]
pub fn apply_grain_layers(
    density: &ImageBuf, pixel_size_um: f32, curves: &[[f64; 3]],
    layers: &[Vec<Vec<f64>>], positive: bool, area: f64,
    channel_scale: [f64;3], layer_scale: [f64;3], density_min: [f64;3],
    uniformity: [f64;3], blur: f32, dye_cloud_blur: f32,
    micro_structure: [f32;2], monochrome: bool, seed_offset: u64, origin: Option<[u32;2]>,
) -> ImageBuf {
    assert_eq!(layers.len(), curves.len());
    let n = density.pixel_count();
    let pixel_area = (pixel_size_um as f64).powi(2);
    let mut max_layers = [[0.0f64;3];3];
    for row in layers { for sl in 0..3 { for ch in 0..3 {
        max_layers[sl][ch] = max_layers[sl][ch].max(row[sl][ch]);
    }}}
    let channels: Vec<Vec<Scalar>> = (0..3usize).into_par_iter().map(|ch| {
        let sign = if positive {-1.0} else {1.0};
        let axis: Vec<f64> = curves.iter().map(|row| sign*row[ch]).collect();
        let total: f64 = (0..3).map(|sl|max_layers[sl][ch]).sum();
        let mut channel = vec![ZERO;n];
        for sl in 0..3 {
            let fraction = max_layers[sl][ch]/total;
            let dmin = fraction*density_min[ch];
            let dmax = max_layers[sl][ch]+dmin;
            let particles = pixel_area*fraction/(area*channel_scale[ch]*layer_scale[sl]);
            let values: Vec<Scalar> = density.pixels().map(|px| {
                let q = sign*(px[ch] as f64);
                let last = axis.len()-1;
                let value = if q <= axis[0] { layers[0][sl][ch] }
                    else if q >= axis[last] { layers[last][sl][ch] }
                    else {
                        let hi = axis.partition_point(|&v| v <= q).min(last);
                        let lo = hi-1;
                        let dx = axis[hi]-axis[lo];
                        let t = if dx != 0.0 {(q-axis[lo])/dx} else {0.0};
                        layers[lo][sl][ch] + t*(layers[hi][sl][ch]-layers[lo][sl][ch])
                    };
                from_f64(value+dmin)
            }).collect();
            let ch_seed = if monochrome {0} else {ch as u64};
            let grain = layer_particle_model(&values,density.width,density.height,dmax,particles,
                uniformity[ch],seed_offset.wrapping_add(ch_seed+sl as u64*10),dye_cloud_blur,origin);
            for (v,g) in channel.iter_mut().zip(grain.iter()) {*v += *g;}
        }
        channel
    }).collect();
    let mut out=ImageBuf::new(density.width,density.height);
    for (ch,channel) in channels.iter().enumerate() { out.write_channel(ch,channel); }
    // Reference microstructure is only active above sigma .05. This seeded
    // Box-Muller lognormal is statistically equivalent, not NumPy-bit-identical.
    let sigma = micro_structure[1] as f64*0.001/pixel_size_um as f64;
    if sigma > 0.05 {
        let sigma_log=(1.0+sigma*sigma).ln().sqrt();
        let mu=-0.5*sigma_log*sigma_log;
        let mut rng=rand_mt::Mt::new(seed_offset.wrapping_add(0x51f15e) as u32);
        let mut clump=ImageBuf::new(density.width,density.height);
        for (i,x) in clump.data.iter_mut().enumerate() {
            let mut coordinate=origin.map(|[ox,oy]| CoordinateRng::new(seed_offset.wrapping_add(0x51f15e+(i%3) as u64),ox+(i/3%density.width as usize) as u32,oy+(i/3/density.width as usize) as u32));
            let u=if let Some(r)=coordinate.as_mut() { spektrafilm_math::numpy_rng::rk_double(r) } else {spektrafilm_math::numpy_rng::rk_double(&mut rng)};
            let u=u.max(f64::MIN_POSITIVE);
            let v=if let Some(r)=coordinate.as_mut() { spektrafilm_math::numpy_rng::rk_double(r) } else {spektrafilm_math::numpy_rng::rk_double(&mut rng)};
            let normal=(-2.0*u.ln()).sqrt()*(std::f64::consts::TAU*v).cos();
            *x=from_f64((mu+sigma_log*normal).exp());
        }
        let spread=micro_structure[0]/pixel_size_um;
        if spread>0.4 {clump=gaussian::gaussian_blur(&clump,spread);}
        for (x,c) in out.data.iter_mut().zip(clump.data.iter()) {*x *= *c;}
    }
    for px in out.data.chunks_exact_mut(3) {for ch in 0..3 {px[ch]-=from_f64(density_min[ch]);}}
    if blur>0.0 {out=gaussian::gaussian_blur(&out,blur);}
    out
}

fn stage_timings_enabled() -> bool {
    std::env::var_os("SPEKTRAFILM_STAGE_TIMINGS").is_some()
}

fn print_stage_timing(enabled: bool, stage: &str, start: Instant) {
    if enabled {
        eprintln!("stage {stage}: {} ms", start.elapsed().as_millis());
    }
}

/// Apply the Poisson-binomial grain particle model to a single-channel density image.
///
/// Port of Python `layer_particle_model`. Density values flow through in `Scalar`
/// (full f64 precision in `precision-f64` mode); RNG sampling uses f64 directly.
pub fn layer_particle_model(
    density: &[Scalar],
    width: u32,
    height: u32,
    density_max: f64,
    n_particles_per_pixel: f64,
    grain_uniformity: f64,
    seed: u64,
    blur_particle: f32,
    origin: Option<[u32;2]>,
) -> Vec<Scalar> {
    // Inputs are f64 to match Python's `layer_particle_model` —
    // density_max/uniformity/agx-area come from JSON profiles at full
    // f64 precision in Python. Passing them as f32 in Rust truncated
    // ~7-decimal noise into `od_particle` and `saturation` which then
    // shifted every Poisson lambda by ~5e-8, producing a different
    // RNG stream and thus visibly different grain patterns.
    let dmax = density_max;
    let od_particle = dmax / n_particles_per_pixel;
    let gu = grain_uniformity;
    let npp = n_particles_per_pixel;

    // Python-bit-exact RNG: numpy's MT19937 + RandomState distribution
    // algorithms (`rk_poisson` / `rk_binomial`). Numpy's seed-to-stream
    // takes a `u32`, so truncate the 64-bit seed — for the CPU export
    // path we never need more than 24 distinct streams (3 channels × N
    // sub-layers). Iteration is single-threaded so the RNG state advances
    // in row-major order exactly like `numpy.random.RandomState(seed)
    // .poisson(lambda_array)` does internally.
    //
    // CRITICAL — match Python's call order: it does
    //   seeds = np.random.poisson(lam_array)   # whole array, then
    //   grain = np.random.binomial(seeds, p)   # whole array.
    // So the RNG must draw ALL Poisson samples first, THEN ALL Binomial
    // samples. Interleaving Poisson/Binomial per pixel (what we used to
    // do) gives a different RNG state at each draw and produces a
    // completely different grain pattern.
    let n = density.len();
    let mut rng = rand_mt::Mt::new(seed as u32);
    let cap=if origin.is_none(){n}else{0};
    let mut p_arr = Vec::with_capacity(cap);
    let mut sat_arr = Vec::with_capacity(cap);
    if origin.is_none() { for &d in density.iter() {
        let p = ((d as f64) / dmax).clamp(1e-6, 1.0 - 1e-6);
        let saturation = 1.0 - p * gu * (1.0 - 1e-6);
        p_arr.push(p);
        sat_arr.push(saturation);
    }}
    let mut grain = vec![ZERO; n];
    if let Some([ox, oy]) = origin {
        // Random access by absolute output coordinate: identical across tiles.
        for (i, slot) in grain.iter_mut().enumerate() {
            let x=ox.wrapping_add((i % width as usize) as u32);
            let y=oy.wrapping_add((i / width as usize) as u32);
            let mut local=CoordinateRng::new(seed,x,y);
            let p=((density[i] as f64)/dmax).clamp(1e-6,1.0-1e-6);
            let saturation=1.0-p*gu*(1.0-1e-6);
            let particles=spektrafilm_math::numpy_rng::rk_poisson(&mut local,npp/saturation);
            let developed=spektrafilm_math::numpy_rng::rk_binomial(&mut local,particles,p);
            *slot=from_f64(developed as f64*od_particle*saturation);
        }
    } else {
        let mut seeds = Vec::with_capacity(n);
        for sat in &sat_arr {
            seeds.push(spektrafilm_math::numpy_rng::rk_poisson(&mut rng, npp / sat));
        }
        for (i, slot) in grain.iter_mut().enumerate() {
            let developed = spektrafilm_math::numpy_rng::rk_binomial(&mut rng, seeds[i], p_arr[i]);
            *slot = from_f64((developed as f64) * od_particle * sat_arr[i]);
        }
    }

    if blur_particle > 0.4 {
        // Match Python: `sigma = blur_particle * np.sqrt(od_particle)`
        // — keep the multiplication in f64 and narrow at the end.
        let sigma = (blur_particle as f64 * od_particle.sqrt()) as f32;
        if sigma > 0.1 {
            grain = gaussian::gaussian_blur_channel(&grain,width,height,sigma);
        }
    }

    grain
}

/// Apply grain to a CMY density image.
///
/// Port of Python `apply_grain_to_density`.
#[allow(clippy::too_many_arguments)]
pub fn apply_grain_to_density(
    density_cmy: &ImageBuf,
    pixel_size_um: f32,
    agx_particle_area_um2: f64,
    agx_particle_scale: [f64; 3],
    density_min: [f64; 3],
    density_max_curves: [f64; 3],
    grain_uniformity: [f64; 3],
    grain_blur: f32,
    n_sub_layers: u32,
    monochrome: bool,
    seed_offset: u64,
    origin: Option<[u32;2]>,
    backend: &dyn ComputeBackend,
) -> ImageBuf {
    let stage_timings = stage_timings_enabled();
    let w = density_cmy.width;
    let h = density_cmy.height;
    let pixel_area = (pixel_size_um as f64) * (pixel_size_um as f64);
    let density_max: [f64; 3] = [
        density_max_curves[0] + density_min[0],
        density_max_curves[1] + density_min[1],
        density_max_curves[2] + density_min[2],
    ];

    let channels: Vec<(usize, Vec<Scalar>)> = (0..3)
        .into_par_iter()
        .map(|ch| {
            let t_ch = Instant::now();
            let particle_area = agx_particle_area_um2 * agx_particle_scale[ch];
            let mut n_particles = pixel_area / particle_area;
            if n_sub_layers > 1 {
                n_particles /= n_sub_layers as f64;
            }

            // Add density_min to input (kept in Scalar precision)
            let dmin_s = from_f64(density_min[ch]);
            let t = Instant::now();
            let density_ch: Vec<Scalar> =
                density_cmy.pixels().map(|px| px[ch] + dmin_s).collect();
            print_stage_timing(stage_timings, "grain.extract_channel", t);

            let mut grain_sum = vec![ZERO; density_ch.len()];

            for sl in 0..n_sub_layers {
                let t = Instant::now();
                // Monochrome (B&W): one shared noise field — every lane draws
                // from channel 0's RNG stream (upstream n_channels==1 has a
                // single emulsion, seed ch=0).
                let seed_ch = if monochrome { 0 } else { ch as u64 };
                let seed = seed_offset.wrapping_add(seed_ch + (sl as u64) * 10);
                let g = layer_particle_model(
                    &density_ch,
                    w,
                    h,
                    density_max[ch],
                    n_particles,
                    grain_uniformity[ch],
                    seed,
                    0.0,
                    origin,
                );
                print_stage_timing(stage_timings, "grain.layer_particle_model", t);
                for (s, &v) in grain_sum.iter_mut().zip(g.iter()) {
                    *s += v;
                }
            }

            // Average sub-layers
            let scale = from_f64(1.0 / n_sub_layers as f64);
            for v in &mut grain_sum {
                *v = *v * scale - dmin_s;
            }

            print_stage_timing(stage_timings, "grain.channel_total", t_ch);
            (ch, grain_sum)
        })
        .collect();

    let mut out = ImageBuf::new(w, h);
    for (ch, grain_sum) in channels {
        out.write_channel(ch, &grain_sum);
    }

    // Final blur — typically a few px sigma at 1–6 MP, big enough that the
    // GPU separable kernel wins.
    if grain_blur > 0.4 {
        let t = Instant::now();
        out = backend.gaussian_blur(&out, grain_blur);
        print_stage_timing(stage_timings, "grain.final_blur", t);
    }

    out
}

/// One absolute-coordinate microstructure sample, identical to the origin-aware
/// branch of apply_grain_layers. Callers compute mu/sigma_log once per field.
pub fn microstructure_coordinate_value(seed:u64,x:u32,y:u32,channel:usize,sigma_log:f64,mu:f64)->f64{
    let mut rng=CoordinateRng::new(seed.wrapping_add(0x51f15e+channel as u64),x,y);
    let u=spektrafilm_math::numpy_rng::rk_double(&mut rng).max(f64::MIN_POSITIVE);
    let v=spektrafilm_math::numpy_rng::rk_double(&mut rng);
    let normal=(-2.*u.ln()).sqrt()*(std::f64::consts::TAU*v).cos();
    (mu+sigma_log*normal).exp()
}

/// Export-only coordinate sampling. The caller MUST supply its bounded private
/// pool; this never changes shared preview/ROI sampling or the legacy RNG path.
/// Spatial dye-cloud/final blur is performed after this joined sampling call by
/// the streamed pipeline over the full image, never independently per band.
#[allow(clippy::too_many_arguments)]
pub fn layer_particle_model_export(
    pool: &rayon::ThreadPool, density: &[Scalar], width: u32,
    density_max: f64, n_particles_per_pixel: f64, grain_uniformity: f64,
    seed: u64, origin: [u32; 2],
) -> Vec<Scalar> {
    let dmax = density_max;
    let od_particle = dmax / n_particles_per_pixel;
    let gu = grain_uniformity;
    let npp = n_particles_per_pixel;
    let [ox, oy] = origin;
    let mut grain = vec![ZERO; density.len()];
    pool.install(|| grain.par_iter_mut().enumerate().for_each(|(i, slot)| {
        let x = ox.wrapping_add((i % width as usize) as u32);
        let y = oy.wrapping_add((i / width as usize) as u32);
        let mut local = CoordinateRng::new(seed, x, y);
        let p = ((density[i] as f64) / dmax).clamp(1e-6, 1.0 - 1e-6);
        let saturation = 1.0 - p * gu * (1.0 - 1e-6);
        let particles = spektrafilm_math::numpy_rng::rk_poisson(&mut local, npp / saturation);
        let developed = spektrafilm_math::numpy_rng::rk_binomial(&mut local, particles, p);
        *slot = from_f64(developed as f64 * od_particle * saturation);
    }));
    grain
}

#[cfg(test)]
mod export_sampling_tests {
    use super::*;
    #[test]
    fn export_pool_preserves_coordinate_samples_and_legacy_is_untouched() {
        for workers in [2, 4] {
            let pool = rayon::ThreadPoolBuilder::new().num_threads(workers).build().unwrap();
            for (w,h) in [(1,1),(17,13),(4096,8),(40000,1)] {
                let d:Vec<_>=(0..w*h).map(|i|from_f64(0.01+2.38*((i as usize*7919%65521)as f64/65521.))).collect();
                for seed in [0,42,u64::MAX] {for origin in [[0,0],[31,209],[u32::MAX-10,u32::MAX-10]] {
                    let reference=layer_particle_model(&d,w,h,2.4,12.3,0.97,seed,0.,Some(origin));
                    let actual=layer_particle_model_export(&pool,&d,w,2.4,12.3,0.97,seed,origin);
                    assert!(reference.iter().zip(actual).all(|(a,b)|(*a as f64).to_bits()==(b as f64).to_bits()));
                }}
            }
        }
        let d=vec![from_f64(0.4);17*13];
        let a=layer_particle_model(&d,17,13,2.4,12.3,0.97,42,0.65,None);
        let b=layer_particle_model(&d,17,13,2.4,12.3,0.97,42,0.65,None);
        assert_eq!(a,b);
    }
}
