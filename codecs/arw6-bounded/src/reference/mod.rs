// SPDX-License-Identifier: LGPL-2.1
// Fixed donor primitives, with memory-only reference helpers restricted to tests.
pub(crate) mod geometry;
#[cfg(test)]
pub(crate) mod idwt;
pub(crate) mod pixelops;
pub(crate) const CODEC_BIT_DEPTH: u32 = 12;
pub(crate) type BitPump<'a> =
    bitstream_io::BitReader<std::io::Cursor<&'a [u8]>, bitstream_io::BigEndian>;
#[cfg(test)]
pub(crate) struct Plane {
    pub w: usize,
    pub h: usize,
    pub data: Vec<i32>,
}
#[cfg(test)]
impl Plane {
    pub fn new(w: usize, h: usize) -> Self {
        Self {
            w,
            h,
            data: vec![0; w * h],
        }
    }
    pub fn row(&self, r: usize) -> &[i32] {
        &self.data[r * self.w..(r + 1) * self.w]
    }
    pub fn row_mut(&mut self, r: usize) -> &mut [i32] {
        &mut self.data[r * self.w..(r + 1) * self.w]
    }
}
