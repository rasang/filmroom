# Filmroom 0.1.22.1-beta1

- versionCode: 99; package: dev.filmroom; original release signing key.
- Based on 0.1.22 stable. Only functional change: remove Qualcomm/Adreno identity allowlists from Vulkan GPU admission, allowing supported hardware adapters (including MediaTek Mali/Immortalis) to proceed to existing capability checks.
- Retains Vulkan requirement, CPU-adapter exclusion, device/limit/memory checks, numerical checks and failure protection. Admission does not guarantee GPU execution or speedup on every device.
- No preprocessing tiling, preprocessing progress, or independent Live-preview experiment changes.
- 14 focused admission/fallback/numerical tests passed during implementation review. No MediaTek physical-device result claimed.
