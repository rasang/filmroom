# Sony phase5 stable host candidate

Base561398530e694af6e61b3d0cf1fbf043cb866e3e. Runtime source tested e666b4b3eb898fb550fa5f950529faae2d07d8ae. Final extra commit changes report/reproduction timing only, not runtime source.

Route: validated ARW compression7 cpp1 JPEGblocks, distinct from ARW6 and Small/M LinearRaw. The3 previously budget-rejected real files6736/6929/8845 now import full resolution within128MiB, no fallback/downscale. Wholeinteger and finalfloat old-reference exact;17 old output hashes unchanged. See SONY_BAYER_STREAMING_DESIGN.md and results/final-artifacts.json.

Semantic merge contracts:
1. Preserve HE/NEF and Canon branches. Add SonyBayer admission and dispatch before common output creation, with disk2P+12C and conservative input-size RAM accounting.
2. integer_spool.rs is an exact extraction of prior ARW6 global halo normalization/development. sony_arw6 delegates to it and exposes codec_error. No new pixel scale. Extra blacklevel-vector length guard is safe hardening.
3. RawSource::new_from_file is identical to HE implementation. Do not duplicate or revert joint's constructor. CameraRaw::read_file drives TIFF and dummy from the same FD; the Sony adapter reparses blocks on its decode FD and rejects stale offsets/counts.
4. Keep vendor checksum coherent and explicitly invalidate/rebuild rawler when merged. Do not copy old cached rlib.
5. Canon's separate FD helper can coexist; avoid untested abstraction consolidation in the final integration window.

Important limits: fullencodedinput remains conservatively charged;52MP remains existing limit. The three test outputs are finite and reference-exact but not independent color-certification; hostDEV RSS is not device memory. External concurrent in-place file mutation is not an immutable input snapshot guarantee.

Nothing pushed/tagged/released. Fixture files and all output/binaries remain private untracked evidence only.
