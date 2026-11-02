# Filmroom 0.1.19-beta.2 (21)

Private test build; no GitHub Release/publication.

- Added measured Kodak Double-X 5222 and Tri-X 7266 B&W camera stocks and 2302 print stock, with stock-specific development controls and approved original frame assets. These use the spectral/density engine, not a final grayscale filter.
- Unsupported color-layer controls are disabled for B&W. 2302 uses its measured density table with scalar gamma; unsupported skew/morph fitting and a nonexistent neutral database are not simulated. Reference photographs are not included. 7266 is Super 8 geometry, not a 135 imitation.
- Added RAW batch Auto / Small / Standard / Large. Auto retains camera RAW and Linear DNG plans; Bayer DNG may use larger bounded row batches. Memory caps and admission checks remain unchanged; image size, quality, and pixel math are unchanged. Fixed-format block decoders can ignore the setting. Host benchmarks do not guarantee phone speed gains.
- Fixed RAW metadata/date retention through export and publication rescan. Real EXIF/XMP/Nikon recorded offsets take priority. Only when absent, the user-approved compatibility offset uses the frozen import timezone while retaining original wall date/time; diagnostics distinguish this inference from camera metadata. Export modification time remains current.
- Added bounded field/reason-only metadata diagnostics; no EXIF values, paths, serial numbers or GPS coordinates are logged.
- Fixed return from background settings to export configuration, white-hole switch capability, import wording, and print exposure control order.

Camera RAW per-operation ceiling remains 256 MiB; Android DNG remains 128 MiB, with dynamic shared memory admission. No RW2 prototype or export-SHA speed experiment is included. All prior Beta1 changes remain.

No physical-device installation, OEM gallery indexing, device GPU behavior, memory peak or performance has been verified. Final build/test evidence is maintained separately; this changelog does not itself assert gate completion.
