# 0.1.22.2-beta7 (111)

- Keep whole-photo refinement enabled while the Home Live cover is visible. Idle
  prewarming remains independently suspended; background lifecycle still retires
  refinement. Film/parameter changes may replace QUICK with REFINED without replay.
- Remove history records even when their output is missing. Unverifiable or
  inaccessible gallery assets are preserved and reported as record-only cleanup;
  denied system deletion consent still retains the record.
- Replace whole-button breathing with a single staggered three-layer Live icon
  ripple; preserve transparent background and white/off, orange/on colors.

No native processing changes. Retain the beta2 native-source/artifact baseline.
Host tests verify scheduling/lifecycle and stale frames, deletion journal behavior,
and drawable timing/geometry; real-device GLES playback remains user validation.

Focused regression: 172 host tests passed; three existing native-fixture tests
skipped. No failures.
