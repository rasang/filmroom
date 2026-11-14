# Lensfun distortion fallback

Filmroom can apply an external lens calibration while developing an original
camera RAW. The correction is local, before the color transform and final
orientation, and uses the existing bounded camera-RGB resampler. No Lensfun
shared library, GLib, runtime XML parser or whole-frame coordinate map is added
to the Android app.

## Enabled scope

The initial enabled combination is **Canon EOS R + Canon RF 16mm F2.8 STM**, at
16 mm, in original CR3 Bayer RAW/C-RAW with the verified full-sensor layout.
This is one lens/camera combination, not automatic support for the entire
Lensfun database. The two public optical-validation inputs are C-RAW; the
same metadata/layout contract also admits the existing supported lossless RAW
variant, without claiming a new real-file lossless sample.

The independent reader requires all of these to agree:

| Field | Required value |
| --- | --- |
| Original Make / Model | Canon / Canon EOS R |
| Canon ModelID | `0x80000424` |
| EXIF and MakerNote LensModel | `RF16mm F2.8 STM` |
| Canon LensType / RFLensType | `61182` / `288` |
| Actual and lens-range focal lengths | 16 mm |
| Sensor / CFA | 6888 × 4546 / RGGB, one component |
| Active rectangle | x=144, y=46, width=6742, height=4498 |
| Default crop | x=156, y=58, width=6720, height=4480 |
| EXIF image / Canon aspect crop | 6720 × 4480 / full 3:2, no additional crop |
| EXIF orientation | 1–8, agrees with the RAW adapter |

The generic geometry module also implements PTLens, poly3 and poly5 models,
calibration/camera crop factors, aspect ratio, optical center and focal-length
interpolation. Those capabilities do not opt other lenses or cameras into the
RAW pipeline. Ricoh GR III/IIIx and the other database entries remain disabled
until their original-source contract and optical result are qualified.

## A single distortion source

The camera adapter selects exactly one `LensStatus`:

| Existing state | Lensfun behavior |
| --- | --- |
| Supported embedded model, including identity | Keep the embedded path; no fallback |
| Present but unsupported/invalid embedded model | Keep the existing unsupported notice; no fallback |
| Already selected external profile | No second selection or resampling pass |
| Missing embedded model, qualified original CR3 | Inspect the bounded original metadata and select the approved profile |
| Missing model in any other format/layout | Ordinary existing development |

DNG never uses this fallback entry point. Its `OpcodeList3` Warp/GainMap ordering,
required-opcode failures and LinearRaw behavior remain independent. Sony ARW,
Fujifilm RAF and Olympus/OM System ORF continue through the existing embedded
paths. JPEG, HEIC and previously developed linear spools are not treated as
uncorrected sensor data.

Optional profile lookup fails closed: missing or contradictory identity,
unsupported geometry, a folded mapping, or an uncertifiable source footprint
leaves ordinary RAW decoding available. A selected correction still obeys the
normal disk/memory admission, cancellation and output-failure contracts.

## Metadata and geometry

The CR3 reader follows only the original `moov` / Canon UUID / CMT1–3 containers.
It reads selected root TIFF entries rather than relying on a fuzzy or
database-resolved marketing name. It preserves the file cursor, rejects
duplicate or cross-container fields, and has a 32 KiB cumulative read budget,
256-box limit, 64 KiB CMT extent limit and 128-entry root-IFD limit. The existing
RAW source identity and bounded-plan checks also cover this selection.

The RF16 profile is normalized to the **unrotated 6720 × 4480 default crop**.
The larger active rectangle is retained for demosaic neighborhoods, not used
as the calibration reference. Correction samples all three camera RGB channels
at one position; it does not introduce transverse chromatic-aberration,
vignetting, perspective or projection conversion. White balance/color
conversion, final crop and EXIF orientation are each applied once afterward.

