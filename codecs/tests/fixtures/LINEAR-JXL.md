# Synthetic JPEG XL sample

`linear-u16-18x20.jxl` contains an 18×20 three-channel uint16 array. Each consecutive
sample cycles through 0, 1, 255, 256, 32767, 32768, 65534, 65535. It contains no user
photograph or personal metadata. `generate-linear-jxl.cpp` generates it with the
pinned official libjxl 0.12.0 encoder, lossless=true, effort=5,
uses_original_profile=true, linear-sRGB encoding metadata and a container.

The application only links libjxl_dec. Recreating this development fixture
requires additionally building upstream's `jxl` and `brotlienc` targets in a host
build, then linking the generator with libjxl.a, libjxl_cms.a, libhwy.a,
libbrotlienc.a, libbrotlidec.a, libbrotlicommon.a, pthread and libm. The generator's
single argument is its destination file. The file is wrapped in generated DNG
containers by `tests/jxl_dng.rs` to test exact endpoint preservation, all TIFF
orientations, crop/profile processing, multiple strips and failure cleanup.
