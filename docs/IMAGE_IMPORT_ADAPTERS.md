# Image import compatibility boundaries

This is a behavior-preserving organization change, not new format support. Detection
uses file contents, never provider MIME, extension, camera Make, or camera Model.
A DNG named `.jpg` must continue through the TIFF/DNG route. BigTIFF and ordinary
TIFF are not newly supported; only the existing native validator may authorize RAW.

## Kotlin orchestration

- `PhotoRepository` owns URI access, bounded encoded-file copying, metadata capture,
  source/spool lifecycle, restore admission, cancellation rollback, and persistence.
- `io/importing/PhotoInputHeader` computes bounded content hints once. Hints do not
  authorize decoding or determine allocation sizes.
- `PhotoInputAdapters` selects exactly one registered `PhotoInputAdapter`. Unknown
  content is unsupported; ambiguous registration is a programming error. Errors
  from a selected adapter are propagated, never used to try a weaker decoder.
- `RasterInputAdapter` retains static-PNG validation and the existing Android
  preview decoder, including white alpha composition and sRGB output.
- `HeicInputAdapter` composes existing `HeicContainer`, `HeicMemoryPolicy`,
  `HeicImport`, ICC/SDR policies and canonical linear-spool validation. It does not
  implement HDR, alternate HEVC decoding, or new container policy.
- `DngInputAdapter` depends on `DngSourceDecoder`, not native calls directly.
  `NativeDngSourceDecoder` wraps `PhotoCodecs`, retaining native-handle ownership,
  admission, cancellation, errors and exact metadata interpretation.
- A new route supplies an adapter and is registered at the repository composition
  root. It must still pass memory/disk/cancellation, metadata, color, restore,
  output-byte-size and cleanup tests. The format catalogue alone never enables it.

`onNewRaw` is invoked **before** writing a newly allocated spool so the repository
can remove partial output after any failure. Reused spools must belong to the
canonical private source directory; adapter extraction does not relax that check.

## Native DNG compatibility policies

The codec's `compat` module groups validated capability policies rather than brand
exceptions. `Dng::read` invokes them in the original order:

1. Unsupported processing tags (fail closed).
2. Camera-calibration validation.
3. Forward-matrix validation.
4. Baseline-exposure validation.
5. Optional profile metadata, warnings and provenance checks.
6. Ordered lens-opcode policy.
7. Unit-geometry policy.

`calibration.rs` handles color/calibration metadata; `profiles.rs` handles optional
HSV/tone-curve metadata and warning policy; `lens.rs` delegates List 1/2 optional
opcode handling and List 3 validated GainMap/identity Warp behavior. The actual
GainMap mathematics remain in `gainmap.rs`; pixel development in `develop.rs` is
unchanged. BaselineExposure is not applied again by the adapter.

For another camera, first identify which actual tags/opcodes differ. Add fixture
coverage and extend the corresponding capability policy only if the transform is
understood; do not bypass checks by camera name. Preserve unsupported versus
malformed error codes. No private customer photo belongs in the distributable
fixture set.

## Verification boundaries

Adapter contract tests use fake routes. HEIC backend tests use substituted pixels,
not proof of hardware HEVC support. Native fixture/JNI tests are host validation,
not Android-device performance or camera colorimetry validation. A release requires
separate recorded native ABI and complete batch checks; this document is not a
claim that those gates have passed.

## Separate localization follow-up

A separately reviewed localization fix stores known development-note resource descriptors
on `PhotoSource`, so retained RAW/HEIC notes follow a language change without decoding
the image again. This changes note rendering, not pixel processing, source metadata or
native compatibility. The legacy String constructor argument remains a fallback.
Its locale lifecycle test is separate from the structural native parity proof.
