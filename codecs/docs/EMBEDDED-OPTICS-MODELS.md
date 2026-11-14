# Embedded TCA and vignetting models

Filmroom retains file-embedded distortion, lateral chromatic-aberration (TCA),
and vignetting availability independently. This document describes the Sony,
Fujifilm and Olympus additions. DNG opcodes, Lensfun profiles, color matrices,
white balance, camera looks, longitudinal CA and purple-fringe detection are
separate mechanisms.

## Data path and supported layouts

`rawler::RawImage::lens_corrections` is a bounded metadata handoff. It contains
`EmbeddedOpticsState::{Missing, Unsupported, Ready}` for TCA and vignetting.
The new `embedded_optics::from_metadata` validates numeric domains and builds
`LensExtras` with the corresponding independent `State` values. Malformed
optional coefficients do not make the RAW undecodable. A missing tag is not
reported as an unsupported tag or as an applied correction.

| Path | TCA metadata | Vignetting metadata |
| --- | --- | --- |
| Sony raw IFD | `0x7035`, signed-short count plus red and blue arrays; even count 4–32; compact or 33-slot storage | `0x7032`, signed-short count 2–16; compact or 17-slot storage |
| Fuji raw IFD | `0xf00f`, known 29-value (9-node) or 31-value (11-node, implicit zero centre) layout | `0xf010`, known 19-value (9-node) or 23-value (11-node) layout |
| Olympus MakerNote ImageProcessing IFD | `0x150c`, exactly six finite floating-point/rational numbers | No verified coefficient model |

Sony declared counts, rather than TIFF storage capacity, determine the number
of nodes. Unused padded entries are not interpreted as additional coefficients.
The two Sony tables have independent counts. For example, the unchanged public
RX100 II sample has 11 distortion nodes, 22 TCA values (11 per channel), and 16
vignetting nodes. The pinned RawTherapee/darktable parsers require matching
counts across all three tables and reject this whole group. Filmroom extends
their table formulas by using each table's own explicit count, consistent with
its independent-item policy. The original count bytes and an independent
numeric calculation are retained; this is not a claim that those applications
accept that file or produce the same image.

Fuji supports crop modes absent/0 at factor 1 and modes 2/4 at factor 1.25.
Other crop modes remain unsupported. When a recognized distortion table is
also present, the TCA/vignetting knots and crop domain must agree with it.
Each optional table can exist without the other. The older TCA layout omits
the zero-radius knot and its two zero values; only those defined entries are
inserted. The newer layout's last non-coefficient entry is not used as a node.
The older rotated-sensor RAF path does not receive these new models.

Olympus shading menu flags are never converted into a gain. The fixed
RawTherapee source notes that shading may already have been applied to RAW
pixels and exposes no embedded vignetting model; the fixed darktable Olympus
branch likewise uses unit gain. Unsupported or absent Olympus shading
coefficients must not be replaced with a manually fitted flat-field map.

## Coordinate contract and composition

Coordinates precede EXIF orientation and the user's crop. The reference centre
is `(width/2, height/2)`, and its half diagonal is radius 1. Sony uses the active
image reference; Fuji and Olympus use the on-file recommended visible crop.
The caller retains active-area pixels for interpolation and demosaic support.
Different developers' crop databases and border rules can produce different
coordinates even with the same optical parameters.

Let `u` be a position relative to the reference centre, `r` its normalized
output radius, and `D` the existing distortion map. TCA is evaluated on the
distortion-mapped source position and returns a source coordinate for one RGB
channel. Green is exactly unchanged. No intermediate RGB image is resampled
between distortion and TCA.

### Sony

For `N` values the knots are `(i + 0.5)/(N - 1)`. Values outside the knots use
the nearest endpoint. Linear interpolation gives:

```text
distortion factor: 1 + distortion_sample / 16384
red/blue factor:   1 + ca_sample / 2097152
source gain:      2 ** (2 ** (vignette_sample / 8192 - 1) - 0.5)
```

The RawTherapee combined model evaluates `D(r) * CA(r)` at the output radius.
Therefore evaluating `CA` directly at the already-distorted source radius
would be incorrect. Filmroom retains the same IFD's Sony distortion spline
and recovers `r` from `s = r * D(r)`. Within a linear segment `D(r)=a*r+b`, this
is a quadratic equation. The implementation uses its increasing root with a
stable algebraic form, exact monotonic interval checks, and constant tails.

