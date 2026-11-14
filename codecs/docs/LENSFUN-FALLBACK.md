# Lensfun optical correction fallback

Filmroom compiles the complete pinned Lensfun database into its original-camera
RAW development path. Distortion, transverse chromatic aberration (TCA), and
vignetting are selected independently. The production route is no longer an
RF16-only allowlist. A database record becomes executable only after source,
identity, capture-parameter, geometry and resource checks for the relevant
operation. Database inclusion does not imply that every RAW file has enough
metadata or that every camera/lens combination has been photographed and tested.

The correction runs locally in linear camera RGB before the color transform
and final orientation. Android gains no Lensfun shared library, GLib dependency,
runtime XML parser or whole-frame coordinate map. TCA addresses lateral channel
misalignment; longitudinal chromatic aberration and general purple-fringe
removal are outside this model.

## Pinned inventory and source scope

The unchanged snapshot in [`lensfun/database`](../lensfun/database) contains
**56 XML files, 1,580 lens records, 1,059 camera records and 52 mount definitions**.
[`DATABASE-STATS.json`](../lensfun/DATABASE-STATS.json) records the generated
inventory; [`PROVENANCE.json`](../lensfun/PROVENANCE.json) binds each XML to its
source archive and SHA-256.

| Calibration present | Lens records |
| --- | ---: |
| Distortion | 1,538 |
| TCA | 1,021 |
| Vignetting | 717 |
| All three | 591 |
| TCA or vignetting without distortion | 39 |

These counts refer to database records. Alternate calibration sensors,
fixed-camera lenses and accessory combinations can have separate records;
there are 1,481 distinct maker/model names. A missing distortion calibration
does not prevent use of a separately available TCA or vignetting calibration.

Supported original CFA adapters can request profiles for CR3, CR2, NEF, ARW,
RAF, ORF, PEF and RW2. This extends profile selection within the existing RAW
decoder/layout support; it does not add unsupported sensor compression modes or
remove the image-size and memory limits. Eligible original CFA DNGs have the
additional source gate described below. Camera-produced RGB/YCbCr RAW,
LinearRaw, JPEG, HEIC and previously developed linear spools do not enter this
external-CFA fallback.

ORF has an additional per-source restriction: Olympus shading compensation
may already be written into the RAW pixels, and this adapter does not establish
that it was absent. External PA vignetting is therefore not automatically
added to ORF, even when a profile, aperture and focus values are available.
Embedded distortion/TCA and eligible external distortion/TCA retain their own
selection. This source gate does not invent an unsupported on-file gain table
or change the previous embedded warning semantics.

### Identity and calibration choice

`lensfun_metadata/capture.rs` retains provenance for original tags and decoded
lens identity separately. Original make/model, lens names, actual focal length
and available independent IDs must agree. A name resolved by the decoder's
lens database is not relabelled as an original EXIF name. Missing, contradictory
or ambiguous identity leaves the external operations off.

`lensfun_database.rs` uses exact normalized names and declared aliases, a known
unambiguous camera crop, and compatible mounts. Its normalization handles case,
spacing, equivalent aperture spelling and declared manufacturer/model aliases;
it does not rank partial names or use edit-distance matching. Distinct optical
models remain ambiguous. Alternate sensor calibrations are selected without
using an APS-C-only calibration over an uncalibrated full-frame outer area.

An unnamed lens can be identified through a fixed camera's private mount and
actual focal length. An accessory-specific record requires the corresponding
combination identity. Explicitly shared fixed-lens families with discrete focal
groups, such as the GR III / GW-4 family, select the exact float32 focal group;
the pipeline does not interpolate between the bare lens and attachment groups.
See the [database README](../lensfun/README.md) for the full matching contract.

The additional original-tag reader is bounded to 32 KiB total reads, 4 KiB per
read and 256 container boxes; DNG conversion-marker traversal additionally
limits the IFD graph to 16 directories. It preserves the file cursor and does
not copy image, preview or complete MakerNote payloads. Decoder metadata runs
inside the adapter's existing bounded probe scope. Missing or unsafe optional
metadata does not prevent ordinary RAW development.

### Original CFA DNG gate