`lensfun_geometry.rs` follows Lensfun 0.3.4's distortion coefficients, focal
interpolation and automatic scale. It computes coordinates directly with
constant-size state. It rejects focal extrapolation and mixed-model
calibrations. An additional continuous polynomial/interval check certifies
positive radial derivative and an in-bounds full image boundary; a finite
grid alone is not the admission test. The per-pixel path also checks finite,
in-range coordinates.

The active camera-RGB backing adds 12 bytes per active pixel to temporary disk
usage, exactly as for embedded correction. Geometry scratch stays within the
existing 10 MiB allowance. Sensor/input storage is released before geometry
finishes. No 6720 × 4480 XY map is allocated by production code.

## Cache and photo information

Successful profile selection reports `LENSFUN_DISTORTION_APPLIED`. The app's
strict code whitelist maps it to “已应用匹配镜头配置的畸变校正。” and the corresponding
English, Japanese and Korean resources. It does not also report embedded
distortion applied for this profile.

Profile-corrected output uses development policy
`camera-matrix-linear-srgb-lensfun-v1`; the other native policy strings retain
their previous values. A completed new RAW spool gets a bounded
`.development.json` sidecar binding the policy to its original source, output
dimensions, lengths and file timestamps. Restoring a valid same-policy spool
does not decode again. A stale, missing or corrupt record triggers development
from the retained original RAW into a new UUID spool, then persists the new
session cache key. Missing/invalid restore keys retain their existing error
behavior.

Rebuilding never overwrites a spool that may still be held by an export or
another source. Failure/cancellation removes only the new owned files; normal
ownership release and startup recovery clean matching records. Previews and
exports read the same completed linear spool, so a newly probed status cannot
be displayed over an old uncorrected cache.

A failed rebuild after a valid original-file probe retains the old session
keys as well as its files. The existing recovery dialog offers manual retry
or postponement; it does not loop automatically. This keeps a temporary
memory/disk failure from turning the retained original into an orphan at the
next startup. Confirmed missing/invalid original paths retain the existing
session-expiry behavior.

**Changing coefficients, reference geometry, interpolation or resampling
requires a development-policy version bump.**

## Data and attribution

`lensfun/approved.xml` preserves the selected camera and lens records from the
pinned source snapshot. Only its distortion constants are compiled by
`tools/generate-lensfun-profiles.py`; unused source TCA/vignetting values do not
enable those operations. `--check` verifies the generated source is current.

The source archive/XML/subset hashes, retrieval date and modifications are in
`lensfun/PROVENANCE.json`. Calibration data is credited to Lensfun database
contributors under CC BY-SA 3.0. The geometry adaptation credits the original
LGPL-3.0-or-later Lensfun implementation and is distributed with Filmroom's
GPL-3.0-or-later source. License texts and attribution are retained in source
and the app's existing codec notices screen.

## Reproducible checks

From `codecs`, with the normal Rust/libjxl build environment:

```sh
python3 tools/generate-lensfun-profiles.py --check
cargo test --offline --locked --release -j2
FILMROOM_RF16_CR3_DIR=/path/to/public/canon \
  cargo test --offline --locked --release \
  public_eos_r_rf16_samples_have_exact_on_file_identity -- --ignored --nocapture
FILM_LENS_FIXTURES=/path/to/public/embedded \
  cargo test --offline --locked --release --test embedded_lens_metadata -- --ignored
```

`tests/fixtures/lensfun-0.3.4-points.json` contains compact coordinates from the
actual official native library, not coordinates generated by this Rust
implementation. `tests/lensfun_geometry_oracle.py` records the native library,
wrapper/converter and XML hashes and regenerates those fixtures using the
separately archived reference inputs. Cases cover RF16, focal interpolation,
poly3/poly5, calibration/camera crop, aspect/center and `real-focal` without
projection conversion.

