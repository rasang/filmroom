//! Same scientific pipeline with presentation sampling enabled/disabled: byte identity oracle.
use film_engine::{scan_preview,streamed};
use spektrafilm_core::params::RuntimeParams;
use std::{fs,path::{Path,PathBuf},cell::Cell};
struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
#[test]fn actual_route_output_is_bit_exact_and_preview_precedes_scanner_sharpen(){
 let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let(w,h)=(41,29);
 for (index,(film,direct)) in [("kodak_portra_400",false),("kodak_portra_400",true),("kodak_ektachrome_100",true)].into_iter().enumerate(){
  let root=std::env::temp_dir().join(format!("scan-integration-{}-{index}",std::process::id()));fs::create_dir_all(&root).unwrap();let _guard=Temp(root.clone());let input=root.join("input.rgb");fs::write(&input,(0..w*h*3).flat_map(|i|(0.02+((i*17)%91)as f32/110.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut p=RuntimeParams::default();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.io.scan_film=direct;p.camera.auto_exposure=false;p.film_render.grain.active=false;p.film_render.halation.active=false;p.film_render.dir_couplers.active=false;p.print_render.glare.active=false;p.film_render.glare.active=false;p.scanner.unsharp_mask=[0.7,0.6];
  let baseline=root.join("baseline.rgb");streamed::export_file(&data,film,"kodak_portra_endura",p.clone(),&input,&baseline,&root.join("scratch-a"),w,h,384,0.,||false,|_,_,_|{}).unwrap();
  let actual=root.join("film-result-12345678-1234-1234-1234-123456789abc.rgb");let preview=scan_preview::path(&actual).unwrap();let observed=Cell::new(false);
  streamed::export_file(&data,film,"kodak_portra_endura",p,&input,&actual,&root.join("scratch-b"),w,h,384,0.,||false,|phase,_,_|{if phase=="scan_sharpen" && preview.is_file(){observed.set(true)}}).unwrap();
  assert_eq!(fs::read(&baseline).unwrap(),fs::read(&actual).unwrap(),"Presentation must not alter {film}/{direct} scientific output bytes");assert!(observed.get(),"Full-color source must exist while scanner sharpening still runs");assert!(preview.is_file());
 }
}
