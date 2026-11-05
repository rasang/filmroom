//! Exact ChaCha12 word stream and guarded integer ZIGNOR predicates.
//! All selection predicates are integer Q48, never f32 transcendentals. An
//! ambiguous selected predicate rejects the complete stage before publication.
//! See GLARE_INTEGER_ERROR_BOUND.md for the conservative absolute guard.
#[path = "glare_tables.rs"]
mod tables;
use tables::{ZIG_NORM_F as F, ZIG_NORM_R as RADIUS, ZIG_NORM_X as X};
pub(super) const ONE: u64 = 1 << 48;
pub(super) const GUARD: u64 = 1 << 16; // 2^-32 absolute in Q48.
pub(crate) const WORD_CAPACITY: u32 = 65536;
pub(crate) const OUTPUT_CAPACITY: u32 = 16384;
pub(crate) const TABLE_BASE: u32 = 327680;
pub(super) const SEED: u64 = 42;
pub(super) const SHADER: &str = include_str!("../shaders/research_finish_glare.wgsl");
#[derive(Clone, Debug)]
pub(crate) struct Tables {
    pub key: [u32; 8],
    pub x: [u64; 257],
    pub f: [u64; 257],
    pub first_reject: [u64; 256],
    pub ln2: u64,
    pub inverse_radius: u64,
    pub radius: u64,
    pub mu: f32,
    pub sigma: f32,
}
fn q(x: f64) -> u64 {
    (x * ONE as f64).round() as u64
}
impl Tables {
    pub fn new(percent: f32, roughness: f32) -> Result<Self, String> {
        if !percent.is_finite() || percent <= 0. || !roughness.is_finite() || roughness < 0. {
            return Err("INVALID_TAIL_GLARE".into());
        }
        let m = percent as f64;
        let s = (roughness * percent) as f64;
        let sigma2 = (1. + s * s / (m * m)).ln();
        let mu = m.ln() - sigma2 / 2.;
        let sigma = sigma2.sqrt();
        if !mu.is_finite()
            || !sigma.is_finite()
            || !(mu as f32).is_finite()
            || !(sigma as f32).is_finite()
        {
            return Err("INVALID_TAIL_GLARE".into());
        }
        // This is rand_core's documented seed_from_u64 expansion, not a replacement
        // RNG: the image's random words come exclusively from the original ChaCha12.
        let mut state = SEED;
        let key = std::array::from_fn(|_| {
            state = state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(11634580027462260723);
            let x = (((state >> 18) ^ state) >> 27) as u32;
            x.rotate_right((state >> 59) as u32)
        });
        let first_reject = std::array::from_fn(|i| {
            let (mut lo, mut hi) = (0u64, (1u64 << 51) + 1);
            while lo < hi {
                let mid = (lo + hi) / 2;
                let u = mid as f64 / (1u64 << 51) as f64;
                if u * X[i] < X[i + 1] {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            lo
        });
        Ok(Self {
            key,
            x: X.map(q),
            f: F.map(q),
            first_reject,
            ln2: q(std::f64::consts::LN_2),
            inverse_radius: q(1. / RADIUS),
            radius: q(RADIUS),
            mu: mu as f32,
            sigma: sigma as f32,
        })
    }
    pub fn packed(&self) -> Vec<f32> {
        let mut out = vec![0.; 32];
        for (i, &word) in self.key.iter().enumerate() {
            out[2 * i] = (word & 65535) as f32;
            out[2 * i + 1] = (word >> 16) as f32;
        }
        out[16] = self.mu;
        out[17] = self.sigma;
        for v in self
            .x
            .iter()
            .chain(self.f.iter())
            .chain(self.first_reject.iter())
            .chain([self.ln2, self.inverse_radius, self.radius].iter())
        {
            out.extend((0..4).map(|i| ((v >> (16 * i)) & 65535) as f32));
        }
        out
    }
}
fn round(x: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    x[a] = x[a].wrapping_add(x[b]);
    x[d] = (x[d] ^ x[a]).rotate_left(16);
    x[c] = x[c].wrapping_add(x[d]);
    x[b] = (x[b] ^ x[c]).rotate_left(12);
    x[a] = x[a].wrapping_add(x[b]);
    x[d] = (x[d] ^ x[a]).rotate_left(8);
    x[c] = x[c].wrapping_add(x[d]);
    x[b] = (x[b] ^ x[c]).rotate_left(7);
}
pub(super) fn word(key: &[u32; 8], index: u64) -> u64 {
    let block = index / 8;
    let initial = [
        0x61707865,
        0x3320646e,
        0x79622d32,
        0x6b206574,
        key[0],
        key[1],
        key[2],
        key[3],
        key[4],
        key[5],
        key[6],
        key[7],
        block as u32,
        (block >> 32) as u32,
        0,
        0,
    ];
    let mut x = initial;
    for _ in 0..6 {
        round(&mut x, 0, 4, 8, 12);
        round(&mut x, 1, 5, 9, 13);
        round(&mut x, 2, 6, 10, 14);
        round(&mut x, 3, 7, 11, 15);
        round(&mut x, 0, 5, 10, 15);
        round(&mut x, 1, 6, 11, 12);
        round(&mut x, 2, 7, 8, 13);
        round(&mut x, 3, 4, 9, 14);
    }
    let i = (index % 8) as usize * 2;
    let lo = x[i].wrapping_add(initial[i]);
    let hi = x[i + 1].wrapping_add(initial[i + 1]);
    lo as u64 | ((hi as u64) << 32)
}
fn mul(a: u64, b: u64) -> u64 {
    ((a as u128 * b as u128) >> 48) as u64
}
fn exp_negative(x: u64) -> u64 {
    let z = x >> 8;
    let mut sum = ONE;
    let mut term = ONE;
    for k in 1..=12 {
        term = mul(term, z) / k;
        if k % 2 == 0 {
            sum += term;
        } else {
            sum -= term;
        }
    }
    for _ in 0..8 {
        sum = mul(sum, sum);
    }
    sum
}
fn negative_log_open(bits: u64, t: &Tables) -> u64 {
    let n = ((bits >> 12) << 1) | 1;
    let b = 63 - n.leading_zeros();
    let m = if b > 48 { n >> (b - 48) } else { n << (48 - b) };
    let z = ((((m - ONE) as u128) << 48) / (m + ONE) as u128) as u64;
    let z2 = mul(z, z);
    let mut term = z;
    let mut sum = z;
    for k in (3..=63).step_by(2) {
        term = mul(term, z2);
        sum += term / k;
    }
    ((53 - b) as u64) * t.ln2 - 2 * sum
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Failure {
    Boundary,
    Ambiguous,
    Iterations,
}
#[derive(Clone, Copy, Debug)]
pub(super) struct Candidate {
    pub value: f32,
    pub consumed: u32,
}
/// Returns the original stream's selected normal variate (narrowed to f32), or
/// refuses the entire stage when a bounded predicate cannot prove its branch.
pub(super) fn candidate(t: &Tables, start: u64, end: u64) -> Result<Candidate, Failure> {
    let mut cursor = start;
    let next = |cursor: &mut u64| -> Result<u64, Failure> {
        if *cursor >= end {
            return Err(Failure::Boundary);
        }
        let x = word(&t.key, *cursor);
        *cursor += 1;
        Ok(x)
    };
    for _ in 0..16 {
        let bits = next(&mut cursor)?;
        let i = (bits & 255) as usize;
        let mant = bits >> 12;
        let center = 1u64 << 51;
        let negative = mant < center;
        let numerator = mant.abs_diff(center);
        let x = ((numerator as u128 * t.x[i] as u128) >> 51) as u64;
        if numerator < t.first_reject[i] {
            return Ok(Candidate {
                value: if negative {
                    -(x as f64 / ONE as f64) as f32
                } else {
                    (x as f64 / ONE as f64) as f32
                },
                consumed: (cursor - start) as u32,
            });
        }
        if i == 0 {
            for _ in 0..32 {
                let nx = mul(negative_log_open(next(&mut cursor)?, t), t.inverse_radius);
                let ny = negative_log_open(next(&mut cursor)?, t);
                let lhs = 2 * ny;
                let rhs = mul(nx, nx);
                if lhs.abs_diff(rhs) <= GUARD {
                    return Err(Failure::Ambiguous);
                }
                if lhs >= rhs {
                    let v = (nx + t.radius) as f64 / ONE as f64;
                    return Ok(Candidate {
                        value: if negative { -(v as f32) } else { v as f32 },
                        consumed: (cursor - start) as u32,
                    });
                }
            }
            return Err(Failure::Iterations);
        }
        let u = next(&mut cursor)? >> 16;
        let left = t.f[i + 1] - mul(t.f[i + 1] - t.f[i], u);
        let right = exp_negative(mul(x, x) >> 1);
        if left.abs_diff(right) <= GUARD {
            return Err(Failure::Ambiguous);
        }
        if left < right {
            return Ok(Candidate {
                value: if negative {
                    -(x as f64 / ONE as f64) as f32
                } else {
                    (x as f64 / ONE as f64) as f32
                },
                consumed: (cursor - start) as u32,
            });
        }
    }
    Err(Failure::Iterations)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn error_bound_fixedpoint_extremes() {
        let t = Tables::new(0.3, 0.7).unwrap();
        let mut worst = 0f64;
        for i in 0..=100000 {
            let x = 8. * i as f64 / 100000.;
            let a = exp_negative(q(x)) as f64 / ONE as f64;
            let e = (a - (-x).exp()).abs();
            worst = worst.max(e);
            assert!(e < 12000. / ONE as f64);
        }
        for bits in [
            0,
            1,
            4095,
            4096,
            1u64 << 32,
            1u64 << 63,
            u64::MAX - 4096,
            u64::MAX,
        ] {
            let n = ((bits >> 12) << 1) | 1;
            let x = n as f64 / (1u64 << 53) as f64;
            let a = negative_log_open(bits, &t) as f64 / ONE as f64;
            assert!((a + x.ln()).abs() < 256. / ONE as f64);
        }
        eprintln!("GLARE_Q48_EXP_MAX_ABS={worst}");
    }
}

#[cfg(test)]
mod sequence_tests {
    use super::*;
    #[test]
    fn chacha12_words_match_original_std_rng_and_random_access() {
        let t = Tables::new(0.3, 0.7).unwrap();
        let original = spektrafilm_model::glare::research_glare_rng_words(SEED, 131073).unwrap();
        for (i, &value) in original.iter().enumerate() {
            assert_eq!(word(&t.key, i as u64), value, "word {i}");
        }
        for i in [0, 1, 7, 8, 31, 32, 65535, 65536, 131072] {
            assert_eq!(word(&t.key, i as u64), original[i]);
        }
    }
    #[test]
    fn exact_fast_thresholds_preserve_original_rounded_f64_decisions() {
        let t = Tables::new(0.3, 0.7).unwrap();
        for i in 0..256 {
            let threshold = t.first_reject[i];
            for n in [
                0,
                threshold.saturating_sub(1),
                threshold,
                threshold.saturating_add(1).min(1 << 51),
                1 << 51,
            ] {
                let original = (n as f64 / (1u64 << 51) as f64) * X[i] < X[i + 1];
                assert_eq!(n < threshold, original, "index {i}, numerator {n}");
            }
        }
    }
    #[test]
    fn original_long_sequence_and_consumption_survive_window_boundaries() {
        let t = Tables::new(0.3, 0.7).unwrap();
        let count = std::env::var("FILM_GLARE_SEQUENCE_SAMPLES")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(200000)
            .clamp(200000, 32_000_000);
        for window in [97u64, 1024, 65536] {
            let mut cursor = 0u64;
            let mut end = window;
            let mut boundaries = 0u64;
            let mut max_abs = 0f64;
            for (i, (expected, consumed)) in
                spektrafilm_model::glare::research_glare_normal_stream(SEED)
                    .take(count)
                    .enumerate()
            {
                let value = loop {
                    match candidate(&t, cursor, end) {
                        Ok(v) => break v,
                        Err(Failure::Boundary) => {
                            end = cursor + window;
                            boundaries += 1;
                        }
                        Err(e) => panic!("sample {i} cursor {cursor}: {e:?}"),
                    }
                };
                cursor += value.consumed as u64;
                assert_eq!(cursor, consumed, "sample {i}, window {window}");
                let error = (value.value as f64 - expected).abs();
                assert!(error < 4e-7, "sample {i}: {error}");
                max_abs = max_abs.max(error);
            }
            assert!(boundaries > 0);
            eprintln!(
                "GLARE_ORIGINAL_STREAM samples={} window={} final_words={} boundaries={} max_abs={}",
                count, window, cursor, boundaries, max_abs
            );
        }
    }
    #[test]
    fn ambiguous_and_iteration_overrun_are_rejected_not_resampled() {
        let mut t = Tables::new(0.3, 0.7).unwrap();
        let start = (0u64..1000).find(|&i| word(&t.key, i) & 255 != 0).unwrap();
        let bits = word(&t.key, start);
        let i = (bits & 255) as usize;
        let numerator = (bits >> 12).abs_diff(1 << 51);
        let x = ((numerator as u128 * t.x[i] as u128) >> 51) as u64;
        let target = exp_negative(mul(x, x) >> 1);
        t.first_reject[i] = 0;
        t.f[i] = target;
        t.f[i + 1] = target;
        assert_eq!(
            candidate(&t, start, start + 96).unwrap_err(),
            Failure::Ambiguous
        );
        assert_eq!(candidate(&t, start, start).unwrap_err(), Failure::Boundary);
        let mut t = Tables::new(0.3, 0.7).unwrap();
        t.first_reject.fill(0);
        t.f.fill(ONE);
        let start = (0u64..1000)
            .find(|&s| (0..16).all(|i| word(&t.key, s + 2 * i) & 255 != 0))
            .unwrap();
        assert_eq!(
            candidate(&t, start, start + 96).unwrap_err(),
            Failure::Iterations
        );
    }
}
