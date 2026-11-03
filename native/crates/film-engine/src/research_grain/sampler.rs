//! Bounded f32 arithmetic mirror of the existing Poisson/BTPE algorithms.
//! Random integer words are exact. Distribution branches/counts are NOT f64 exact.
//! This host mirror is not evidence of real GPU/driver execution.
use super::{R, LOG_FACTORIAL_COUNT};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Pair(pub u32, pub u32);
impl Pair {
    pub fn from(v: u64) -> Self { Self(v as u32, (v >> 32) as u32) }
    pub fn value(self) -> u64 { self.0 as u64 | ((self.1 as u64) << 32) }
    pub fn add(self, b: Self) -> Self { let lo = self.0.wrapping_add(b.0); Self(lo, self.1.wrapping_add(b.1).wrapping_add(u32::from(lo < self.0))) }
    pub fn xor(self, b: Self) -> Self { Self(self.0 ^ b.0, self.1 ^ b.1) }
    pub fn shr(self, n: u32) -> Self { Self((self.0 >> n) | (self.1 << (32 - n)), self.1 >> n) }
    pub fn mul(self, b: Self) -> Self {
        let (a0,a1,b0,b1) = (self.0 & 65535,self.0 >> 16,b.0 & 65535,b.0 >> 16);
        let w0=a0*b0; let t=a1*b0+(w0>>16); let w1=a0*b1+(t&65535);
        let hi=a1*b1+(t>>16)+(w1>>16);
        Self(self.0.wrapping_mul(b.0),hi.wrapping_add(self.0.wrapping_mul(b.1)).wrapping_add(self.1.wrapping_mul(b.0)))
    }
    pub fn mix(self) -> Self { let z=self.xor(self.shr(30)).mul(Self::from(0xbf58476d1ce4e5b9)); let z=z.xor(z.shr(27)).mul(Self::from(0x94d049bb133111eb)); z.xor(z.shr(31)) }
}
pub const MAX_STEPS: u32 = 4096;
pub struct Rng<'a> { pub state: Pair, pub words: u32, pub steps: u32, pub failed: bool, table: &'a [f32] }
impl<'a> Rng<'a> {
    pub fn new(seed:u64,x:u32,y:u32,table:&'a [f32])->Self {
        let inc=Pair::from(0x9e3779b97f4a7c15);
        let state=Pair::from(seed).xor(Pair(x,0).mul(inc)).xor(Pair(y,0).mul(Pair::from(0xd1b54a32d192ed03))).add(inc).mix();
        Self {state, words:0, steps:0, failed:false, table}
    }
    pub fn tick(&mut self)->bool {self.steps+=1; if self.steps>MAX_STEPS {self.failed=true;} !self.failed}
    pub fn next_word(&mut self)->u64 { self.state=self.state.add(Pair::from(0x9e3779b97f4a7c15)); self.words+=1; self.state.mix().value() }
}
pub fn rk_double(rng:&mut Rng)->f32 {
    let a=(rng.next_word()>>32) as u32 >> 5;
    let b=(rng.next_word()>>32) as u32 >> 6;
    // Correctly round the original 53-bit uniform ONCE to f32. Converting a
    // and b independently to f32 first introduces a second rounding boundary.
    let bits=if a!=0 {32-a.leading_zeros()+26}else{32-b.leading_zeros()};
    let lo=(a<<26)|b;let hi=a>>6;
    if bits<=24 {return lo as f32*f32::from_bits(74u32<<23);}
    let shift=bits-24;let mut mantissa=(lo>>shift)|(hi<<(32-shift));
    let rest=lo&((1u32<<shift)-1);let half=1u32<<(shift-1);
    if rest>half || (rest==half && mantissa&1!=0){mantissa+=1;}
    mantissa as f32*f32::from_bits((74+shift)<<23)
}
fn log_factorial(k:i64,rng:&mut Rng)->f32 {if k<0 || k as usize>=rng.table.len() {rng.failed=true;return f32::NAN;}rng.table[k as usize]}
pub fn factorial_table()->Vec<f32> {
    let original=spektrafilm_math::numpy_rng::PreparedPoisson::new();
    (0..LOG_FACTORIAL_COUNT).map(|k| original.log_factorial(k as i64) as f32).collect()
}
#[derive(Clone,Copy,Debug)]
pub struct Sample {pub particles:u32,pub developed:u32,pub words:u32,pub value:f32}
pub fn particle(seed:u64,x:u32,y:u32,density:f32,dmax:f32,npp:f32,gu:f32,table:&[f32])->R<Sample> {
    if !density.is_finite() || !dmax.is_finite() || dmax<=0. || !npp.is_finite() || npp<=0. || !gu.is_finite() {return Err("GPU_GRAIN_NUMERIC_CHECK".into());}
    let p=(density/dmax).clamp(1e-6,1.0-1e-6);
    let saturation=1.0-p*gu*(1.0-1e-6);
    let lambda=npp/saturation;
    if !lambda.is_finite() || lambda<0. || lambda>super::MAX_LAMBDA {return Err("GPU_GRAIN_COUNT_LIMIT".into());}
    let mut rng=Rng::new(seed,x,y,table);
    let particles=rk_poisson(&mut rng,lambda);
    let developed=rk_binomial(&mut rng,particles,p);
    let value=developed as f32*(dmax/npp)*saturation;
    if rng.failed || !value.is_finite() || particles>=LOG_FACTORIAL_COUNT as u64 || developed>particles {return Err("GPU_GRAIN_SAMPLER_LIMIT".into());}
    Ok(Sample {particles:particles as u32,developed:developed as u32,words:rng.words,value})
}
pub fn microstructure(seed:u64,x:u32,y:u32,sigma_log:f32,mu:f32)->f32 {
    let mut rng=Rng::new(seed,x,y,&[]);
    // A genuine zero uniform occurs only when both original words contribute zero.
    // MIN_POSITIVE is the explicit f32 counterpart of the production f64 floor.
    let u=rk_double(&mut rng).max(f32::MIN_POSITIVE);let v=rk_double(&mut rng);
    let normal=(-2.0*u.ln()).sqrt()*(std::f32::consts::TAU*v).cos();
    (mu+sigma_log*normal).exp()
}
#[inline]
pub fn rk_poisson(rng: &mut Rng, lam: f32) -> u64 {
    if lam >= 10.0 {
        rk_poisson_ptrs(rng, lam)
    } else if lam == 0.0 {
        0
    } else {
        rk_poisson_mult(rng, lam)
    }
}

