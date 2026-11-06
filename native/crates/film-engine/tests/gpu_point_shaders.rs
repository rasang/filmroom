//! Validate the five point-op WGSL shaders added for the GPU preview chain:
//! colorspace_convert, cctf_encode_srgb, cctf_decode_srgb, table_lookup,
//! lut3d_interp. Guards against shipping a shader that naga rejects.

#[test]
fn point_op_shaders_validate() {
    let shaders = [
        ("colorspace_convert", include_str!("../../spektrafilm-shaders/wgsl/colorspace_convert.wgsl")),
        ("cctf_encode_srgb", include_str!("../../spektrafilm-shaders/wgsl/cctf_encode_srgb.wgsl")),
        ("cctf_decode_srgb", include_str!("../../spektrafilm-shaders/wgsl/cctf_decode_srgb.wgsl")),
        ("table_lookup", include_str!("../../spektrafilm-shaders/wgsl/table_lookup.wgsl")),
        ("lut3d_interp", include_str!("../../spektrafilm-shaders/wgsl/lut3d_interp.wgsl")),
        ("grain", include_str!("../../spektrafilm-shaders/wgsl/grain.wgsl")),
        ("grain_layers", include_str!("../../spektrafilm-shaders/wgsl/grain_layers.wgsl")),
    ];
    for (name, src) in shaders {
        let module = naga::front::wgsl::parse_str(src)
            .unwrap_or_else(|e| panic!("{name}: WGSL parse failed: {e}"));
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap_or_else(|e| panic!("{name}: WGSL validation failed: {e:?}"));
    }
}
