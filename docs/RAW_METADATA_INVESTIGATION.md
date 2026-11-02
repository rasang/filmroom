# RAW capture metadata follow-up (unreleased)

Baseline: Beta 1 `1a05595f` (version code 20). Public samples: raw.pixls 750,
6887, 131, 130, 851, 8846. Source-independent TIFF inspection and AndroidX
snapshot → durable queue → synthetic JPEG readback establish that standard
camera/exposure/capture dates survive. This diagnostic alone is **not** proof
of the full production RAW pixel export; the opt-in production queue test
separately exercises actual decoder, resize, borders, encoder and final JPEG.

## Validated changes

- Empty, all-zero EXIF comment reservations (8–8192 bytes) contain no text and
  are omitted without a misleading encoding warning. Nonempty unknown text
  encoding remains a warning. No original metadata values are fabricated.
- Rejected metadata carries only fixed schema field names + fixed reason codes
  into local diagnostics, never values, paths, serials, coordinates, comments,
  exception text or the original metadata JSON. Up to 24 distinct issues;
  overflow is explicitly represented by `METADATA:ISSUE_LIMIT`.
- Reasons survive bounded metadata snapshots and queue recovery. Diagnostic
  records distinguish `READ_VALIDATION`, `WRITE_VALIDATE`, `WRITE_EXCEPTION`.
  No normal metadata fields are logged; these notices do not set a fatal error.
- Existing 48 KiB EXIF/snapshot limits and transactional injection remain.
  Legacy snapshots without issue codes still load; stored issue text is
  revalidated against the enum allowlist before it can reach diagnostic output.

## Capture-date indexing

Older D800 / EOS 7D / A7 II originals have DateTimeOriginal but no standard EXIF offset (D800 has recoverable Nikon WorldTime).
Previously this deliberately omitted MediaStore DATE_TAKEN, leaving the gallery
free to use the new export date. Indexing now uses the import device's frozen
local timezone only when no explicit source offset/XMP instant exists. Source snapshots retain the original offset presence. With explicit user approval,
outputs lacking a source offset receive a compatibility OffsetTimeOriginal from
the frozen local interpretation; diagnostics label ASSUMED_IMPORT_ZONE, never
log the timezone/date values, and never claim it was a camera recording. EXIF
DateTimeOriginal remains unchanged; EXIF modification
time and provider-owned DATE_ADDED/DATE_MODIFIED remain the new export time.
This is an explicit local-wall-clock interpretation, not invented source timezone.
Legacy queue snapshots lacking an index zone freeze the current device zone on
first migration; the timezone of an old import cannot be reconstructed.

## Nikon scope

D800 sample 750 has ISO and lens information only in Nikon private MakerNote,
not its standard ISO/lens EXIF tags. The bounded fallback is validated
for type-2 Nikon MakerNote `ISOInfo` (0x25), four-rational `Lens` (0x84),
and recorded `WorldTime` (0x24) base-zone plus explicit DST flag. D800 sample
750 records +01:00 with DST enabled, hence its real source offset is +02:00.
It does **not** infer a precise lens model from focal lengths, decrypt LensData,
copy opaque MakerNotes or introduce a dependency. Generic missing-zone inference
is the separately authorized output-compatibility policy above.
Reference: ExifTool's official `lib/Image/ExifTool/Nikon.pm`, ISOInfo and Lens
field definitions, https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/Nikon.pm.

## Validation invocation

Set `FILMROOM_PUBLIC_RAW_METADATA=1` with the six local public originals for
`PublicRawMetadataDiagnosticHostTest`. Also set `FILMROOM_HOST_JNI_DIR` to the
frozen Beta 1 Linux libraries and load `tools/host-jni/real-queue.init.gradle`
for `PublicRawMetadataExportHostTest`. MediaStore is an explicit host fake;
it applies the source-verified AOSP publish-scan date algorithm to final JPEGs,
not an actual OEM gallery implementation.
Private user diagnostic attachments are not stored in this repository.

## Required scan-level date gate

AOSP Android 10/API29, Android 12/API31 and Android 15/API35 publish scans clear
DATE_TAKEN and rederive it from image metadata. A historical wall clock without
offset or usable GPS time, paired with a new export mtime, yields no date.
Therefore insert-only DATE_TAKEN checks are insufficient. The opt-in export test
now models this AOSP publish scan; it is still not an OEM gallery/device test.
The earlier 12-output passing run verified EXIF and submitted values only and
must not be represented as scan-level completion.

References (official source):
- https://android.googlesource.com/platform/packages/providers/MediaProvider/+/android-10.0.0_r47/src/com/android/providers/media/scan/ModernMediaScanner.java
- https://android.googlesource.com/platform/packages/providers/MediaProvider/+/android-12.0.0_r1/src/com/android/providers/media/scan/ModernMediaScanner.java
- https://android.googlesource.com/platform/packages/providers/MediaProvider/+/android-15.0.0_r1/src/com/android/providers/media/scan/ModernMediaScanner.java
- https://github.com/exiftool/exiftool/blob/master/config_files/time_zone.config

## Final isolated gate (2026-11-02)

The final production changes passed 36 tests, with zero failures, errors or
skips (`filmroom-raw-metadata-final.log`). This includes six actual public RAW
files through persisted queue/retry, native decode/render, 320px resize, JPEG
encoding, each with border off and on: 12 final JPEGs. For each published JPEG
the host provider clears the initially submitted DATE_TAKEN and rederives it
from the final file using the Android 10/12/15 scanner oracle. All 12 retain
historical capture instants; original EXIF wall clocks stay unchanged and
modification/export times are new. Tests also cover malformed Nikon offsets,
inline WorldTime endian/DST, source precedence, frozen timezone across queue
recovery, DST gap/overlap, rejected persisted zones/offsets, diagnostic privacy,
budget limits and transactional metadata-write failures.

This supersedes the earlier insert-only 22-test run. It is host evidence, not
physical-device/OEM-gallery certification. D800 ISO50 and source +02:00 are
recovered; exact encrypted Nikon LensModel is intentionally not guessed.
Evidence and public JPEGs remain outside versioned source artifacts. No private
user log, photo, metadata value or diagnostic attachment is included in Git.