The regression suite also exercises exclusive source selection, strict
metadata rejection, output policy/warnings, all eight orientations, translated
active regions, strip-size invariance, admission and cancellation. DNG Warp/
GainMap and the existing embedded tests remain part of the complete native
suite. Android host tests cover warning localization/persistence and cache
reuse, migration, recovery and ownership.

## Executed production validation

The actual release `codec_probe` import path decoded **seven full-resolution
inputs**. An independent chunked scan checked **552,098,304 float values**;
all were finite, every output length matched its declared dimensions, and all
original input hashes stayed unchanged. Exact source/code/output hashes and
warning codes are in
[`lensfun-SOURCES.json`](../tests/public-fixtures/lensfun-SOURCES.json).

| Input | Output | Verified result |
| --- | --- | --- |
| EOS R / RF16, `7D6A9561.CR3` | 6720 × 4480 | Profile warning/new policy; pixels differ from the uncorrected baseline |
| EOS R / RF16, `7D6A9423.CR3` | 6720 × 4480 | Profile warning/new policy; pixels differ from the uncorrected baseline |
| RF-ID-conflict copy of the first CR3 | 6720 × 4480 | Successful ordinary decode; no profile warning; exact old uncorrected pixel hash |
| Ricoh GR III DNG, sample 3115 | 6000 × 4000 | Exact existing pixel hash; no Lensfun warning |
| Sony ARW, sample 6736 | 6192 × 4128 | Exact existing corrected pixel hash and embedded warning |
| Fujifilm RAF, sample 1178 | 6000 × 4000 | Exact existing corrected pixel hash and embedded warning |
| Olympus ORF, sample 1993 | 5184 × 3888 | Exact existing corrected pixel hash and embedded warning |

The conflict control changes only Canon RFLensType from 288 to 289: one byte
at absolute offset 4182, while lens names, sensor payload and file length stay
unchanged. Its output exactly matching the pre-change uncorrected baseline
checks the production rejection route, beyond the isolated metadata parser.

Each corrected Canon output is 361,267,200 bytes; admission accounts for
787,799,088 total temporary disk bytes under the 256 MiB codec allowance.
The original 423,892,896-byte uncorrected disk requirement remains for the
conflict control. These are native admission figures, not measured Android
RSS or APK size.

Both Canon production JPEG previews were inspected for obvious strip seams,
repeated border bands and blank corners; none were observed. This inspection
and the earlier optical comparison cover these two samples, not all focus
distances or apertures. The new camera-RGB pipeline is not expected to produce
byte-identical output to the earlier experimental post-linear-sRGB warp.

The compact official-Lensfun oracle covers **16 cases / 1,024 points**, with
identical automatic scales and a maximum single-axis coordinate difference of
**0.000451857 px**. This measures agreement with Lensfun's model, not physical
lens residual distortion. The RF16 full-frame case differs by at most
0.000236887 px. Direct absolute coordinates avoid reproducing the old native
library's accumulated float row-stepping drift.

The existing Linux JNI smoke passed **19 checks**, including sticky
cancellation, busy state and partial-output cleanup. The four Android host
test classes passed **41 tests** with zero failures/errors/skips: warning and
locale handling (12), RAW ownership/migration (9), startup recovery (9), and
RAW input adapter (11). Kotlin/Java and the unit-test sources compiled under
JDK 17 and Android SDK 35. This host run excluded the two unrelated private
audio/packed-engine asset preparation tasks because those private assets are
not provided in this checkout; release packaging gates were not changed.

The final-source `cargo test --offline --locked --release -j2` run passed
**270 tests across 40 suites**, with zero failures and **14 explicit opt-in
ignores**. The final executable hash exactly matches the binary used for the
seven-file production validation above. The complete gate includes DNG
required operations/GainMap ordering, LinearRaw, existing manufacturer routes,
all eight orientations, budgets, cancellation and the official Lensfun
coordinate fixtures.

Android ABI cross-build, APK packaging, physical-device runtime, peak memory
and device performance were not tested by this integration validation.