A `.dng` extension or absence of a known converter name is insufficient.
`dng_lensfun.rs` requires positive native-camera evidence: normalized Software
starts with the original camera model followed by a firmware/version marker
and a numeric version.
The independent RAW descriptor must agree with the DNG reader on sensor size,
active rectangle, default crop and available orientation, and describe CFA data.

External fallback is excluded when any OpcodeList1/2/3 tag is present, including
an empty, optional or unsupported opcode list. Existing warp, gain-map and
Stage1 operations, an existing DNG profile path, OriginalRawFileName/Data,
Adobe conversion private-data markers and non-unit/invalid DefaultScale also
block this route. Bounded original metadata must pass the normal identity and
conversion-marker checks. Missing firmware evidence keeps the existing DNG
processing path.

Admitted DNG fallback retains DNG-specific calibration, white balance, color
matrix and exposure processing through `calibrate_camera`. The existing ordered
DNG Warp/GainMap pipeline, required-opcode failures and LinearRaw behavior stay
independent. It is not a second correction pass after a DNG opcode pipeline.

## Independent selection of the three operations

Each operation obeys the same precedence, independently of the other two:

| Embedded state for this operation | External behavior |
| --- | --- |
| `Ready`, including an identity model | Keep the embedded result; do not add that external operation |
| `Unsupported` or invalid present metadata | Keep the unsupported status; do not replace it with a database estimate |
| `Missing`, with a consistent profile match | Attempt this profile's corresponding calibration and numerical admission |
| `Missing`, without a match or calibration | Keep ordinary development for this operation |

There is one selected distortion map and one selected TCA map, even when their
sources differ. For example, embedded distortion can coexist with external
TCA and vignetting. A missing actual aperture or focus value disables external
vignetting while allowing matched distortion and TCA. An unsafe optical model
or execution domain is not enabled merely because its XML exists. A rejected
external operation cannot replace an already admitted embedded operation.

## Capture parameters, interpolation and original projection

The selected camera crop, calibration crop, aspect ratio and optical center
remain distinct inputs. External profiles use the unrotated visible default
crop as their reference. Manufacturer models retain the reference chosen by
their adapters; the composition translates between those coordinate frames.
EXIF orientation is applied after optical processing.

The shared model code follows the fixed official **Lensfun 0.3.4** formulas:

| Operation | Models and interpolation |
| --- | --- |
| Distortion | PTLens, poly3 and poly5; native focal-scaled Hermite interpolation |
| TCA | Linear and poly3; linear is represented exactly by zero higher terms; native multi-focal interpolation |
| Vignetting | PA; native inverse-distance weighting over focal length, aperture and reciprocal focus distance |

Later equal-float32 calibration keys replace earlier values while preserving
the original slot, as in the native loader. The general distortion path uses
the first remaining model family and skips other families, rather than blending
incompatible formulas. Distortion/TCA queries outside their calibration sample
interval use the native nearest endpoint after the identity/focal-range checks.
Vignetting uses the lens's complete native MinFocal/MaxFocal range for its metric,
not just the subset with PA samples. Tables are bounded to 1,024 points; this
covers the pinned database's largest 301-point distortion and 440-point PA
records. It is not the old 64-point or single-focal implementation.

Fisheye, equisolid, stereographic and panoramic records preserve their original
projection. This is optical correction within that projection, not automatic
fisheye-to-rectilinear conversion. Native `real-focal` is not used when projection
conversion is disabled. A circular-lens PA fit that becomes invalid outside its
image circle is rejected for a full rectangular source domain; other valid
operations can remain available.

Vignetting requires the actual capture FNumber and a valid source focus reading
or interval. The reader does not substitute maximum lens aperture or guessed
infinity. Canon FileInfo near/far bounds, for example, retain an explicit
infinity marker and reject zero, reversed or malformed bounds. The selected
focus estimate is the reciprocal-distance midpoint. A continuous interval
check requires at most 2% **model gain variation** throughout the complete
reported focus interval and source-radius domain, including native exact-match
neighborhoods. This is a model uncertainty bound, not measured optical accuracy.

The focus certificate preserves signed polynomial cancellation: it evaluates
the interval-center difference polynomial over the complete source radius,
then adds the coefficient-interval remainder and floating-point allowance.
This admits the RF135 capture interval of 1.08–1.12 m without relaxing the 2%
limit or changing any native coefficients. A wider unsafe interval and an
interior-radius error that cancels only at the corner remain rejected.

