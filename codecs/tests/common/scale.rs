pub fn replace(mut b:Vec<u8>,tag:u16,typ:u16,count:u32,data:Vec<u8>)->Vec<u8>{
 let off=u32::from_le_bytes(b[4..8].try_into().unwrap())as usize;
 let n=u16::from_le_bytes(b[off..off+2].try_into().unwrap())as usize;
 let mut entries:Vec<Vec<u8>>=(0..n).map(|i|b[off+2+i*12..off+14+i*12].to_vec()).filter(|e|u16::from_le_bytes(e[..2].try_into().unwrap())!=tag).collect();
 let mut e=Vec::new();e.extend(tag.to_le_bytes());e.extend(typ.to_le_bytes());e.extend(count.to_le_bytes());
 if data.len()<=4 {let mut v=[0;4];v[..data.len()].copy_from_slice(&data);e.extend(v);}else {e.extend((b.len()as u32).to_le_bytes());b.extend(data);}
 entries.push(e);entries.sort_by_key(|e|u16::from_le_bytes(e[..2].try_into().unwrap()));
 let off=b.len()as u32;b[4..8].copy_from_slice(&off.to_le_bytes());b.extend((entries.len()as u16).to_le_bytes());for e in entries {b.extend(e);}b.extend(0u32.to_le_bytes());b
}
pub fn rational(v:&[(u32,u32)])->Vec<u8>{v.iter().flat_map(|(a,b)|a.to_le_bytes().into_iter().chain(b.to_le_bytes())).collect()}
pub fn rename(mut b:Vec<u8>,old:u16,new:u16)->Vec<u8>{
 let off=u32::from_le_bytes(b[4..8].try_into().unwrap())as usize;let n=u16::from_le_bytes(b[off..off+2].try_into().unwrap())as usize;
 for i in 0..n {let p=off+2+i*12;if u16::from_le_bytes(b[p..p+2].try_into().unwrap())==old {b[p..p+2].copy_from_slice(&new.to_le_bytes());}}b
}
