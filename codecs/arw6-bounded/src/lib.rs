//! Pinned ARW6 bounded integer codec. Full-image reference exists only in the separate oracle workspace.
pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
pub mod bounded;
mod container;
mod entropy;
pub(crate) mod reference;
pub mod storage;
pub(crate) mod bits {
    #[allow(non_snake_case)]
    pub fn LEu32(b: &[u8], o: usize) -> u32 {
        u32::from_le_bytes(b[o..o + 4].try_into().unwrap())
    }
    pub fn log2ceil(v: usize) -> usize {
        if v <= 1 {
            0
        } else {
            (usize::BITS - (v - 1).leading_zeros()) as usize
        }
    }
    pub fn clampbits(v: i32, b: u32) -> u16 {
        v.clamp(0, (1i32 << b) - 1) as u16
    }
}