PA gain uses the calibration half-diagonal convention; distortion and poly3
TCA use the half-short-side convention. The gain polynomial must have a positive
denominator and gain in [0.25, 64] over the **actual active source rectangle**
relative to the profile crop, including demosaic/resample neighborhoods. The
full-library path does not assume a fixed 0.5% halo. Unsafe values are rejected,
not clipped to make a model pass.

## Single resampling pass and continuous admission

The processing order is sensor normalization/demosaic, source-camera-RGB
vignetting, one composite distortion/TCA resampling, color calibration, crop
and orientation. Gain is evaluated at original source coordinates. Temporary
camera-RGB calibration clears optical state already executed, preventing a
second gain or geometry operation.

Lensfun's native uniform scale and the TCA 1.001 starting reserve are retained.
The combined map is checked continuously over the whole output rectangle.
Adaptive interval subdivision tightens the enclosure before increasing uniform
zoom; it does not crop simply because a coarse whole-image interval loses the
correlation between radius and position. The 6000-pixel RF16 regression also
checks an offset active/crop reference and keeps the previously validated view.
The per-pixel route still rejects nonfinite or out-of-range coordinates.

A separate full-domain Jacobian and RGB-channel separation bound certifies the
worst 128×32 destination tile, including interpolation taps, against the
existing **8 MiB source-read window**. Small boundary boxes cannot hide a larger
memory requirement. Finite coordinate grids alone are not admission proofs.

Vignetting alone keeps strip processing. Geometry adds an active camera-RGB
backing of 12 bytes per active pixel to temporary disk admission and retains
the existing 10 MiB geometry scratch allowance. Sensor/input storage is released
before final geometry. Cancellation, source revalidation, output ownership and
normal memory/disk limits remain in force. No image-sized XY map is allocated.

## Cache and photo information

Each requested external operation reports its own selected result:
`LENSFUN_DISTORTION_APPLIED` / `LENSFUN_DISTORTION_NOT_APPLIED`,
`LENSFUN_TCA_APPLIED` / `LENSFUN_TCA_NOT_APPLIED`, and
`LENSFUN_VIGNETTING_APPLIED` / `LENSFUN_VIGNETTING_NOT_APPLIED`. Missing calibration
is distinct from a present requested calibration that could not be applied.
The app accepts only known codes and maps them to localized resources.

Output that actually uses the full-library fallback has a format-specific policy:

| Development route | Policy |
| --- | --- |
| Camera CFA | `camera-matrix-linear-srgb-lensfun-v3` |
| Fuji X-Trans | `raf-xtrans-linear-srgb-lensfun-v3` |
| Original CFA DNG | `dng-cfa-linear-srgb-lensfun-v3` |

Prior Lensfun v1/v2 spools must be
redeveloped from the retained original source before reuse as v3. Ordinary
routes that did not apply an external operation keep their corresponding policy.
The manufacturer-only optical policies remain their existing embedded-v2 values.

The bounded `.development.json` sidecar binds policy, source and output identity.
A valid same-policy spool can be reused; stale, missing or corrupt cache metadata
causes a new owned spool to be built. A failed or cancelled rebuild retains the
original/session and old valid files, cleans only the new owned output, and
uses the existing manual-retry recovery path. It does not loop automatically.
Previews and exports consume the same completed linear spool. Fresh probe
warnings can update photo information even if unchanged pixels are reused.

**Changing coefficients, reference geometry, interpolation or resampling
requires a development-policy version bump.**

## Data, attribution and reproducible checks

`tools/generate-lensfun-profiles.py` compiles all original database XML into
static profiles and indexes. `--check` verifies source hashes and generated
outputs. `lensfun/approved.xml` and the RF16 helpers are retained for old-route
compatibility fixtures; they no longer define production inventory or scope.
The XML snapshot and generated Rust byte counts are not APK measurements.

Calibration data credits Lensfun database contributors under CC BY-SA 3.0.
The algorithm adaptations credit the fixed LGPL-3.0-or-later Lensfun sources
and ship with Filmroom's GPL-3.0-or-later code. Original XML, source/provenance
hashes and full license texts are retained in the repository and app notices.

