# Filmroom 0.1.22-beta5 test build

Application version code: 57. Engine diagnostic build: 14. JNI interface: 2.

This integration starts from dev `8cf5e59812385eb336f8980acf1811133026966c`, retaining the Android Adreno admission checks and unsupported-adapter fallback protection.

Included changes:

- Independent film/paper `none` selections, their CPU/GPU processing paths, and the accepted paper package carousel.
- Settings, saved recipes, and sanitized non-executable export history backup/import. Backups do not contain photographs. Explicit `none` remains valid while unknown media identifiers are rejected.
- Recipe icon 4 and a portrait camera scanner with an album shortcut.
- Bounded whole-frame high-resolution refinement for pre-imaged film borders, preserving composition geometry.
- More specific GPU completion-failure diagnostics, without relaxing admission or failure quarantine.
- Recorded 35 mm-equivalent focal length is preferred for imprints when present.

The HEIF grid candidate remains disabled. The codec source is unchanged from beta4; RAF support retains its previously validated limits. Host software-Vulkan validation is not an Android/Adreno device test. Detailed build and test outcomes belong to the external release receipts rather than being inferred from this scope document.
