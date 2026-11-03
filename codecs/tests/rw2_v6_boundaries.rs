//! Standalone tests of the actual production Panasonic v6 decoder.
//! Run with cargo test --test rw2_v6_boundaries.
//! Dependency stubs below provide scheduling/storage/logging only. Decoder arithmetic
//! is included from production unchanged; fixtures encode a real 16-byte v6 packet.

extern crate self as log;
#[macro_export]
macro_rules! debug { ($($arg:tt)*) => {{}}; }

mod pumps {
    pub trait BitPump {
        fn get_bits(&mut self, count: u32) -> u32;
    }
    pub struct BitPumpLSB<'a> {
        bytes: &'a [u8],
        bit_offset: usize,
    }
    impl<'a> BitPumpLSB<'a> {
        pub fn new(bytes: &'a [u8]) -> Self {
            Self { bytes, bit_offset: 0 }
        }
    }
    impl BitPump for BitPumpLSB<'_> {
        fn get_bits(&mut self, count: u32) -> u32 {
            assert!(count <= 32);
            assert!(self.bit_offset + count as usize <= self.bytes.len() * 8,
                "test packet bitstream exhausted");
            let mut value = 0u32;
            for i in 0..count as usize {
                let at = self.bit_offset + i;
                value |= u32::from((self.bytes[at / 8] >> (at % 8)) & 1) << i;
            }
            self.bit_offset += count as usize;
            value
        }
    }
}

mod decoders {
    pub type PixU16 = Vec<u16>;
    pub fn decode_threaded<F>(width: usize, height: usize, dummy: bool, decode: &F) -> PixU16
    where F: Fn(&mut [u16], usize) + Sync {
        if dummy { return Vec::new(); }
        let mut output = vec![0u16; width * height];
        for (row, pixels) in output.chunks_exact_mut(width).enumerate() {
            decode(pixels, row);
        }
        output
    }
}

#[allow(dead_code)]
mod production {
    include!("../vendor/rawler-0.7.2/src/decoders/rw2/v6decompressor.rs");
}

fn width(bits: u32) -> usize {
    match bits { 12 => 14, 14 => 11, _ => panic!("fixture supports only 12/14 bits") }
}

/// The decoder reads pixelbuffer[0] last in LSB order: final 12 or14 bits
/// of this 128-bit packet. Other encoded samples/base selectors remain zero.
fn first_sample_packet(bits: u32, sample: u16) -> [u8; 16] {
    assert!(u32::from(sample) < (1u32 << bits));
    let mut packet = [0u8; 16];
    for i in 0..bits as usize {
        let at = 128 - bits as usize + i;
        packet[at / 8] |= (((sample >> i) & 1) as u8) << (at % 8);
    }
    packet
}

fn assert_zero_block(bits: u32) {
    let decoded = production::decode_panasonic_v6(&[0; 16], width(bits), 1, bits, false);
    assert_eq!(decoded, vec![0; width(bits)], "all-zero {bits}-bit packet must not wrap below black");
}

fn assert_first_sample_boundaries(bits: u32) {
    // Expected values from LibRaw0.22.2 unsigned spix=epixel-15 semantics.
    // These constants deliberately do not repeat production's arithmetic.
    for (encoded, expected) in [(0, 0), (14, 0), (15, 0), (16, 1)] {
        let packet = first_sample_packet(bits, encoded);
        let decoded = production::decode_panasonic_v6(&packet, width(bits), 1, bits, false);
        assert_eq!(decoded.len(), width(bits));
        assert_eq!(decoded[0], expected, "{bits}-bit first epixel={encoded}");
    }
}

#[test] fn zero_block_12bit_clamps_underflow() { assert_zero_block(12); }
#[test] fn zero_block_14bit_clamps_underflow() { assert_zero_block(14); }
#[test] fn first_epixel_12bit_boundaries() { assert_first_sample_boundaries(12); }
#[test] fn first_epixel_14bit_boundaries() { assert_first_sample_boundaries(14); }
