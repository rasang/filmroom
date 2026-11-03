// Compile the actual production v4 source with only dependency stubs for pump tests.
mod pumps { pub trait BitPump {fn peek_bits(&mut self,n:u32)->u32;fn consume_bits(&mut self,n:u32);fn get_bits(&mut self,n:u32)->u32 {let v=self.peek_bits(n);self.consume_bits(n);v}} }
mod decoders {
 pub type PixU16=Vec<u16>;
 pub fn decode_threaded_multiline<F>(w:usize,h:usize,lines:usize,dummy:bool,f:&F)->Result<PixU16,String> where F:Fn(&mut[u16],usize)->Result<(),String>+Sync {let mut v=if dummy {vec![]}else{vec![0;w*h]};for(i,c)in v.chunks_mut(w*lines).enumerate(){f(c,i*lines)?;}Ok(v)}
}
#[allow(dead_code)] mod production {include!("../vendor/rawler-0.7.2/src/decoders/rw2/v4decompressor.rs");}
use pumps::BitPump;
fn compare(logical:usize,split:bool){
 let mut state=0x12345678u32;let bytes:Vec<_>=(0..0x8000).map(|_|{state^=state<<13;state^=state>>17;state^=state<<5;state as u8}).collect();
 let physical=|i|if split {(i+0x4000-0x2008)%0x4000}else{i};
 let expected=(u16::from_le_bytes([bytes[physical(logical)],if logical==0x3fff{0}else{bytes[physical(logical+1)]}])as u32>>4)&0xfff;
 let before=((logical^0x3ff0)*8+4+12)as u32;let skip=(0x4000*8-before)/8;
 let mut pump=production::BitPumpPanasonic::new(&bytes,split);for _ in 0..skip{pump.get_bits(8);}
 assert_eq!(pump.get_bits(12),expected,"logical {logical:#x} split {split}");
}
#[test]fn rotation_seam_high_byte_wraps_in_same_block(){compare(0x2007,true);}
#[test]fn logical_block_end_high_byte_is_zero_not_next_physical_byte(){compare(0x3fff,true);compare(0x3fff,false);}
#[test]fn ordinary_words_keep_the_same_order(){for i in [0,1,0x1ff8,0x2008,0x3000,0x3ffe]{compare(i,true);compare(i,false);}}
