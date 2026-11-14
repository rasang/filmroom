//! Modern RAF only. Validate both directories before camera lookup; never decode the JPEG.
//! Uncompressed sensor rows are read on demand. Compressed sensor integers are bounded;
//! RGB development is striped. SuperCCD/EXR, unknown packing and >52 MP fail closed.
use super::*;
use std::{collections::BTreeMap, os::unix::fs::FileExt, os::fd::AsRawFd};
pub(crate) mod xtrans;
const SIGNATURE: &[u8] = b"FUJIFILMCCD-RAW ";
const FUJI_SCRATCH: u64 = 8 * MIB;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Encoding { Packed12, Words, Msb32, Lossless, Lossy }
#[derive(Clone, Debug, PartialEq, Eq)]
struct Plan { width:usize, height:usize, bits:usize, offset:u64, bytes:u64, little:bool, encoding:Encoding, xtrans:bool, orientation:u8, crop:[usize;4] }
struct Entry<'a> { typ:u16, count:usize, data:&'a [u8] }
fn invalid() -> crate::error::CodecError { err("INVALID_INPUT","probe") }
fn span(b:&[u8],at:usize,n:usize)->Result<&[u8]> {b.get(at..at.checked_add(n).ok_or_else(invalid)?).ok_or_else(invalid)}
fn be32(b:&[u8])->usize {u32::from_be_bytes(b[..4].try_into().unwrap()) as usize}
fn be16(b:&[u8])->usize {u16::from_be_bytes(b[..2].try_into().unwrap()) as usize}
fn endian(b:&[u8])->Result<crate::tiff::Endian> { match b.get(..4) {Some(b"II\x2a\0")=>Ok(crate::tiff::Endian::Le),Some(b"MM\0\x2a")=>Ok(crate::tiff::Endian::Be),_=>Err(unsupported())} }
fn directory<'a>(b:&'a [u8],at:usize,e:crate::tiff::Endian,allow_next:bool)->Result<BTreeMap<u16,Entry<'a>>> {
    let n=e.u16(span(b,at,2)?) as usize;
    if n==0 || n>256 {return Err(invalid());}
    let table=span(b,at+2,n*12+4)?;
    let next=e.u32(&table[n*12..]) as usize;
    if next!=0 {if !allow_next{return Err(unsupported());}span(b,next,2)?;}
    let mut out=BTreeMap::new();
    for ent in table[..n*12].chunks_exact(12) {
        let tag=e.u16(ent);let typ=e.u16(&ent[2..]);let count=e.u32(&ent[4..]) as usize;
        let size=match typ {1|2|6|7=>1,3|8=>2,4|9|11|13=>4,5|10|12=>8,_=>return Err(invalid())};
        let bytes=count.checked_mul(size).filter(|n|*n<=64*1024).ok_or_else(invalid)?;
        let data=if bytes<=4 {&ent[8..8+bytes]}else {span(b,e.u32(&ent[8..]) as usize,bytes)?};
        if out.insert(tag,Entry{typ,count,data}).is_some(){return Err(invalid());}
    }
    Ok(out)
}
fn scalar(d:&BTreeMap<u16,Entry<'_>>,tag:u16,e:crate::tiff::Endian)->Result<usize> {
    let v=d.get(&tag).ok_or_else(unsupported)?;
    if v.count!=1 || !matches!(v.typ,3|4|13){return Err(invalid());}
    Ok(if v.typ==3 {e.u16(v.data) as usize}else{e.u32(v.data) as usize})
}
fn validate_compressed(b:&[u8],w:usize,h:usize,bits:usize,xtrans:bool)->Result<Encoding>{
    let p=span(b,0,16)?;
    let blocks=p[13] as usize;let lines=be16(&p[14..]);let rounded=be16(&p[7..]);
    if be16(p)!=0x4953 || p[2]>1 || p[3]!=(if xtrans{16}else{0}) || p[4] as usize!=bits
        || be16(&p[5..])!=h || be16(&p[9..])!=w || be16(&p[11..])!=768
        || w<768 || w>12288 || w%24!=0 || h<6 || h>12288 || h%6!=0
        || !(1..=16).contains(&blocks) || rounded!=blocks*768 || blocks!=w.div_ceil(768)
        || lines!=h/6 || lines>2048 {return Err(unsupported());}
    let sizes=span(b,16,blocks*4)?;
    let mut pos=16+(blocks*4).div_ceil(16)*16;
    if p[2]==0 {pos=pos.checked_add(blocks*lines.div_ceil(16)*16).ok_or_else(invalid)?;}
    for size in sizes.chunks_exact(4) {
        let n=be32(size);if n==0 {return Err(invalid());}
        span(b,pos,n)?;pos=pos.checked_add(n).ok_or_else(invalid)?;
    }
    if b.len()!=pos {return Err(invalid());}
    Ok(if p[2]==1{Encoding::Lossless}else{Encoding::Lossy})
}
fn plan(source:&RawSource)->Result<Plan>{
    let b=source.buf();let h=span(b,0,108)?;
    if !h.starts_with(SIGNATURE){return Err(unsupported());}
    let jpeg=be32(&h[84..]);let jpeg_n=be32(&h[88..]);
    let tags=be32(&h[92..]);let tags_n=be32(&h[96..]);
    let raw=be32(&h[100..]);let raw_n=be32(&h[104..]);
    if jpeg<108 || jpeg_n<20 || tags<jpeg+jpeg_n || tags_n<4 || raw<tags+tags_n || raw_n<8 {return Err(invalid());}
    let j=span(b,jpeg,jpeg_n)?;let tag_data=span(b,tags,tags_n)?;let t=span(b,raw,raw_n)?;
    // Require the EXIF TIFF at the position expected by pinned Rawler, not arbitrary JPEG bytes.
    if j.get(..4)!=Some(b"\xff\xd8\xff\xe1") || j.get(6..12)!=Some(b"Exif\0\0") {return Err(unsupported());}
    let app1_len=be16(span(j,4,2)?);if app1_len<8{return Err(invalid());}
    let exif=span(j,12,app1_len-8)?;let je=endian(exif)?;
    let root=directory(exif,je.u32(span(exif,4,4)?) as usize,je,true)?;
    let make=root.get(&271).ok_or_else(unsupported)?;
    if make.typ!=2 || make.data.strip_suffix(&[0]).unwrap_or(make.data).trim_ascii_end()!=b"FUJIFILM" {return Err(unsupported());}
    let orientation=if root.contains_key(&274){scalar(&root,274,je)?}else{1};
    if !(1..=8).contains(&orientation) {return Err(invalid());}
    // Legacy direct sensor blocks, SuperCCD and EXR layouts have no modern FujiIFD.
    let e=endian(t)?;
    let root=directory(t,e.u32(span(t,4,4)?) as usize,e,false)?;
    let sensor_at=scalar(&root,0xf000,e)?;
    let sensor=directory(t,sensor_at,e,true)?;
    let width=scalar(&sensor,0xf001,e)?;let height=scalar(&sensor,0xf002,e)?;
    crate::validate_dimensions(width.try_into().map_err(|_|invalid())?,height.try_into().map_err(|_|invalid())?)?;
    // Upper-size rejection precedes the unsupported multi-directory sensor contract.
    let _=directory(t,sensor_at,e,false)?;
    if width<16 || height<16 || width%2!=0 || height%2!=0 {return Err(unsupported());}
    let bits=scalar(&sensor,0xf003,e)?;
    if !matches!(bits,12|14|16){return Err(unsupported());}
    let off=scalar(&sensor,0xf007,e)?;let bytes=scalar(&sensor,0xf008,e)?;
    if off<8 || bytes==0 {return Err(invalid());}let encoded=span(t,off,bytes)?;
    let n=be32(span(tag_data,0,4)?);if n>4000 || tags_n>256*1024 {return Err(invalid());}
    let mut pos=4;let mut pattern=None;let mut crop_origin=None;let mut crop_size=None;let mut seen=std::collections::BTreeSet::new();
    for _ in 0..n {
        let v=span(tag_data,pos,4)?;let tag=be16(v);let len=be16(&v[2..]);pos+=4;
        let data=span(tag_data,pos,len)?;pos+=len;
        if !seen.insert(tag){return Err(invalid());}
        match tag {
            0x100=>{if len!=4 || be16(data)!=height || be16(&data[2..])!=width{return Err(invalid());}},
            0x110=>{if len!=4{return Err(invalid());}crop_origin=Some((be16(&data[2..]),be16(data)));},
            0x111=>{if len!=4{return Err(invalid());}crop_size=Some((be16(&data[2..]),be16(data)));},
            0x131=>{
                if len!=36 || data.iter().any(|v|*v>2)
                    || data.iter().filter(|v|**v==0).count()!=8 || data.iter().filter(|v|**v==1).count()!=20 || data.iter().filter(|v|**v==2).count()!=8 {return Err(unsupported());}
                pattern=Some(data);
            },
            _=>{}
        }
    }
    if pos>tag_data.len(){return Err(invalid());}
    let (cx,cy)=crop_origin.ok_or_else(unsupported)?;let(cw,ch)=crop_size.ok_or_else(unsupported)?;
    if cw<8 || ch<8 || cx.checked_add(cw).is_none_or(|v|v>width) || cy.checked_add(ch).is_none_or(|v|v>height) {return Err(invalid());}
    let crop=[cx,cy,cw,ch];
    let xtrans=pattern.is_some();
    let black=sensor.get(&0xf00a).ok_or_else(unsupported)?;
    if black.typ!=4 || black.count!=(if xtrans{36}else{4}) || black.data.chunks_exact(4).any(|v|e.u32(v)>=1u32<<bits){return Err(err("INVALID_COLOR_METADATA","probe"));}
    let wb=sensor.get(&0xf00e).ok_or_else(unsupported)?;
    if wb.typ!=4 || wb.count!=3 || wb.data.chunks_exact(4).any(|v|e.u32(v)==0 || e.u32(v)>65535){return Err(err("INVALID_COLOR_METADATA","probe"));}
    let packed=width*height*bits/8;
    let encoding=if bytes<packed {validate_compressed(encoded,width,height,bits,xtrans)?}
        else if bytes==width*height*2 && bits>=14 {Encoding::Words}
        else if bytes==packed && bits==12 {Encoding::Packed12}
        else if bytes==packed && bits==14 && scalar(&sensor,0xf006,e)?==1 && width%16==0 {Encoding::Msb32}
        else {return Err(unsupported());};
    Ok(Plan{width,height,bits,offset:(raw+off) as u64,bytes:bytes as u64,little:matches!(je,crate::tiff::Endian::Le),encoding,xtrans,orientation:orientation as u8,crop})
}
pub(crate) struct FujiRaw {
    camera:CameraRaw, plan:Plan, black:Vec<f32>, black_width:usize, white:f32,
    file:File, source:RawSource, identity:crate::nikon_he::Identity,
}
impl FujiRaw {
    pub(crate) fn read(input:&Path)->Result<Option<Self>>{
        let mut file=File::open(input).map_err(|e|io_error(e,"probe"))?;
        let mut h=[0u8;8];file.read_exact(&mut h).map_err(|e|io_error(e,"probe"))?;
        if h!=*b"FUJIFILM" {return Ok(None);}
        let identity=crate::nikon_he::Identity::read(&file)?;
        if !(108..=512*MIB).contains(&identity.bytes){return Err(invalid());}
        let source=RawSource::new_from_file(&file,input).map_err(|e|io_error(e,"probe"))?;
        let plan=plan(&source)?;
        let mut raw=bounded("probe",||rawler::decode_dummy(&source))?;
        if raw.camera.clean_model=="DBP for GX680" || ["double_width","fuji_rotation","fuji_rotation_alt"].iter().any(|h|raw.camera.find_hint(h))
            || raw.width!=plan.width || raw.height!=plan.height || raw.cpp!=1
            || !matches!(&raw.data,RawImageData::Integer(v) if v.is_empty()) {return Err(unsupported());}
        if raw.camera.find_hint("jpeg32") != (plan.encoding==Encoding::Msb32) {return Err(unsupported());}
        let crop=raw.crop_area.map(rect).ok_or_else(unsupported)?;
        if [crop.x,crop.y,crop.w,crop.h]!=plan.crop {return Err(invalid());}
        // The camera DB has fixed uncompressed margins (e.g. X-T2 right 128),
        // but compressed RAF changes sensor width. The on-file crop is authoritative.
        // Develop against the complete encoded sensor, with global halo, then crop once.
        raw.active_area=None;
        let development=descriptor_cfa(&raw,plan.orientation,true)?;
        if (development.cfa.len()==36)!=plan.xtrans {return Err(unsupported());}
        if raw.blacklevel.cpp!=1 || raw.blacklevel.width!=(if plan.xtrans{6}else{2}) || raw.blacklevel.height!=raw.blacklevel.width
            || raw.blacklevel.levels.len()!=raw.blacklevel.width*raw.blacklevel.height || raw.whitelevel.0.len()!=1 {return Err(err("INVALID_COLOR_METADATA","probe"));}
        let black=raw.blacklevel.as_vec();let white=raw.whitelevel.0[0] as f32;
        if black.iter().any(|v|!v.is_finite() || *v<0. || *v>=white) || white>(1u32<<plan.bits) as f32-1. {return Err(err("INVALID_COLOR_METADATA","probe"));}
        let black_width=raw.blacklevel.width;
        let camera=CameraRaw{he:None,development,format:"RAF",input_bytes:identity.bytes,jpeg_blocks:vec![],decoder_extra_bytes:0,source_ifd_offset:0,arw1_offset:None,arw6:None};
        if identity!=crate::nikon_he::Identity::read(&file)?{return Err(invalid());}
        Ok(Some(Self{camera,plan,black,black_width,white,file,source,identity}))
    }
    pub(crate) fn probe(&self)->Probe {
        let mut p=self.camera.probe();
        p.variant=match (self.plan.xtrans,self.plan.encoding) {
            (true,Encoding::Lossless)=>"RAF_XTRANS_LOSSLESS",(true,Encoding::Lossy)=>"RAF_XTRANS_LOSSY",(true,_)=>"RAF_XTRANS_UNCOMPRESSED",
            (false,Encoding::Lossless)=>"RAF_BAYER_LOSSLESS",(false,Encoding::Lossy)=>"RAF_BAYER_LOSSY",(false,_)=>"RAF_BAYER_UNCOMPRESSED",
        };
        if self.plan.xtrans {
            p.development_policy=if self.camera.development.lens_extras.has_ready() {
                "raf-xtrans-linear-srgb-embedded-v2"
            } else { "raf-xtrans-linear-srgb-v1" };
        }
        p
    }
    pub(crate) fn admit(&self,b:&Budget)->Result<Admission>{
        let memory=b.memory_budget_mi_b.checked_mul(MIB).filter(|v|*v>=BASE && *v<=CAMERA_RAW_MAX_MEMORY_MIB*MIB).ok_or_else(||err("MEMORY_BUDGET","admission"))?;
        let d=&self.camera.development;
        let compressed=matches!(self.plan.encoding,Encoding::Lossless|Encoding::Lossy);
        // Includes full mapped input, possible EOF-padding copy, sensor integers and
        // two bounded entropy workers. No full-size RGB, float mosaic or preview allocation.
        let decode_peak=BASE+self.identity.bytes+if compressed {self.plan.bytes+16+(d.width*d.height*2) as u64+FUJI_SCRATCH}else{0};
        let retained=BASE+if compressed {(d.width*d.height*2) as u64}else{0};
        let capacity=(memory.saturating_sub(retained)/(d.active.w as u64*128)).saturating_sub((2*HALO) as u64) as usize;
        let rows=b.raw_batch.camera_rows(capacity.min(128),capacity,256);
        if decode_peak>memory || rows<8{return Err(err("MEMORY_BUDGET","admission"));}
        let disk=self.probe().output_bytes;
        if disk>b.disk_budget_bytes.min(1024*MIB){return Err(err("DISK_BUDGET","admission"));}
        crate::develop::admit_geometry(d,b,Admission{memory_budget_bytes:memory,estimated_peak_bytes:decode_peak.max(retained+d.active.w as u64*(rows+2*HALO) as u64*128),required_disk_bytes:disk,strip_rows:rows as u32})
    }
    pub(crate) fn decode(self,output:&Path,b:&Budget,cancel:&AtomicBool)->Result<(Probe,Admission)>{
        if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","decode"));}
        let admission=self.admit(b)?;let probe=self.probe();
        if self.identity!=crate::nikon_he::Identity::read(&self.file)? || plan(&self.source)?!=self.plan {return Err(invalid());}
        let pool=rayon::ThreadPoolBuilder::new().num_threads(2).stack_size(1024*1024).build().map_err(|_|err("MEMORY_BUDGET","decode"))?;
        let integers=if matches!(self.plan.encoding,Encoding::Lossless|Encoding::Lossy) {
            let decoded=pool.install(||bounded("decode",||rawler::get_decoder(&self.source)?.raw_image_with_cancel(&self.source,&Default::default(),false,&||cancel.load(Ordering::Relaxed))));
            if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","decode"));}
            let mut raw=decoded?;
            raw.active_area=None;
            let revalidated=descriptor_cfa(&raw,self.plan.orientation,true)?;
            if raw.width!=self.plan.width || raw.height!=self.plan.height || raw.blacklevel.as_vec()!=self.black || raw.whitelevel.0.as_slice()!=[self.white as u32]
                || revalidated.lens!=self.camera.development.lens
                || revalidated.lens_extras!=self.camera.development.lens_extras {return Err(invalid());}
            match raw.data {RawImageData::Integer(v) if v.len()==self.plan.width*self.plan.height=>Some(v),_=>return Err(invalid())}
        }else{None};
        // Metadata map and any padded compressed buffer are gone before strip development.
        drop(self.source);
        if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","decode"));}
        let d=&self.camera.development;
        let out=OpenOptions::new().write(true).create_new(true).custom_flags(libc::O_NOFOLLOW).open(output).map_err(|e|io_error(e,"spool"))?;
        let mut guard=OutputGuard(output,false);
        let rc=unsafe{libc::posix_fallocate(out.as_raw_fd(),0,probe.output_bytes as libc::off_t)};
        if rc!=0{return Err(io_error(std::io::Error::from_raw_os_error(rc),"spool"));}
        let row_bytes=match self.plan.encoding{Encoding::Packed12|Encoding::Msb32=>d.width*self.plan.bits/8,_=>d.width*2};
        let mut encoded=vec![0u8;row_bytes];
        let mut row=vec![0u16;d.width];
        let sink=crate::develop::CameraRgbSink::new(d,output)?;
        let region=sink.region(d);
        let mut first=region.y;
        while first<region.y+region.h {
            let last=(first+admission.strip_rows as usize).min(region.y+region.h);
            let y=first.saturating_sub(HALO).max(d.active.y);let end=(last+HALO).min(d.active.y+d.active.h);
            let mut normalized=Vec::with_capacity(d.active.w*(end-y));
            for sy in y..end {
                if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","develop"));}
                let values=if let Some(ref ints)=integers {&ints[sy*d.width..(sy+1)*d.width]}else{
                    self.file.read_exact_at(&mut encoded,self.plan.offset+(sy*row_bytes) as u64).map_err(|e|io_error(e,"decode"))?;
                    unpack_row(&self.plan,&encoded,&mut row)?;&row
                };
                for (x,v) in values[d.active.x..d.active.x+d.active.w].iter().enumerate(){
                    if *v as usize>=(1usize<<self.plan.bits){return Err(err("INVALID_INPUT","decode"));}
                    let black=self.black[(sy%self.black_width)*self.black_width+(x+d.active.x)%self.black_width];
                    normalized.push((*v as f32-black).max(0.)/(self.white-black));
                }
            }
            let pixels=pool.install(||sink.develop(d,y,end-y,normalized,cancel))?;
            sink.emit_strip(d,&out,&pixels,y,first,last,cancel)?;first=last;
        }
        drop(integers);drop(encoded);drop(row);
        pool.install(||sink.finish(d,&out,cancel))?;
        if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","spool"));}
        if self.identity!=crate::nikon_he::Identity::read(&self.file)?{return Err(invalid());}
        out.sync_all().map_err(|e|io_error(e,"spool"))?;guard.1=true;Ok((probe,admission))
    }
}
fn unpack_row(p:&Plan,encoded:&[u8],row:&mut [u16])->Result<()> {
    match p.encoding {
        Encoding::Packed12=>for (out,b) in row.chunks_exact_mut(2).zip(encoded.chunks_exact(3)){out[0]=b[0] as u16|((b[1] as u16&15)<<8);out[1]=(b[1] as u16>>4)|((b[2] as u16)<<4);},
        Encoding::Words=>for(out,b)in row.iter_mut().zip(encoded.chunks_exact(2)){*out=if p.bits==14 || p.little{u16::from_le_bytes([b[0],b[1]])}else{u16::from_be_bytes([b[0],b[1]])};},
        Encoding::Msb32=>{
            // Fujifilm's JPEG32 packing is a big-endian bit stream with every u32 byte-reversed.
            for (x,out) in row.iter_mut().enumerate(){let bit=x*p.bits;let mut v=0u16;
                for i in 0..p.bits {let at=bit+i;let byte=(at/32)*4+3-(at/8)%4;v=(v<<1)|((encoded[byte]>>(7-at%8))&1) as u16;}*out=v;
            }
        },
        _=>return Err(unsupported()),
    }Ok(())
}