fn rk_poisson_mult(rng: &mut Rng, lam: f32) -> u64 {
    let enlam = (-lam).exp();
    let mut x: u64 = 0;
    let mut prod = 1.0_f32;
    loop {
        if !rng.tick() { return 0; }
        let u = rk_double(rng);
        prod *= u;
        if prod > enlam {
            x += 1;
        } else {
            return x;
        }
    }
}

fn rk_poisson_ptrs(rng: &mut Rng, lam: f32) -> u64 {
    let slam = lam.sqrt();
    let loglam = lam.ln();
    let b = 0.931 + 2.53 * slam;
    let a = -0.059 + 0.02483 * b;
    let invalpha = 1.1239 + 1.1328 / (b - 3.4);
    let vr = 0.9277 - 3.6224 / (b - 2.0);
    loop {
        if !rng.tick() { return 0; }
        let u = rk_double(rng) - 0.5;
        let v = rk_double(rng);
        let us = 0.5 - u.abs();
        let k_f = ((2.0 * a / us + b) * u + lam + 0.43).floor();
        if us >= 0.07 && v <= vr { return k_f as u64; }
        if k_f < 0.0 || (us < 0.013 && v > us) { continue; }
        // A proposal is not a returned count. For lambda <= 240000 and
        // k >= 262144, log P(k) <= -991 by k! >= (k/e)^k, while the
        // original 53-bit nonzero uniforms make PTRS lhs > -114.
        // Thus this is a proven rejection, including f32 infinite tails.
        // v == 0 is excluded from the proof and fails the entire candidate.
        if k_f >= LOG_FACTORIAL_COUNT as f32 && v > 0.0 && lam <= super::MAX_LAMBDA {continue;}
        if !k_f.is_finite() || k_f >= LOG_FACTORIAL_COUNT as f32 {rng.failed=true;return 0;}
        let k = k_f as i64;
        let lhs = v.ln() + invalpha.ln() - (a / (us * us) + b).ln();
        let rhs = -lam + (k as f32) * loglam - log_factorial(k, rng);
        if lhs <= rhs {
            return k as u64;
        }
    }
}

#[inline]
pub fn rk_binomial(rng: &mut Rng, n: u64, p: f32) -> u64 {
    if p <= 0.5 {
        if (p * n as f32) <= 30.0 {
            rk_binomial_inversion(rng, n, p)
        } else {
            rk_binomial_btpe(rng, n, p)
        }
    } else {
        let q = 1.0 - p;
        if (q * n as f32) <= 30.0 {
            n - rk_binomial_inversion(rng, n, q)
        } else {
            n - rk_binomial_btpe(rng, n, q)
        }
    }
}

fn rk_binomial_inversion(rng: &mut Rng, n: u64, p: f32) -> u64 {
    let q = 1.0 - p;
    let qn = ((n as f32) * q.ln()).exp();
    let np = (n as f32) * p;
    let bound = (n as f32).min(np + 10.0 * (np * q + 1.0).sqrt()) as u64;
    let mut x: u64 = 0;
    let mut px = qn;
    let mut u = rk_double(rng);
    while u > px {
        if !rng.tick() { return 0; }
        x += 1;
        if x > bound {
            x = 0;
            px = qn;
            u = rk_double(rng);
        } else {
            u -= px;
            px = (((n - x + 1) as f32) * p * px) / ((x as f32) * q);
        }
    }
    x
}

