# Filmroom 0.1.22.1

This stable release promotes the code from `v0.1.22.1-beta5` (`3e6812a4`).
Only the release version changes to 0.1.22.1 / versionCode 104; no rendering or device-admission behavior is changed for promotion.

- Retains vendor-neutral Vulkan admission, private normalized source previews for RAW and other photos, and the GPU export improvements accumulated since 0.1.22.
- Physical halation uses staged scalar H/V spools, preserves kernel support and signed accumulation, and reports bounded work geometry and timeout details.
- Final output cleanup ownership, preview lifetime/cancellation and optional-memory admission fixes are retained.
- Original package and release certificate are retained for in-place updates.

## Validation boundary

Promotion does not add handset evidence. The focused tests and software-Vulkan comparisons documented in `GPU_SPATIAL_EXPORT_BETA5.md` remain the algorithm evidence. Large-image handset runtime, peak memory, thermal behavior and every device/driver combination are not verified. Physical staging still has substantial logical IO, and paper-side diffusion's global prepass can remain expensive. The full test suite has known baseline failures; focused gates are not a full-suite pass.