`source_position_after_distortion(..., distortion_applied)` makes this context
explicit. If the distortion map was not applied, CA is evaluated directly at
the supplied radius. It does not undo a map that was skipped. The public
`source_position(...)` convenience method is the TCA-only case.

### Fujifilm

The stored knots describe source radius. Knots are multiplied by the supported
crop factor. A zero node with unit correction is inserted if the first knot is
positive. The source-image functions are:

```text
distortion factor: 1 + distortion_percent / 100
red/blue factor:   1 + ca_value
source gain:      100 / vignette_level
```

The existing distortion implementation solves `r = s / D(s)` analytically.
The TCA model then evaluates `CA(s)` at exactly that source radius; it neither
re-inverts the mapping nor guesses that source and destination radii are equal.
This expresses the source-domain relationship directly, avoiding the
reference applications' additional sampled inverse spline.

### Olympus / OM System

The six coefficients are `[red0, red2, red4, blue0, blue2, blue4]`. In each
non-green channel the source position is multiplied about the centre by:

```text
1 + c0 + c2 * source_radius**2 + c4 * source_radius**4
```

This follows the fixed darktable implementation, which first obtains the
distorted source radius and evaluates the CA polynomial there. The fixed
RawTherapee combined Olympus implementation evaluates both factors at the
output radius instead. Filmroom explicitly follows darktable's composition
order here; it does not claim that these two reference versions are equivalent.

## Sampling, gains and continuous bounds

TCA returns unclamped coordinates. Its caller supplies a combined automatic
zoom equal to the distortion zoom times `TcaModel::autofill_zoom()`. The latter
is a continuous upper bound across all three channel factors, at least 1.
Spline maxima are bounded by their node values; Olympus polynomial extrema
are evaluated analytically, including extrema between nodes. This keeps the
combined corrected footprint in-frame without concealing a composition error
with a broad coordinate clamp.

The accepted TCA factor range is 0.5–2, and the radial mapping must be strictly
increasing. Sony also checks the combined distortion/TCA radial derivative on
every shared linear interval; its derivative is quadratic. This rejects folds
that an endpoint-only or coarse-grid validation could miss. These are explicit
supported numeric envelopes, not inferred descriptions of all lenses.

Mathematical validity alone does not establish that a shared RGB sampling
window fits the executor's memory cap. `TcaModel::sampling_bounds()` supplies
continuous minimum/maximum channel factors and a spatial Jacobian upper bound
over source radius 0–1. It includes green and all polynomial critical points;
linear segments are checked by explicit index, including adjacent floating
point knots whose midpoint can round to an endpoint. Sony's inverse-domain
bound uses the minimum of `D(r)+r*D'(r)` across every retained interval and
both constant tails, with a conservative downward roundoff allowance before
division. No sampled grid is used to certify these limits.

For a centred reference, a 128×32 output tile's per-axis source span is bounded
by the combined spatial Jacobian times `hypot(127,31)`, plus that reference
half-axis times the maximum channel-factor difference. The caller adds the
interpolation tap margin, checks finite bounds, caps each dimension to the
already-admitted active image, and compares against the actual shared-window
budget before publishing Ready/APPLIED. These integral byte counts remain
exactly representable in `f64` under the existing image limits. Failure of
this optional TCA admission must downgrade only TCA and re-evaluate the normal
distortion/vignetting path. This avoids rejecting the whole RAW late in
resampling because of an extreme but finite coefficient table.

Vignetting gains are applied to demosaiced source camera RGB before any
geometric interpolation and before the color transform. The radial gain is
identical across RGB. Source positions can extend outside the recommended crop
into active-area padding; the spline endpoint rule keeps those gains bounded.
The supported gain envelope is 0.125–64, and zero, negative, nonfinite and
out-of-envelope gains are rejected. This is a radiometric gain, not a rendered
JPEG brightness curve.

The implemented gain interpolation follows RawTherapee: interpolate the
already-converted node gains. The fixed darktable implementation instead
interpolates attenuation and divides by it. Those operations agree at nodes
but can differ between nodes. Neither code nor documentation assumes complete
pixel parity between the two applications.