#[allow(clippy::many_single_char_names)]
fn rk_binomial_btpe(rng: &mut Rng, n: u64, p: f32) -> u64 {
    let r = p.min(1.0 - p);
    let q = 1.0 - r;
    let nf = n as f32;
    let fm = nf * r + r;
    let m = fm.floor() as i64;
    let p1 = (2.195 * (nf * r * q).sqrt() - 4.6 * q).floor() + 0.5;
    let xm = m as f32 + 0.5;
    let xl = xm - p1;
    let xr = xm + p1;
    let c = 0.134 + 20.5 / (15.3 + m as f32);
    let a1 = (fm - xl) / (fm - xl * r);
    let laml = a1 * (1.0 + a1 / 2.0);
    let a2 = (xr - fm) / (xr * q);
    let lamr = a2 * (1.0 + a2 / 2.0);
    let p2 = p1 * (1.0 + 2.0 * c);
    let p3 = p2 + c / laml;
    let p4 = p3 + c / lamr;
    let nrq = nf * r * q;

    let y: i64;
    'outer: loop {
        if !rng.tick() { return 0; }
        let u = rk_double(rng) * p4;
        let mut v = rk_double(rng);

        let cand;
        if u <= p1 {
            cand = (xm - p1 * v + u).floor() as i64;
            y = cand;
            break 'outer;
        } else if u <= p2 {
            let x = xl + (u - p1) / c;
            v = v * c + 1.0 - ((m as f32) - x + 0.5).abs() / p1;
            if v > 1.0 {
                continue 'outer;
            }
            cand = x.floor() as i64;
        } else if u <= p3 {
            cand = (xl + v.ln() / laml).floor() as i64;
            if cand < 0 {
                continue 'outer;
            }
            v *= (u - p2) * laml;
        } else {
            cand = (xr - v.ln() / lamr).floor() as i64;
            if cand > n as i64 {
                continue 'outer;
            }
            v *= (u - p3) * lamr;
        }

        let k = (cand - m).unsigned_abs() as i64;
        if k <= 20 || k as f32 >= nrq / 2.0 - 1.0 {
            let s = r / q;
            let a_step51 = s * (nf + 1.0);
            let mut f = 1.0_f32;
            if m < cand {
                for i in (m + 1)..=cand {
                    if !rng.tick() { return 0; }
                    f *= a_step51 / (i as f32) - s;
                }
            } else if m > cand {
                for i in (cand + 1)..=m {
                    if !rng.tick() { return 0; }
                    f /= a_step51 / (i as f32) - s;
                }
            }
            if v > f {
                continue 'outer;
            }
        } else {
            let kf = k as f32;
            let rho =
                (kf / nrq) * ((kf * (kf / 3.0 + 0.625) + 0.166_666_666_666_666_66) / nrq + 0.5);
            let t = -kf * kf / (2.0 * nrq);
            let a_log = v.ln();
            if a_log < t - rho {
                y = cand;
                break 'outer;
            }
            if a_log > t + rho {
                continue 'outer;
            }
            let x1 = (cand + 1) as f32;
            let f1 = (m + 1) as f32;
            let z = (n as i64 - m + 1) as f32;
            let w = (n as i64 - cand + 1) as f32;
            let x2 = x1 * x1;
            let f2 = f1 * f1;
            let z2 = z * z;
            let w2 = w * w;
            let bound = xm * (f1 / x1).ln()
                + (nf - m as f32 + 0.5) * (z / w).ln()
                + ((cand - m) as f32) * (w * r / (x1 * q)).ln()
                + (13680.0 - (462.0 - (132.0 - (99.0 - 140.0 / f2) / f2) / f2) / f2)
                    / f1
                    / 166320.0
                + (13680.0 - (462.0 - (132.0 - (99.0 - 140.0 / z2) / z2) / z2) / z2) / z / 166320.0
                + (13680.0 - (462.0 - (132.0 - (99.0 - 140.0 / x2) / x2) / x2) / x2)
                    / x1
                    / 166320.0
                + (13680.0 - (462.0 - (132.0 - (99.0 - 140.0 / w2) / w2) / w2) / w2) / w / 166320.0;
            if a_log > bound {
                continue 'outer;
            }
        }
        y = cand;
        break 'outer;
    }

    if y < 0 || y > n as i64 { rng.failed = true; return 0; }
    let y_pos = if p > 0.5 { n as i64 - y } else { y };
    y_pos as u64
}
