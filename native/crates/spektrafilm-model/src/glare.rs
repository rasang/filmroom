// Viewing glare simulation.
// Adds a small fraction of randomized blurred illuminant to simulate surface reflections.

use rand::SeedableRng;
use rand::rngs::StdRng;
use rand_distr::{Distribution, LogNormal};
use spektrafilm_math::gaussian;
use spektrafilm_math::image::ImageBuf;
use spektrafilm_math::precision::{Scalar, from_f32, from_f64};

/// Generate the per-pixel glare_amount field (lognormal sampled, then blurred, then /100).
///
/// Port of Python `compute_random_glare_amount` (model/glare.py:19). The lognormal
/// parameters are inverted from linear-space mean and std:
///   σ = sqrt( ln(1 + (s²/m²)) )
///   μ = ln(m) - σ²/2
///
/// Spatial blur uses our 2D gaussian filter; the result is divided by 100 (CC-style fraction).
pub fn compute_random_glare_amount(
    width: u32,
    height: u32,
    percent: f32,
    roughness: f32,
    blur: f32,
    seed: u64,
) -> Vec<Scalar> {
    let n_pixels = (width as usize) * (height as usize);
    if percent <= 0.0 {
        return vec![Scalar::default(); n_pixels];
    }
    let m = percent as f64;
    let s = (roughness * percent) as f64;
    let sigma2 = (1.0 + (s * s) / (m * m)).ln();
    let sigma = sigma2.sqrt();
    let mu = m.ln() - sigma2 / 2.0;
    let dist = LogNormal::new(mu, sigma).unwrap_or_else(|_| LogNormal::new(0.0, 1.0).unwrap());
    let mut rng = StdRng::seed_from_u64(seed);

    let mut glare: Vec<Scalar> = (0..n_pixels)
        .map(|_| from_f64(dist.sample(&mut rng)))
        .collect();

    if blur > 0.0 {
        // 2D gaussian filter via our 3-channel image kernel; broadcast scalar field to RGB.
        let mut img = ImageBuf::from_data(
            width,
            height,
            glare.iter().flat_map(|&v| [v, v, v]).collect(),
        );
        img = gaussian::gaussian_blur(&img, blur);
        glare = img.extract_channel(0);
    }

    // Divide by 100 (CC-style fraction).
    let inv100 = from_f64(1.0 / 100.0);
    for v in &mut glare {
        *v *= inv100;
    }
    glare
}

/// Add glare in any 3-channel image space — XYZ or RGB.
///
/// `space_per_pixel_offset` is the per-color constant offset vector. For XYZ-space glare
/// this is `illuminant_xyz`. For RGB-space glare it's `M * illuminant_xyz` where M is the
/// CAT+matrix that would convert XYZ → output RGB. By linearity:
///   `M * (xyz + g * illu) = M*xyz + g * (M*illu)`
/// so applying glare in either space gives the same result.
///
/// `glare_amount` must be `width*height` Scalar values from `compute_random_glare_amount`.
pub fn add_glare_with_amount(
    image: &mut ImageBuf,
    glare_amount: &[Scalar],
    space_offset: [Scalar; 3],
) {
    debug_assert_eq!(glare_amount.len(), image.pixel_count());
    for (i, px) in image.pixels_mut().enumerate() {
        let g = glare_amount[i];
        px[0] += g * space_offset[0];
        px[1] += g * space_offset[1];
        px[2] += g * space_offset[2];
    }
}