An accepted identity model is ready but inactive. `LensExtras::has_ready()`
supports metadata/policy reporting; `has_active()` requires a non-identity
ready model. Identity models do not independently request another geometry
backing file or interpolation pass.

## Bounds and source binding

Metadata is obtained from the same decoder-owned raw IFD or MakerNote
ImageProcessing IFD as the selected source image. No EXIF lens-name lookup or
camera menu flag supplies these coefficients. Retained arrays have at most
32 Sony CA entries, 16 Sony gain entries, 31 Fuji values or six Olympus values;
unknown layouts are rejected before a model-sized allocation.

The existing RAW container parser's metadata budgets remain in force. The new
handoff performs no extra file reads and retains only these bounded arrays.
The model has no full-frame coordinate map. Main-pipeline disk and working-set
admission, cancellation, crop/orientation, source-state policy and cache
versioning are described in `RAW-OPTICS-CORRECTIONS.md`.

## Reproducible numerical evidence

`tests/public-fixtures/embedded-optics-numerical.json` contains parameters,
original numeric TIFF representations, absolute source offsets, exact field
bytes, original RAW SHA-256 values, provenance, and numeric expected results.
No RAW sensor payload or preview is committed.

| Public raw.pixls.us sample | Relevant coverage |
| --- | --- |
| 6736, Sony ILCE-6700 | Padded 11-node Sony tables; nonconstant red/blue CA |
| 4731, Sony DSC-RX100M2 | Independent 11/11/16 distortion/CA/vignette counts |
| 1178, Fuji X-T20 | 11-node layout, signed rationals, implicit CA centre |
| 3914, Fuji X-T4 | 9-node layout with positive first radius and inserted centre |
| 1993, Olympus E-M1 Mark II | Six actual float32 CA coefficients; no vignette model |

`tests/oracles/embedded_optics_reference.py` reads each file's selected metadata
with an enforced 32 KiB cumulative read budget; the five actual reads range
from 690 to 2,900 bytes. Original whole-file SHA-256 verification is a separate
streaming operation. It computes Sony's combined formula directly in output
coordinates, uses 90-step bisection for Fuji's inverse (independent of the Rust
analytic solution), and uses the darktable Olympus source-radius formula.
The fixture covers 150 coordinate scalars and 16 gains, including active-area
positions outside a reference crop.

```sh
python tests/oracles/embedded_optics_reference.py --source-root /path/to/research
python tests/oracles/embedded_optics_reference.py
cargo test --offline --test embedded_optics
FILM_OPTICS_FIXTURES=/path/with/five/originals cargo test --offline \
    --test embedded_optics -- --ignored --nocapture
```

The ordinary integration suite also compiles the actual vendored parser tests
and checks all-channel autofill on an interior/edge grid. The opt-in real-file
test compares `decode_dummy`'s actual metadata handoff with independently read
original parameters. Numeric model agreement and successful handoff do not
establish physical flat-field uniformity, fringe removal, or support for every
lens. The production image tests and their measured limits belong to the main
optics validation report.

## Pinned primary references

- [RawTherapee `rtengine/lensmetadata.cc`, 64c2d90a3248c7ad41842d9162368858b61aac6a](https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/lensmetadata.cc): Sony/Fuji tables, source gains, centre/radius normalization and Olympus shading limitation.
- [darktable `src/iop/lens.cc`, 78f66f802183c6d241656f82b03ce6c10c1724d8](https://github.com/darktable-org/darktable/blob/78f66f802183c6d241656f82b03ce6c10c1724d8/src/iop/lens.cc): metadata model version 2, especially the Olympus source-radius composition.
- [darktable `src/common/exif.cc`, same commit](https://github.com/darktable-org/darktable/blob/78f66f802183c6d241656f82b03ce6c10c1724d8/src/common/exif.cc): known manufacturer layouts and field extraction.

Filmroom and the vendored model work are GPL-3.0-or-later. The committed public
parameter evidence is from the five CC0-1.0 original samples listed in the
fixture. The separate public E-M10 flat-field images used during research have
no per-file redistribution licence in their forum post and are not included
as new repository assets.
