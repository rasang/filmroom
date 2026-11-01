# V5 full-image spatial storage and lifetime

This is an experimental replacement for V4's overlapping whole-pipeline tiles.
No user pixels are resized and no halo radius is truncated. Pointwise stages use
bounded full-row groups; spatial stages execute the same whole-image separable
FIR / Young–van Vliet IIR arithmetic, using a local intermediate plane.

## On-disk units

For `N = width * height`, every native stage plane is `8N` bytes (f64 LE).
The caller's linear RGB input and final output are each `12N` bytes (f32 LE).
The native scratch directory is unique, private and created exclusively by the
native export. Its files are not exposed in logs and are never uploaded.

| Stage | Maximum simultaneous native scratch planes | Why |
|---|---:|---|
| Exposure | 3 | RGB film exposure planes |
| Scatter | 7 | 3 exposure + retained core + tail accumulator + component output and horizontal temporary |
| Halation | 6 | 3 exposure + sum + blur output + horizontal temporary |
| Initial density | 6 | 3 log-exposure + 3 density |
| DIR | 11 | 3 log-exposure + 3 density + correction + retained core + exponential sum + blur output + horizontal temporary |
| Grain | 7 | 3 density + channel sum + current sampled layer + blur output + horizontal temporary |
| Print/scan | 3 | Consumed density; output belongs to caller |

DIR deletes each consumed log-exposure plane as its replacement density plane
is completed, so the old and new image sets do not accumulate. Scatter/halation
components and grain sublayers are consumed and removed immediately. The
bounded live logical scratch maximum is therefore `88N` for currently supported
mobile settings. This is a code-lifetime bound, not a measurement or filesystem
allocation guarantee. Current admission remains deliberately conservative:
`128N + 64 MiB` **additional native scratch**. Input/output and encoded image are
accounted for separately by the application. Filesystem metadata, reserved
blocks and concurrent work also need free-space reserve.

## RAM and failure behavior

Spatial explicit buffers are at most 16 MiB; pointwise groups target 32,768
pixels, rounded down to whole rows (one full row minimum). LUTs and calibration
are prepared once. No full-image sample array, spectrum array, or mmap is used.
The hidden 128..512 MiB budget is an admission estimate, not an OS heap cap or
promise that total app RSS is below it. Decoder/app allocations are separate.

Normal success removes the owned scratch directory before reporting success.
Error, cancellation and unwinding remove owned scratch and partial final output;
input is preserved. A killed process cannot execute destructors, so the app
journals and deletes orphaned `film-native-v5-*` private directories on restart.
Never delete arbitrary cache content. Storage errors are explicit, not a reason
to silently reduce dimensions or turn off grain/halation.

## Evidence scope

`results/v5-streamed-reference/` compares six small complete exports with the
whole-image CPU pipeline, including chunk boundaries and output cancellation.
Large-image actual RAM, wall time and measured logical scratch are recorded
separately in `results/file_export_v5/`; do not infer phone performance from cloud
CPU timing or relabel V4 benchmarks as V5.
