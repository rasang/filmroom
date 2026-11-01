#[path = "../src/disk_spatial.rs"]
mod disk_spatial;
use disk_spatial::*;
use std::{cell::Cell,fs,io,path::{Path,PathBuf},sync::atomic::{AtomicU64,Ordering}};
use spektrafilm_math::{gaussian::gaussian_blur_channel,precision::Scalar};
static NEXT:AtomicU64=AtomicU64::new(0);
struct TestDir(PathBuf);
impl TestDir { fn new(name:&str)->Self {let d=std::env::temp_dir().join(format!("film-v5-spatial-{name}-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir_all(d.join("scratch")).unwrap();Self(d)} }
impl Drop for TestDir {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
fn write(path:&Path,data:&[f64]) {let bytes:Vec<_>=data.iter().flat_map(|x|x.to_le_bytes()).collect();fs::write(path,bytes).unwrap();}
fn read(path:&Path)->Vec<f64> {fs::read(path).unwrap().chunks_exact(8).map(|x|f64::from_le_bytes(x.try_into().unwrap())).collect()}
fn fixture(w:u32,h:u32)->Vec<f64> {let mut data:Vec<_>=(0..w as usize*h as usize).map(|i|((i*7+3)%31) as f64/17.-0.3).collect();data[0]=16.;let n=data.len();data[n-1]=-3.;if n>3 {data[n/2]=0.25;}data}
#[test]
fn exact_global_fir_iir_parity_across_strip_shapes() {
    assert!(cfg!(feature="precision-f64"));
    for (w,h) in [(1,1),(1,19),(37,1),(3,11),(37,19),(257,3),(8192,3),(3,6144)] {
        let d=TestDir::new("parity");let input=d.0.join("input");let src=fixture(w,h);write(&input,&src);
        let budget=8192usize.max(w as usize*24).max(h as usize*16+8);
        for sigma in [0.,0.1,0.65,1.5,2.99,3.,12.,200.] {
            let output=d.0.join(format!("out-{sigma}"));let mut events=Vec::new();
            let result=gaussian_file(&input,&output,w,h,sigma,&d.0.join("scratch"),budget,&mut ||false,&mut |p|events.push(p)).unwrap();
            let expected=gaussian_blur_channel(&src.iter().map(|&x|x as Scalar).collect::<Vec<_>>(),w,h,sigma);
            let actual=read(&output);assert_eq!(actual.len(),src.len());
            for (i,(&a,&e)) in actual.iter().zip(&expected).enumerate() {assert_eq!(a.to_bits(),(e as f64).to_bits(),"{w}x{h} sigma{sigma} sample{i}: {a} vs{e}");}
            assert!(result.peak_buffer_bytes<=budget as u64);assert_eq!(result.input_bytes,(w*h*8) as u64);assert_eq!(result.output_bytes,result.input_bytes);
            assert_eq!(result.scratch_bytes,if sigma>0. {result.input_bytes}else{0});
            assert_eq!(result.bytes_read,if sigma>0.{result.input_bytes*2}else{result.input_bytes});assert_eq!(result.bytes_written,result.bytes_read);
            assert!(result.row_batch_rows>=1);if sigma>0.{assert!(result.column_strip_columns>=1);}
            let last=events.last().unwrap();assert_eq!(last.completed,last.total);assert!(fs::read_dir(d.0.join("scratch")).unwrap().next().is_none());
        }
    }
}
#[test]
fn cancelling_horizontal_or_vertical_removes_only_new_files() {
    for target in ["horizontal","vertical"] {
        let d=TestDir::new("cancel");let input=d.0.join("input");let output=d.0.join("output");let src=fixture(37,19);write(&input,&src);let stop=Cell::new(false);
        let r=gaussian_file(&input,&output,37,19,12.,&d.0.join("scratch"),1024,&mut ||stop.get(),&mut |p|{if p.pass==target&&p.completed>0 {stop.set(true);}});
        assert_eq!(r.unwrap_err().kind(),io::ErrorKind::Interrupted);assert!(!output.exists());assert_eq!(read(&input),src);assert!(fs::read_dir(d.0.join("scratch")).unwrap().next().is_none());
    }
}
#[test]
fn panic_cleanup_preserves_input() {
    let d=TestDir::new("panic");let input=d.0.join("input");let output=d.0.join("output");let src=fixture(37,19);write(&input,&src);
    let r=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||gaussian_file(&input,&output,37,19,3.,&d.0.join("scratch"),1024,&mut ||false,&mut |p|{if p.pass=="vertical"&&p.completed>0 {panic!("intentional callback panic");}})));
    assert!(r.is_err());assert!(!output.exists());assert_eq!(read(&input),src);assert!(fs::read_dir(d.0.join("scratch")).unwrap().next().is_none());
}
#[test]
fn invalid_dimensions_budget_sigma_and_length_fail_without_output() {
    let d=TestDir::new("invalid");let input=d.0.join("input");let output=d.0.join("output");write(&input,&[1.,2.,3.,4.]);
    for (w,h,sigma,budget) in [(0,2,1.,1024),(2,0,1.,1024),(40001,1,1.,1024),(2,2,-1.,1024),(2,2,f32::NAN,1024),(2,2,f32::INFINITY,1024),(2,2,1.,0),(2,2,1.,64*1024*1024+1),(3,2,1.,1024)] {
        let r=gaussian_file(&input,&output,w,h,sigma,&d.0.join("scratch"),budget,&mut ||false,&mut |_|{});assert!(r.is_err());assert!(!output.exists());
    }
    let r=gaussian_file(&input,&input,2,2,1.,&d.0.join("scratch"),1024,&mut ||false,&mut |_|{});assert_eq!(r.unwrap_err().kind(),io::ErrorKind::InvalidInput);assert_eq!(read(&input),vec![1.,2.,3.,4.]);
}
#[test]
fn existing_output_is_never_overwritten() {
    let d=TestDir::new("existing");let input=d.0.join("input");let output=d.0.join("output");write(&input,&[1.,2.,3.,4.]);fs::write(&output,b"keep-this-file").unwrap();
    let r=gaussian_file(&input,&output,2,2,1.,&d.0.join("scratch"),1024,&mut ||false,&mut |_|{});assert_eq!(r.unwrap_err().kind(),io::ErrorKind::AlreadyExists);assert_eq!(fs::read(output).unwrap(),b"keep-this-file");
}
#[test]
fn nonfinite_input_and_missing_scratch_cleanup_partial_output() {
    let d=TestDir::new("nonfinite");let input=d.0.join("input");let output=d.0.join("output");
    for sigma in [0.,3.] {
        write(&input,&[1.,f64::NAN,3.,4.]);let r=gaussian_file(&input,&output,2,2,sigma,&d.0.join("scratch"),1024,&mut ||false,&mut |_|{});assert_eq!(r.unwrap_err().kind(),io::ErrorKind::InvalidData);assert!(!output.exists());
    }
    write(&input,&[1.,2.,3.,4.]);let r=gaussian_file(&input,&output,2,2,1.,&d.0.join("missing-scratch"),1024,&mut ||false,&mut |_|{});assert_eq!(r.unwrap_err().kind(),io::ErrorKind::NotFound);assert!(!output.exists());
}
