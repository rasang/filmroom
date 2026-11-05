//! Addressing-only contract for export CoordinateSplitMix64 grain.
//! No dependency on the resident GPU allocator or scientific implementation.
pub const CAPACITY: u32 = 16_384;
pub const STREAM_TAG: u32 = 0x4753_0001;
pub const STREAM_X: usize = 32;
pub const STREAM_Y: usize = 33;
pub const STREAM_MODE: usize = 34;
pub const SAMPLER_PHASES: [u32; 4] = [63, 65, 66, 64];
const ERROR: &str = "GPU_GRAIN_STREAM_GEOMETRY";
pub type Result<T> = std::result::Result<T, &'static str>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Chunk {
    width: u32,
    height: u32,
    start: u64,
    count: u32,
}
impl Chunk {
    pub fn new(width: u32, height: u32, start: u64, count: u32) -> Result<Self> {
        let pixels = u64::from(width) * u64::from(height);
        if width == 0
            || height == 0
            || count == 0
            || count > CAPACITY
            || start >= pixels
            || u64::from(count) > pixels - start
            || pixels.checked_mul(8).is_none()
        {
            return Err(ERROR);
        }
        Ok(Self {
            width,
            height,
            start,
            count,
        })
    }
    pub fn start(self) -> u64 {
        self.start
    }
    pub fn count(self) -> u32 {
        self.count
    }
    pub fn input_file_offset(self) -> u64 {
        self.start * 8
    }
    pub fn output_file_offset(self) -> u64 {
        self.start * 4
    }
    pub fn base_xy(self) -> [u32; 2] {
        [
            (self.start % u64::from(self.width)) as u32,
            (self.start / u64::from(self.width)) as u32,
        ]
    }
    /// Reference identity, never a scratch address. Origin wrapping is intentional.
    pub fn coordinate(self, local: u32, origin: [u32; 2]) -> Result<[u32; 2]> {
        if local >= self.count {
            return Err(ERROR);
        }
        let index = self.start + u64::from(local);
        Ok([
            origin[0].wrapping_add((index % u64::from(self.width)) as u32),
            origin[1].wrapping_add((index / u64::from(self.width)) as u32),
        ])
    }
    /// Preserve layer/profile constants supplied by caller, set addressing only.
    /// Must be used for every production dispatch, including pointwise combines.
    /// This explicitly excludes serial MT and spatial operations.
    pub fn apply(self, mut p: [u32; 40], op: u32) -> Result<[u32; 40]> {
        if !matches!(op, 50 | 54 | 56 | 57 | 58 | 59 | 60 | 63 | 64 | 65 | 66)
            || p[11] != 0
            || p[12] != 0
            || p[13] != 0
        {
            return Err(ERROR);
        }
        p[0] = self.width;
        p[1] = self.height;
        p[2] = op;
        p[3] = 0; // No dispatch base. Every typed frame starts at local lane zero.
        p[4] = self.count;
        p[5] = self.count;
        p[7] = 0; // Plane-local offset, deliberately unrelated to file position.
        let [x, y] = self.base_xy();
        p[STREAM_X] = x;
        p[STREAM_Y] = y;
        p[STREAM_MODE] = STREAM_TAG;
        Ok(p)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn boundary_and_tail_chunks_keep_global_identity() {
        for width in [1, 17, 16_383, 16_384, 16_385, 50_001] {
            let height = 40_000;
            let n = u64::from(width) * u64::from(height);
            for start in [0, 1, 16_383, 16_384, n - 1] {
                let count = (n - start).min(u64::from(CAPACITY)) as u32;
                let c = Chunk::new(width, height, start, count).unwrap();
                let [bx, by] = c.base_xy();
                for i in 0..count {
                    // Exact shader algorithm, including row crossings for tiny widths.
                    let dx = i % width;
                    let carry = u32::from(dx >= width - bx);
                    let x = if carry != 0 {
                        dx - (width - bx)
                    } else {
                        bx + dx
                    };
                    let y = by + i / width + carry;
                    assert_eq!(
                        c.coordinate(i, [u32::MAX - 7, u32::MAX - 3]).unwrap(),
                        [
                            (u32::MAX - 7).wrapping_add(x),
                            (u32::MAX - 3).wrapping_add(y)
                        ]
                    );
                }
                let p = c.apply([0; 40], 63).unwrap();
                assert_eq!((p[3], p[7], p[4], p[5]), (0, 0, count, count));
            }
        }
    }
    #[test]
    fn offsets_above_u32_and_128_mib_remain_file_only() {
        for start in [33_554_432u64, u64::from(u32::MAX) + 19] {
            let c = Chunk::new(100_003, 100_003, start, CAPACITY).unwrap();
            assert_eq!(c.input_file_offset(), start * 8);
            assert_eq!(c.apply([0; 40], 64).unwrap()[7], 0);
        }
    }
    #[test]
    fn invalid_geometry_and_mt_are_rejected() {
        assert!(Chunk::new(0, 1, 0, 1).is_err());
        assert!(Chunk::new(1, 1, 1, 1).is_err());
        assert!(Chunk::new(100, 100, 0, CAPACITY + 1).is_err());
        assert!(Chunk::new(u32::MAX, u32::MAX, 0, 1).is_err());
        let c = Chunk::new(100, 100, 0, 1).unwrap();
        assert!(c.apply([0; 40], 52).is_err());
        let mut p = [0; 40];
        p[11] = 1;
        assert!(c.apply(p, 63).is_err());
    }
}