From `codecs`, with the normal Rust/libjxl environment:

```sh
python3 tools/generate-lensfun-profiles.py --check
cargo test --offline --locked --release --jobs 2
cargo test --offline --locked --release --test lensfun_geometry --test lensfun_optics
```

The committed native-reference checks separate data coverage from optical
numerics and production RAW validation:

- `tests/lensfun_database_oracle.py` loads all 1,580 original records through the
  official native C API and checks 6,320 focal/aperture range fields.
- `tests/lensfun_geometry_oracle.py` preserves the 16-case / 1,024-point geometry
  fixture covering model families, interpolation, crop/aspect/center and
  `real-focal` without projection conversion.
- `tests/lensfun_optics_oracle.py` preserves the RF16 432-point, three-channel
  coordinate and source-gain comparison.
- `tests/lensfun_full_optics_oracle.py` adds 11 profiles / 15 captures / 135 points,
  including 301/440-row tables, zoom interpolation, native duplicate and mixed
  model rules, linear TCA, original fisheye projections and independent TCA/PA
  without distortion. One invalid circular-domain PA model is explicitly
  rejected instead of copying native zero/clipped color output.

These scripts use the separately archived, hash-pinned native 0.3.4 library and
unmodified official schema converter; Filmroom's Rust results do not generate
the expected coordinates. The additional optical comparison measured maximum
coordinate error 0.000597730 px, gain relative error 0.000000454, identical TCA
coefficient floats and PA coefficient difference at most about 5.96e-8.
The regression suite also checks continuous bounds, focus intervals, source
halos, all orientations, translated references, strip stability and cancellation.

The numerical fixtures establish agreement with the reference formulas and
coverage of the selected cases. The completed production RAW and application
checks, tied to final source/binary hashes, are recorded in
[LENSFUN-FULL-VALIDATION](LENSFUN-FULL-VALIDATION.md) and
[lensfun-full-SOURCES.json](../tests/public-fixtures/lensfun-full-SOURCES.json).
Android packaging, APK delta and device measurements remain separate work.
The earlier results below must not be reused as claims that every full-library
lens or this v3 production binary was validated.

## Historical distortion-only baseline production validation

The following is the earlier RF16 distortion-only gate recorded in
`tests/public-fixtures/lensfun-SOURCES.json`. Its totals and hashes describe
that earlier binary. In particular, the old unchanged-DNG control does not
require an eligible original CFA DNG to remain uncorrected under the new gate.


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


## Historical RF16 TCA and vignetting model validation

`tests/lensfun_optics_oracle.py` calls the unmodified Lensfun 0.3.4 native C API
with distortion, scale, TCA and vignetting enabled (flags 43). The committed
`tests/fixtures/lensfun-0.3.4-optics.json` records the native library, XML, wrapper
and generator hashes. Twelve cases include both real capture-focus estimates,
five aperture calibrations, intermediate apertures, small dimensions and
portrait geometry. Independent 1×1 calls prevent row-stepping accumulation from
becoming an algorithm requirement. The native F64 color callback uses the same
float calibration arithmetic without SIMD reciprocal approximations.

The earlier executed standalone Rust comparison checked **432 points × 3 channels** and
**432 source gains**: auto-scale agrees exactly, maximum coordinate difference
is **0.000455917 px**, and maximum relative gain difference is **0.000004618**.
These are numerical agreement measurements, not a physical lens calibration.
Six model tests also cover source-coordinate ordering and the real active halo,
unknown/invalid aperture and focus, interior focus variation missed by endpoint
checks, hidden polynomial singularities, excessive gain, TCA folds/borders,
invalid channel indexes and additional zoom composition.

Thirteen bounded-metadata tests, including both original public CR3 files,
passed. Each actual metadata read is **1,986 bytes / 20 boxes**. Both files report
FNumber 2.8; `7D6A9423.CR3` reports focus 5.98 m to infinity, and `7D6A9561.CR3`
reports 1.50–1.99 m. Both complete focus intervals pass model admission.

Production decoding and app integration are validated separately from these
standalone model tests; the distortion-only baseline above is retained as a
historical record and is not an optical-v2 output hash claim.
