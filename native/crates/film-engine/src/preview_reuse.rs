//! Private CPU comparison candidate. Scientific output, before brightness, never encoded pixels.
use super::{Engine,EngineError,Result,check_cancellation};
use spektrafilm_math::image::ImageBuf;
use std::{fs,io::Read,path::Path};
use sha2::{Digest,Sha256};

// Reserve 1 MiB of the admitted 32 MiB for keys, metadata and allocator overhead.
pub const CAPACITY:usize=31*1024*1024;
#[derive(Default)]
pub struct Cache { identity:String, entries:Vec<Entry> }
struct Entry { width:u32,height:u32,input:Vec<u32>,output:ImageBuf,metered:Option<f32> }
impl Entry { fn bytes(&self)->usize {self.input.capacity()*4+self.output.data.capacity()*std::mem::size_of::<spektrafilm_math::precision::Scalar>()} }
impl Cache {
    pub fn bytes(&self)->usize {self.entries.iter().map(Entry::bytes).sum()}
    pub fn clear(&mut self){self.entries.clear();self.identity.clear();}
    pub fn find(&mut self,identity:&str,input:&[f32],width:u32,height:u32)->Option<(ImageBuf,Option<f32>)>{
        if self.identity!=identity {self.clear();return None;}
        if self.entries.iter().any(|entry|entry.width==width && entry.height==height && (entry.input.len()!=input.len() || !entry.input.iter().zip(input).all(|(a,b)|*a==b.to_bits()))) {self.clear();return None;}
        let index=self.entries.iter().position(|entry|entry.width==width && entry.height==height && entry.input.len()==input.len() && entry.input.iter().zip(input).all(|(a,b)|*a==b.to_bits()))?;
        let entry=self.entries.remove(index);
        let result=(entry.output.clone(),entry.metered);
        self.entries.push(entry);Some(result)
    }
    pub fn insert(&mut self,identity:String,input:&[f32],output:ImageBuf,metered:Option<f32>){
        if identity.len()>65536 {self.clear();return;}
        if self.identity!=identity {self.clear();self.identity=identity;}
        let entry=Entry{width:output.width,height:output.height,input:input.iter().map(|x|x.to_bits()).collect(),output,metered};
        let bytes=entry.bytes();if bytes>CAPACITY {return;}
        self.entries.retain(|e|e.width!=entry.width || e.height!=entry.height);
        while self.bytes()+bytes>CAPACITY || self.entries.len()>=2 {self.entries.remove(0);}
        self.entries.push(entry);
    }
}
/// Full file bytes + relative names; deterministic traversal, no mtimes or unchecked hash hints.
/// Bundled dependency files are app-owned and immutable during an operation. The caller also
/// verifies the digest after cold computation before admitting a result into the cache.
pub fn dependencies(engine:&Engine,generation:u64)->Result<String>{
    fn visit(root:&Path,path:&Path,hash:&mut Sha256,engine:&Engine,generation:u64)->Result<()> {
        let io=|_|EngineError::new("MISSING_DATA","Cannot fingerprint bundled dependencies");
        let mut children=fs::read_dir(path).map_err(io)?.collect::<std::io::Result<Vec<_>>>().map_err(io)?;
        children.sort_by_key(|e|e.file_name());
        for child in children {
            check_cancellation(engine,generation)?;
            let kind=child.file_type().map_err(io)?;
            if kind.is_symlink(){return Err(EngineError::new("INVALID_PATH","Dependency symlinks cannot be cached"));}
            let path=child.path();
            if kind.is_dir(){visit(root,&path,hash,engine,generation)?;}else if kind.is_file(){
                let name=path.strip_prefix(root).map_err(|_|EngineError::new("INVALID_PATH","Dependency outside root"))?.to_string_lossy();
                hash.update((name.len()as u64).to_le_bytes());hash.update(name.as_bytes());
                let mut file=fs::File::open(&path).map_err(io)?;hash.update(file.metadata().map_err(io)?.len().to_le_bytes());
                let mut buffer=[0u8;65536];loop {check_cancellation(engine,generation)?;let n=file.read(&mut buffer).map_err(io)?;if n==0{break;}hash.update(&buffer[..n]);}
            }
        }Ok(())
    }
    let mut hash=Sha256::new();visit(&engine.data_dir,&engine.data_dir,&mut hash,engine,generation)?;
    Ok(format!("{:x}",hash.finalize()))
}
#[cfg(test)]mod tests {
    use super::*;
    fn output()->ImageBuf{ImageBuf::new(1,1)}
    #[test]fn exact_bits_identity_and_bounds(){
        let mut c=Cache::default();c.insert("science".into(),&[0.,1.,2.],output(),None);
        assert!(c.find("science",&[0.,1.,2.],1,1).is_some());
        assert!(c.find("science",&[-0.,1.,2.],1,1).is_none());assert_eq!(c.bytes(),0);
        c.insert("science".into(),&[0.,1.,2.],output(),None);
        assert!(c.find("science",&[0.,1.,3.],1,1).is_none());
        assert!(c.find("science",&[0.,1.,2.],3,1).is_none());
        assert!(c.find("different seed/profile/dependency",&[0.,1.,2.],1,1).is_none());assert_eq!(c.bytes(),0);
        for edge in [512,768,512,768] {let input=vec![0.;edge*edge*3];c.insert("same".into(),&input,ImageBuf::new(edge as u32,edge as u32),None);assert!(c.bytes()<=CAPACITY);}
        assert_eq!(c.entries.len(),2);c.clear();assert_eq!(c.bytes(),0);
    }
}