/// Add viewing glare to an XYZ image.
///
/// Port of Python `add_glare`. Generates a random glare pattern
/// using lognormal distribution, blurs it, and adds it as
/// a fraction of the illuminant.
pub fn add_glare(
    xyz: &ImageBuf,
    illuminant_xyz: [f32; 3],
    percent: f32,
    roughness: f32,
    blur: f32,
) -> ImageBuf {
    if percent <= 0.0 {
        return xyz.clone();
    }

    let n_pixels = xyz.pixel_count();
    let amount = percent;
    let sigma = roughness * amount;

    // Generate random glare amount per pixel
    let mu = (amount as f64).ln() - 0.5 * (sigma as f64 / amount as f64).powi(2).ln_1p();
    let s = ((sigma as f64 / amount as f64).powi(2).ln_1p()).sqrt();
    let dist = LogNormal::new(mu, s).unwrap_or_else(|_| LogNormal::new(0.0, 1.0).unwrap());
    let mut rng = StdRng::seed_from_u64(42);

    let mut glare_map: Vec<Scalar> = (0..n_pixels)
        .map(|_| from_f64(dist.sample(&mut rng)))
        .collect();

    // Blur the glare map
    if blur > 0.0 {
        let mut glare_img = ImageBuf::from_data(
            xyz.width,
            xyz.height,
            glare_map.iter().flat_map(|&v| [v, v, v]).collect(),
        );
        glare_img = gaussian::gaussian_blur(&glare_img, blur);
        glare_map = glare_img.extract_channel(0);
    }

    // Apply: xyz += glare_amount * illuminant_xyz / 100
    let illum = [
        from_f32(illuminant_xyz[0]),
        from_f32(illuminant_xyz[1]),
        from_f32(illuminant_xyz[2]),
    ];
    let inv100 = from_f64(1.0 / 100.0);
    let mut result = xyz.clone();
    for (i, px) in result.pixels_mut().enumerate() {
        let g = glare_map[i] * inv100;
        px[0] += g * illum[0];
        px[1] += g * illum[1];
        px[2] += g * illum[2];
    }

    result
}

/// Sequential samples matching the whole-frame glare generator exactly. Keeping
/// one sampler across row chunks preserves StdRng order without an H×W array.
pub struct GlareSamples { distribution: Option<LogNormal<f64>>, rng: StdRng }
impl GlareSamples {
    pub fn new(percent:f32,roughness:f32,seed:u64)->Result<Self,&'static str>{
        if !percent.is_finite() || percent<0. || !roughness.is_finite() || roughness<0. {return Err("Invalid glare parameters");}
        let distribution=if percent==0. {None}else {
            let m=percent as f64;let s=(roughness*percent)as f64;
            let sigma2=(1.+s*s/(m*m)).ln();let mu=m.ln()-sigma2/2.;
            Some(LogNormal::new(mu,sigma2.sqrt()).map_err(|_|"Invalid glare distribution")?)
        };
        Ok(Self{distribution,rng:StdRng::seed_from_u64(seed)})
    }
    pub fn sample(&mut self)->f64{self.distribution.as_ref().map_or(0.,|d|d.sample(&mut self.rng))}
}

/// Bounded read-only oracle for validating an isolated GPU implementation of the
/// existing StdRng stream. Not used by production image generation.
#[doc(hidden)]
pub fn research_glare_rng_words(seed:u64,count:usize)->Result<Vec<u64>,&'static str> {
    use rand::RngCore;
    if count>2_000_000{return Err("Glare oracle sample limit");}
    let mut rng=StdRng::seed_from_u64(seed);
    Ok((0..count).map(|_|rng.next_u64()).collect())
}
/// Original StandardNormal outputs and cumulative u64 consumption, for complete
/// sequence comparisons (including rejected draws and tail branches).
#[doc(hidden)]
pub fn research_glare_normal_sequence(seed:u64,count:usize)->Result<Vec<(f64,u64)>,&'static str> {
    if count>2_000_000{return Err("Glare oracle sample limit");}
    Ok(research_glare_normal_stream(seed).take(count).collect())
}
/// Streaming version of the same read-only oracle. Callers bound iteration;
/// no image-sized allocation is required for a complete maximum-frame census.
#[doc(hidden)]
pub fn research_glare_normal_stream(seed:u64)->impl Iterator<Item=(f64,u64)> {
    use rand::RngCore;
    use rand_distr::StandardNormal;
    struct Counted { rng:StdRng, halves:u64 }
    impl RngCore for Counted {
        fn next_u32(&mut self)->u32 {self.halves+=1;self.rng.next_u32()}
        fn next_u64(&mut self)->u64 {self.halves+=2;self.rng.next_u64()}
        fn fill_bytes(&mut self,dest:&mut[u8]) {self.halves+=(dest.len()as u64).div_ceil(4);self.rng.fill_bytes(dest)}
    }
    let mut rng=Counted{rng:StdRng::seed_from_u64(seed),halves:0};
    std::iter::from_fn(move||{let x:f64=StandardNormal.sample(&mut rng);assert_eq!(rng.halves%2,0,"Unexpected glare u32 sampling");Some((x,rng.halves/2))})
}