#[cfg(test)] mod tests {
    use super::*;
    fn plain(encoding:Encoding,bits:usize)->Plan{Plan{width:16,height:16,bits,offset:0,bytes:0,little:true,encoding,xtrans:false,orientation:1,crop:[0,0,16,16]}}
    #[test] fn unpacked_sensor_rows_are_exact(){
        let values:Vec<u16>=(0..16).map(|i|(i*997)&4095).collect();let mut bytes=Vec::new();
        for p in values.chunks_exact(2){bytes.extend([p[0] as u8,((p[0]>>8)|((p[1]&15)<<4)) as u8,(p[1]>>4) as u8]);}
        let mut actual=vec![0;16];unpack_row(&plain(Encoding::Packed12,12),&bytes,&mut actual).unwrap();assert_eq!(actual,values);
        let values:Vec<u16>=(0..16).map(|i|i*997).collect();let bytes:Vec<_>=values.iter().flat_map(|v|v.to_le_bytes()).collect();
        unpack_row(&plain(Encoding::Words,14),&bytes,&mut actual).unwrap();assert_eq!(actual,values);
        let mut packed=vec![0u8;28];for (x,v) in values.iter().enumerate(){for k in 0..14{let bit=x*14+k;let byte=(bit/32)*4+3-(bit/8)%4;packed[byte]|=(((v>>(13-k))&1) as u8)<<(7-bit%8);}}
        unpack_row(&plain(Encoding::Msb32,14),&packed,&mut actual).unwrap();assert_eq!(actual,values);
    }
    #[test] fn compressed_header_and_payload_bounds_are_checked(){
        let mut data=vec![0u8;64];data[..16].copy_from_slice(&[0x49,0x53,1,16,14,0,24,3,0,3,0,3,0,1,0,4]);data[16..20].copy_from_slice(&32u32.to_be_bytes());
        assert_eq!(validate_compressed(&data,768,24,14,true).unwrap(),Encoding::Lossless);
        assert!(validate_compressed(&data[..63],768,24,14,true).is_err());
        for (at,value) in [(2,2),(3,0),(4,16),(13,0)]{let mut bad=data.clone();bad[at]=value;assert!(validate_compressed(&bad,768,24,14,true).is_err());}
        data[2]=0;assert!(validate_compressed(&data,768,24,14,true).is_err()); // Missing lossy q-base table.
    }
    #[test] fn directory_never_accepts_duplicate_or_outside_values(){
        let e=crate::tiff::Endian::Le;let mut b=vec![0u8;30];b[..2].copy_from_slice(&2u16.to_le_bytes());
        for at in [2,14]{b[at..at+2].copy_from_slice(&256u16.to_le_bytes());b[at+2..at+4].copy_from_slice(&4u16.to_le_bytes());b[at+4..at+8].copy_from_slice(&1u32.to_le_bytes());}
        assert!(directory(&b,0,e,false).is_err());b[14..16].copy_from_slice(&257u16.to_le_bytes());assert!(directory(&b,0,e,false).is_ok());
        b[18..22].copy_from_slice(&2u32.to_le_bytes());b[22..26].copy_from_slice(&99u32.to_le_bytes());assert!(directory(&b,0,e,false).is_err());
    }
    #[test] fn public_sample_metadata_is_opt_in(){
        let Ok(dir)=std::env::var("FILMROOM_RAF_FIXTURES") else{return;};
        for (id,variant) in [(1214,"RAF_BAYER_UNCOMPRESSED"),(1371,"RAF_BAYER_UNCOMPRESSED"),(1191,"RAF_XTRANS_UNCOMPRESSED"),(864,"RAF_XTRANS_UNCOMPRESSED"),(865,"RAF_XTRANS_LOSSLESS"),(1178,"RAF_XTRANS_LOSSLESS"),(6124,"RAF_XTRANS_UNCOMPRESSED"),(7301,"RAF_XTRANS_LOSSLESS"),(7302,"RAF_XTRANS_LOSSY")]{
            let path=Path::new(&dir).join(format!("{id}.RAF"));let raw=FujiRaw::read(&path).unwrap_or_else(|e|panic!("{id}: {e:?}")).unwrap();
            assert_eq!(raw.probe().variant,variant,"{id}");let budget=Budget{memory_budget_mi_b:256,..Default::default()};let admission=raw.admit(&budget).unwrap();assert!(admission.estimated_peak_bytes<=256*MIB);
        }
        for (id,code) in [(1435,"PIXEL_LIMIT"),(7175,"PIXEL_LIMIT"),(1964,"UNSUPPORTED_CAMERA_RAW_VARIANT")]{
            let path=Path::new(&dir).join(format!("{id}.RAF"));assert_eq!(FujiRaw::read(&path).err().unwrap().code,code,"{id}");
        }
    }
    #[test] fn public_sensor_matches_independent_libraw() {
        let (Ok(dir),Ok(id),Ok(oracle))=(std::env::var("FILMROOM_RAF_FIXTURES"),std::env::var("FILMROOM_RAF_ORACLE_ID"),std::env::var("FILMROOM_RAF_ORACLE")) else{return;};
        let path=Path::new(&dir).join(format!("{id}.RAF"));let raf=FujiRaw::read(&path).unwrap().unwrap();
        let d=&raf.camera.development;let mut reference=File::open(&oracle).unwrap();
        assert_eq!(reference.metadata().unwrap().len(),(d.width*d.height*2) as u64);
        let actual=if matches!(raf.plan.encoding,Encoding::Lossless|Encoding::Lossy){
            let raw=bounded("decode",||rawler::decode(&raf.source,&Default::default())).unwrap();
            match raw.data{RawImageData::Integer(v)=>Some(v),_=>panic!("integer sensor required")}
        }else{None};
        let row_bytes=match raf.plan.encoding{Encoding::Packed12|Encoding::Msb32=>d.width*raf.plan.bits/8,_=>d.width*2};
        let mut encoded=vec![0u8;row_bytes];let mut row=vec![0u16;d.width];let mut bytes=vec![0u8;d.width*2];
        for y in 0..d.height{
            reference.read_exact(&mut bytes).unwrap();
            let values=if let Some(ref v)=actual{&v[y*d.width..(y+1)*d.width]}else{
                raf.file.read_exact_at(&mut encoded,raf.plan.offset+(y*row_bytes) as u64).unwrap();unpack_row(&raf.plan,&encoded,&mut row).unwrap();&row
            };
            for(x,(v,b))in values.iter().zip(bytes.chunks_exact(2)).enumerate(){assert_eq!(*v,u16::from_le_bytes([b[0],b[1]]),"{id}, x={x}, y={y}");}
        }
    }

    #[test] fn public_failures_and_cancellation_remove_only_partial_output() {
        let Ok(dir)=std::env::var("FILMROOM_RAF_FIXTURES") else{return;};
        let input=Path::new(&dir).join("865.RAF");let original=std::fs::read(&input).unwrap();
        let raw_base=be32(&original[100..]);let sensor_offset=raw_base+2048;
        let temp=std::env::temp_dir().join(format!("raf-faults-{}",std::process::id()));std::fs::create_dir_all(&temp).unwrap();
        let bad=temp.join("renamed.jpg");let out=temp.join("output.f32");
        for case in 0..5 {
            let mut bytes=original.clone();
            match case {
                0=>{bytes.pop();},
                1=>bytes[100..104].copy_from_slice(&u32::MAX.to_be_bytes()),
                2=>bytes[sensor_offset+2]=2,
                3=>bytes[sensor_offset+16..sensor_offset+20].fill(0),
                _=>bytes[raw_base+40..raw_base+44].copy_from_slice(&50000u32.to_le_bytes()),
            }
            std::fs::write(&bad,bytes).unwrap();
            assert!(crate::raw::decode(&bad,&out,&Budget{memory_budget_mi_b:256,..Default::default()},&AtomicBool::new(false)).is_err(),"case {case}");assert!(!out.exists());
        }
        let budget=Budget{memory_budget_mi_b:256,..Default::default()};
        assert_eq!(crate::raw::decode(&input,&out,&budget,&AtomicBool::new(true)).unwrap_err().code,"CANCELLED");assert!(!out.exists());
        let cancelled=AtomicBool::new(false);
        std::thread::scope(|scope|{
            scope.spawn(||{std::thread::sleep(std::time::Duration::from_millis(20));cancelled.store(true,Ordering::Relaxed);});
            assert_eq!(crate::raw::decode(&input,&out,&budget,&cancelled).unwrap_err().code,"CANCELLED");
        });assert!(!out.exists());
        // Insufficient budgets must not create output, before a sensor allocation.
        let raf=FujiRaw::read(&input).unwrap().unwrap();
        assert_eq!(raf.admit(&Budget{memory_budget_mi_b:32,..Default::default()}).unwrap_err().code,"MEMORY_BUDGET");
        assert_eq!(raf.admit(&Budget{memory_budget_mi_b:256,disk_budget_bytes:1,..Default::default()}).unwrap_err().code,"DISK_BUDGET");
        std::fs::write(&out,b"existing output").unwrap();
        assert_eq!(crate::raw::decode(&input,&out,&budget,&AtomicBool::new(false)).unwrap_err().code,"OUTPUT_EXISTS");
        assert_eq!(std::fs::read(&out).unwrap(),b"existing output");
        assert_eq!(std::fs::read(&input).unwrap(),original);
        std::fs::remove_dir_all(temp).unwrap();
    }

}
