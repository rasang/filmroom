# Sony lossless Bayer streaming (phase5, host gates passed)

Base: frozen variants candidate 561398530e694af6e61b3d0cf1fbf043cb866e3e. Separate from joint HE integration.

Route only ARW cpp1 with the validated compression7 JPEG tile table. Full512×512 tiles contain1024×256 SOF samples, four unsampled components. Preserve vendor RGGB packing exactly; write two rows per packed scanline into an anonymous full2P-byte integer FD. Whole-sensor coordinates and existing halo8 development are retained. No tile-local demosaic, preview, resolution reduction, level rescaling, or new color transform.

The initial TIFF probe and dummy metadata now share a File-backed source. Decode reparses the JPEG block table from a clone of the exact opened FD used for dummy metadata and read_at pixel data; stale equal-length replacement offsets are rejected. The metadata mmap is dropped before tile reads. Input modification in place during decoding is not an immutable snapshot guarantee (existing file API limitation); malformed entropy still gets independent validation.

Memory admission conservatively includes BASE32MiB + full input bytes + largest encoded tile +2MiB tile scratch, although read_at only holds one encoded tile. Development retains no whole sensor RAM and uses BASE+96B per active strip pixel including halo. Disk admission includes integer2P plus fullfloat12C, max1GiB. All normal52MP/128MiB constraints remain. No files are counted as supported until real decode gates pass.

integer_spool.rs extracts the existing ARW6 strip development without changing normalization or coordinate math; ARW6 fullfloat SHA must remain unchanged. Scratch keeps the integer FD anonymous, preallocated and closed on every error. Cancellation is checked before reserve, tile entropy, each output row and each development row. Float output guard owns cleanup.

Host evidence (runtime source e666b4b3eb898fb550fa5f950529faae2d07d8ae):
- Fresh-target dev build,11 focused units passed;3 private fixture tests ignored in normal run and explicitly invoked for real samples.
-6736/6929/8845 whole vendor integers equal, full/dummy blackwhite/WB/crop/active metadata equal; final float output matches original in-memory+global-strip reference byte for byte.
-HostDEV maxRSS73288/66584/66552KiB, not Android or optimized release claims. Offline full-memory oracle intentionally exceeds app RAM budget; it is test-only.
-17 old full-output SHA unchanged: original10, Canon129/130/2085/2976, SonySmall6931, A1002346, ARW6 8846.
-12 original fault/budget/cancel cases fail closed, no named temp/partial output, FD4→4. The first100ms integer-labelled cancel actually occurred at admission; an additional1000ms real decode cancellation passed independently. Reproduction script now uses1000ms. Output-observation cancellation occurred after actual developed bytes existed.
-Independent whole float finite scans and visual QA found no structural seams, not independent color accuracy certification.

Still pending joint semantic integration, AndroidABI and actual mobile runtime memory. No publishing authorization. Input fullsize is deliberately still charged; unusually large encoded inputs may need further metadata-bounded admission work, not suffix-complete marketing.
