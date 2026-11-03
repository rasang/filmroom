//! Per-request execution choices, deliberately excluded from pixel/checkpoint identity.
#[derive(Clone, Copy, Debug, Default, serde::Serialize)]
pub struct ExportOptimizations {
    pub gpu_resource_reuse: bool,
    pub accelerated_checkpoint_sha: bool,
    pub fused_exponential_io: bool,
    pub parallel_grain_interpolation: bool,
    pub fft_transpose_256: bool,
    pub fused_diffusion_fft: bool,
    pub exact_grain_sampling: bool,
    pub prepared_diffusion_kernel: bool,
}
impl ExportOptimizations {
    pub fn from_bits(bits: u32) -> Option<Self> {
        if bits & !127 != 0 { return None; }
        Some(Self { gpu_resource_reuse:false, accelerated_checkpoint_sha: bits & 1 != 0, fused_exponential_io: bits & 2 != 0,
            parallel_grain_interpolation: bits & 4 != 0, fft_transpose_256: bits & 8 != 0, fused_diffusion_fft: bits & 16 != 0, exact_grain_sampling: bits & 32 != 0, prepared_diffusion_kernel: bits & 64 != 0 })
    }
    pub fn bits(self) -> u32 { u32::from(self.accelerated_checkpoint_sha) | (u32::from(self.fused_exponential_io)<<1) | (u32::from(self.parallel_grain_interpolation)<<2) | (u32::from(self.fft_transpose_256)<<3) | (u32::from(self.fused_diffusion_fft)<<4) | (u32::from(self.exact_grain_sampling)<<5) | (u32::from(self.prepared_diffusion_kernel)<<6) }
}
#[cfg(test)] mod tests { use super::*; #[test] fn bounded_and_baseline() { assert_eq!(ExportOptimizations::default().bits(),0); for bits in 0..128 {assert_eq!(ExportOptimizations::from_bits(bits).unwrap().bits(), bits);} for bits in [128,255,u32::MAX] {assert!(ExportOptimizations::from_bits(bits).is_none());} } }
