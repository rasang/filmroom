# beta31 combined viewfinder changes

Version 83 / 0.1.31-beta31, based on beta30.

- Prepare the current photo and embedded Live clip while the existing effect chooser is open. The handoff is single-use, expires after 15 seconds, validates the source/result/presentation identity, and releases resources on cancellation. If preparation is not ready, wait on the existing page instead of entering an empty effect page. No decoder/GL context is claimed to be pre-created in the chooser.
- During destination codec/audio warmup, hold the actual opening composition at t=0 instead of a blank surface or the completed photograph. The 3-second motion clock still starts when primed.
- Apply the same opening-finder geometry in preview and video/Live export. Horizontal/vertical 135 slack is outside the finder. Final-result sampling and output dimensions remain unchanged.
- Remove beta30's added automatic original-URI mapping and permission-retry button. Keep the existing manual reselection flow and wording, as requested. This does not claim to repair inaccessible old picker grants or deleted files. The previously added backup geometry/metadata serialization is retained.
- Add a persistent, default-on original Live audio switch in the viewfinder. It selects source audio only; original video and existing shutter/ambience are retained. Preview replay and both video/Live exports use the current selection. This preference is ordinary local app state, not a promise to survive uninstall without backup.

## Scope and verification limits

The combined Activity merge retains the preloaded-photo handoff, restores the original manual-photo flow, and applies the original-audio selection to preview/exports. Handoff tests wait for asynchronous entry and still verify all destination intent metadata. Expired/stale handoffs do not silently display the wrong prepared picture.

Host GLES regression uses rendering code byte-identical to the integrated renderer/geometry and checks landscape/portrait rails, equal-aspect and pixel-rounding bypasses, split-focus orientation, and final-result pixel identity. It is not an Android MediaCodec/real-device test.

The prior attempted all-class suite did not complete and contained three failures independently reproduced on beta29. This batch runs the affected/previous regression set and mandatory preset/backup gate; it does not erase those known broad-suite limits. No emulator was started.

Final affected regression: 319 tests passed, zero failures/errors/skips. Mandatory preset/backup gate: 122 tests passed, zero failures/errors/skips. The first run's two new finder-parity fixtures used API 28 below the app minimum; they were moved to isolated API 35 while retaining their assertions, and passed in the full rerun. No device test was performed.

Full release lint completed with 0 errors and 108 warnings.
