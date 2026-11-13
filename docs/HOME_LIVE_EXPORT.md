# Home Live preview and export

Integrated the feature-only portion of `experiment/home-motion-reconstructed-exp6`
onto the current dev tree. This does not restore the experiment's application ID,
version, disabled updates, or old export/import implementation.

## Behavior

- The icon between Recipe and Compare selects None / Light table / Viewfinder.
  It has no background: white when off, theme orange when on. An enabled-state
  change runs one 1000 ms three-layer ripple (inner to outer, staggered by 80 ms),
  respecting system animation disablement. The button itself stays fixed; it is not
  a continuous animation.
- Selection plays once; stationary hold replays; release returns to the export-cover
  scene. Original paired Live inputs require their own authorized video grant;
  unrecognized/missing input is not fabricated.
- With Live enabled, the normal export action snapshots the selected mode in the
  background job and requests existing `ORIGINAL` Live quality. The existing codec
  and cover-resolution fallbacks remain, without resolution downgrade messages.
  Unsupported devices and export failures are not silent successes or videos.
- Before gallery publication, the full processed image (after crop/frame, before
  animation) is encoded as a private PNG, kept at the rendered export dimensions.
  It is separate from the existing normalized **original** preview and the small
  list thumbnail. No extra ordinary photo is published to the gallery.
- Reopening the list, switching styles and exporting another effect use that clean
  still, never the decorated Live cover. Old ordinary-photo records keep using
  their normal gallery photo. A missing clean still fails explicitly rather than
  silently degrading to the wrong image.
- Live jobs persist their exact published asset URIs/names for pair cleanup. The
  publisher informs the queue before visibility changes; interrupted publications
  are recovered before deciding whether to resume. Deleting a history record
  removes its private clean/source previews; gallery deletion uses the existing
  explicit deletion flow for all journaled assets.
- Metadata-only backups retain the Live marker but do not include private full
  images. A restored Live record without its clean still cannot re-render another
  effect until recreated from its original source.

## Verification boundaries

Host tests exercise bitmap ownership, frozen queue targets, private file validation,
metadata/record persistence, authorization lifecycle and publication/deletion
journals. They do not prove device GLES/codec performance, OEM gallery recognition,
or frame-rate parity. No release is created by this integration task.

Validation run: 487 selected host tests passed (six existing external/native-fixture
cases skipped), 126 preset compatibility tests passed, and `lintDebug` passed.
The full-size clean-store fixture is 3000 × 1500 pixels, with byte and pixel checks.
Eight additional UI host tests passed for icon state/animation reset and paired-video
permission/picker lifecycle, including stale results after recreation. Host-rendered
white/off and orange/on icon screenshots were visually checked; not device captures.
