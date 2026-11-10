# Home Live preview: exp6 reconstruction

This branch is a **reconstruction**, not recovery of the original Kotlin source or
original experiment Git history. It starts from `dev` commit `0278d7a0` (beta36).
The original local experiment commit was reported as `ec075e1c`, but is not
available in the recovered checkout or remote repository.

## Evidence and scope

The user authorized reconstruction from the previously delivered exp6 installation
package and the accumulated interaction requirements. The package used as evidence
has SHA-256:

`ac2ecfa31c23216dae64ac5578d3c5e4d1c2ae91f26d5c3b2f7d324b1a29e2ea`

JADX 1.5.3 output and fallback DEX instruction output were used to inspect
HomeMotionPreview, HomeMotionSnapshot, HomeMotionInputOwner, layout, diagnostics,
MainActivity integration and export-preview hold gestures. Decompiled Java is not
original source: incomplete coroutine output and misleading synchronization output
were checked against instruction output and rewritten as maintainable Kotlin.
No APK, private photographs, signing credentials, or decompiler output is included
in this branch. Native libraries/audio needed for local builds are external
build inputs, not new source claims.

## Reconstructed behavior

- Separate application ID `dev.filmroom.homemotion`, no shared app-private data with
  the production app; production update prompts disabled.
- Live icon between Recipe and Compare. Popover: None / Light table / Viewfinder;
  selected mode highlighted orange.
- Enable or change photograph: play once. Hold a stationary image for 350 ms:
  replay; release/cancel: stop sound and show the 2900 ms export-cover scene.
- Keep the Live cover visible between plays and while processing new film settings;
  replace textures only when the next snapshot is ready. None restores normal preview.
- Source/result snapshots own their bitmaps; preparation is cancellable and stale
  results cannot overwrite the current photograph or settings.
- Viewfinder original Live input uses the existing detected embedded-motion input,
  source crop mapping, original-audio preference and three-second effect timeline.
  Original motion plays through the focusing/opening segment, not an extra clip
  after the three-second effect. Light table matches existing static-photo export.
- Surface decoding first; bounded warmup and bitmap fallback, then static fallback
  for unsupported/unavailable preview input. This does not assert support for every
  vivo paired-file or Honor input format.
- Decoder completion releases file leases only after actual decoder cleanup.
- Export-list effect preview supports hold-to-replay while retaining short-drag
  navigation. This entry still previews synthesized effects, not a new saved-Live
  output-history model.

## Explicit exclusions

Direct Live export from the home editor remains on hold. The normal export button
and queue semantics are unchanged. This branch is not merged into `dev` and no
release is published by reconstruction. Device GPU compatibility, gallery format
compatibility and exact frame-rate parity with the original APK require device
verification; passing JVM/host tests is not such proof.

## Validation

The reconstruction uses versionCode 95 and versionName
`0.1.36-home-motion-reconstructed-exp6`, distinct from the original exp6 (94).

The final implementation passed 97 focused unit/host tests, zero failures, errors
or skips. Selection included `*HomeMotion*`, `*EffectHoldReplayGestureTest`,
`*EffectMotion*`, `*EffectPreviewAudioTest`, `*EffectPhotoNavigationTest`,
`*EffectRotationLifecycleHostTest`, and `*EffectSurfaceRelease*`.

Independent code review additionally corrected failed-preparation retry state,
GL candidate cleanup on initialization failure, retained-player failure callback
identity, and failure notification after a successful static-cover frame. These
are maintained-source safety fixes, not claims of byte-for-byte reconstruction.

Release validation uses `:app:assembleRelease -PfilmroomAbi=arm64-v8a`, which
includes the mandatory preset/backup compatibility gate. Its initial run exposed
four existing tests with hard-coded production FileProvider authorities; those
assertions now derive the authority from the installed application ID, preserving
private-file isolation checks instead of skipping tests.

No device or emulator installation/playback test was performed. Full lint was not
run; release vital lint is the packaging check. No claim is made that these sources
are byte-for-byte identical to the original APK sources.

Final release result: `assembleRelease` succeeded, including all 126 mandatory
compatibility tests (zero failures/errors/skips) and release vital lint. The local
unsigned APK metadata confirms `dev.filmroom.homemotion`, versionCode 95. This
artifact was used only for build verification, not published as a release.
