# Pre-imaging composite contract v1

Optional MobileParams `preImaging` describes a caller-owned linear-sRGB photo + film-edge composite. The whole composite enters every photographic stage. This is an artistic treatment of existing display-referred film-edge assets, not a physically equivalent film-stock boundary simulation.

Fields: `version:1`, `frameWidth`, `frameHeight`, `photoWidth`, `photoHeight`, `meteringWidth`, `meteringHeight`, and export-only `meteringPath`.

- Frame dimensions must exactly equal the supplied RGB frame. All dimensions are integers in 1..40000. Aperture photo dimensions may not exceed frame dimensions.
- Photo dimensions describe aperture pixels at the current composite resolution. Physical pitch is `filmFormatMm * 1000 / max(photoWidth,photoHeight)`, preserved by whole preview, refined GPU preview, CPU streaming, and GPU export.
- Preview AE requires the existing verified full-photo `previewMetering`; export continues to reject all preview metering/source contracts.
- Export requires a separate original, unframed, immutable linear-sRGB f32le RGB spool. Native applies its existing private files/cache path policy, exact dimensions/byte count, full finite-sample scan and cancellation. Source and composite paths must differ. The source is validated even when AE is disabled.
- With AE enabled, native measures only this separate scene and resolves its EV before any checkpoint or export-reuse lookup. The exact resolved f32 EV and a versioned runtime tag are included in CPU/GPU scientific identities. The metering path is excluded from serialization and is never a cache identity.
- Metering still uses native's existing <=256 nearest-grid algorithm. Preview/export parity depends on supplying the same scene meter values/grid; a bilinear preview meter versus a nearest full-source export meter can differ, as with legacy full-photo metering.
- Frame dimensions drive pixel/memory/scratch planning. Caller must also reserve and retain its original scene and generated composite spools for the job lifetime.
- GPU remains eligible. Existing fallback and cancellation rules remain unchanged. Refined native preview supports the contract. Detail ROI explicitly rejects it because its source-coordinate ownership is different.
- Absent `preImaging` preserves legacy serialized parameters/runtime, dimensions, exposure, pitch and cache behavior. Existing post-imaging export jobs are unaffected.
