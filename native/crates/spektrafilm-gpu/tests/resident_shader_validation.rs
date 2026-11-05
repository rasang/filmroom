#![cfg(feature = "wgpu-backend")]
#[test]
fn all_resident_wgsl_parse_and_validate_without_a_gpu() {
    let dir=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../spektrafilm-shaders/wgsl");
    for path in std::fs::read_dir(dir).unwrap().map(|p|p.unwrap().path()) {
        if !path.extension().is_some_and(|e|e=="wgsl") {continue;}
        let source=std::fs::read_to_string(&path).unwrap();
        let module=wgpu::naga::front::wgsl::parse_str(&source).unwrap_or_else(|e|panic!("{}: {}",path.display(),e.emit_to_string(&source)));
        wgpu::naga::valid::Validator::new(wgpu::naga::valid::ValidationFlags::all(),wgpu::naga::valid::Capabilities::all()).validate(&module).unwrap_or_else(|e|panic!("{}: {}",path.display(),e.emit_to_string(&source)));
    }
}
