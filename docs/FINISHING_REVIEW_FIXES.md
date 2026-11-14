# Finishing branch review fixes

Review base: `711e09213bfc6aaff4a5de104ce6669a576a792c`.

- Recover `film-result-UUID.rgb.finishing.part` at process startup in the private
  cache and direct UUID export-job directories. Retry removes this result type
  before disk admission. Source, checkpoint, arbitrary files and symlinked job
  trees are not traversed or deleted. Legacy direct-cache finishing files also
  have a narrow allowlist. Normal cancellation retains the existing Rust guard.
- Keep intermediate and post-finishing preview identities separate end to end:
  native `scan-source-UUID-finished.png`, final-ready callback, one intermediate
  plus one final publication, and `scan-preview-ATTEMPT-final.jpg`. The final
  is preferred and has a new UI file identity. Late intermediate callbacks,
  cancelled/invalid final images and stale attempts cannot replace it.
- A failed optional final thumbnail leaves only the intermediate available;
  its mere existence can never be mistaken for a committed finishing result.
  Actual exported pixels, darkroom processing and checkpoint data are unchanged.

Regressions are wired into the read-only finishing-performance workflow:
`FinishingRecoveryHostTest`, `ScanPreviewStoreHostTest`, native `finishing`
and `scan_preview::tests`, alongside the existing cache/GPU/codec checks.
These are host checks; they do not establish phone performance or a device
force-stop test result. No APK or main/dev merge is included.
