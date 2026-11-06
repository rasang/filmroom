# Bounded 12/14-bit CFA DNG

Development scope: single-image, two-by-two Bayer CFA DNG from any maker.
There is no Make/Model whitelist; admission is structural, never brand-based. The adapter now accepts
12/14-bit unsigned samples in either MSB-first packed uncompressed strips/tiles or
lossless JPEG strips/tiles. Existing 16-bit CFA, LinearRaw and JPEG XL routes remain
separate.

The newly admitted JPEG layout is explicitly bounded: SOF3, two interleaved
components with 1x1 sampling, JPEG width times two equal to sensor width, exact
stored block height and precision, predictor 1, zero point transform, one scan,
and either no restart interval or complete JPEG-row restart intervals. APPn
segments are skipped only within the cumulative 64 KiB header limit. All entropy
symbols, restart markers and EOI are validated. Only uniform zero/one final
fill bits after the exact sample count and up to 4095 zero TIFF alignment bytes
after EOI are accepted; missing entropy/EOI, mixed fill, nonzero or excess tails
are rejected.
Unsupported layout is an error, never an embedded-preview or resize fallback.

JPEG rows are decoded to a 0600, exclusively created, immediately unlinked,
preallocated u16 sensor backing store. The same input descriptor used for probe
is used for decoding. The ordinary bounded halo-based developer then reads that
store. Admission counts width * height * 2 additional disk bytes and bounded
row/decoder memory. The 52,000,000 encoded-sensor pixel and 32,768 dimension guards
remain in place. A cropped mode does not reduce the encoded-sensor guard.
The production output guard is retained until all decoding and writes succeed.

Packed blocks have byte-aligned rows. Their declared extent must contain all
required row bytes and lie within the input file. Trailing storage padding is
not sensor data and is not read or allocated. Legacy 16-bit extent policy is
unchanged;
directory endian does not change packed bit order. FillOrder other than 1,
floating/signed samples and predictors are rejected. Black-level repeat grids,
ActiveArea, DefaultCrop and orientation pass through the existing validated
sensor-development contract. The default WhiteLevel is 2^BitsPerSample - 1.

## Explicit limits

- Multi-image pixel-shift DNG is rejected because it needs explicit frame
  registration/fusion semantics. No first-frame-only success is reported.
- One-component LinearRaw monochrome DNG is rejected. It is not disguised as
  Bayer, white-balanced as color, or passed through a three-channel color matrix.
- Alternate JPEG component/predictor layouts remain unsupported until
  independently validated.
- The existing basic D65 ColorMatrix/AsShotNeutral developer is retained. This
  does not claim full Adobe profile rendering or matching the camera JPEG.

## Verification

`cfa_precision_dng` generates nonconstant sensor ramps and compares 12/14-bit
packed and JPEG decoding with established u16 development, all four Bayer
patterns, all eight orientations, shifted active area, odd default crop offsets,
2x2 unequal black levels and both TIFF endians. It also checks additional spool
disk admission, cancellation, corrupt/truncated entropy and existing-output
preservation, padded strip tails, and partial-edge tiles across halo boundaries.
`dng::cfa_storage::tests` checks bit packing, category-16 uint16
reconstruction, component mapping, restart handling, every byte truncation,
unsupported JPEG headers, exact extent and callback failure propagation.

`dng_cfa_audit` is an offline host-only audit executable that exports the actual
bounded adapter's normalized active sensor samples for comparison with LibRaw.
It is not an import fallback. Public sample and independent-oracle validation
results must be recorded separately; test design alone is not a passed result.

## Aggregate stored-work bound and measured controls

New 12/14-bit CFA tile layouts also bound the sum of padded tile samples to twice the existing 52,000,000-pixel ceiling. Repeated compressed offsets cannot bypass this work bound. The synthetic repeated-offset regression and ordinary partial-edge tile parity tests pass; the legacy 16-bit/LinearRaw policy is unchanged.

Pentax K10D and K-1 Mark II DNG controls compare exactly over 25,887,104 normalized active sensor samples against LibRaw-decoded sensor data. This comparison uses the adapter's black/white values and therefore does not independently certify those metadata choices or manufacturer rendering. The Sigma tiled-DNG production control produces the expected finite output and a Linux child peak RSS of 41,404 KiB. These results do not imply support for untested DNG variants or Android devices.

## Companded byte CFA and large U16-container strips

The generic uncompressed Bayer-strip path also accepts 8-bit code values only
with a complete 256-entry SHORT LinearizationTable and an explicit WhiteLevel.
The existing row reader maps codes through the table before black subtraction
and white normalization. It does not treat the byte values as linear light,
normalize them by 255, or use a camera-model exception. Other unverified CFA
linearization layouts and byte-code JPEG/tiles remain rejected.

A 16-bit TIFF CFA container with a non-tiled JPEG strip above the legacy 8 MiB
encoded or 4 Mi-sample block limit now uses the bounded row/spool route rather
than increasing those legacy limits. The scan must have the supported SOF3
layout and 12, 14 or 16-bit precision. Its actual SOF precision sets the initial
and restart predictor midpoint; decoded values are not shifted to fill 16 bits.
Existing small-block and tiled U16 routes are unchanged. Original 12/14-bit
container paths still require matching scan precision. The global 52 MP and
32,768-dimension guards, complete entropy/EOI validation, private storage and
memory/disk admission remain in force.

Validation for this extension includes release all-targets (188 passed, 8
explicit legacy opt-in ignores), every Bayer phase/orientation and both TIFF
endians against an expanded-u16 control, partial final strips, malformed LUTs,
wrong scan precisions, a complete >4 Mi-sample spool-to-RGB success case,
resource rejection, cancellation and existing-output protection. Representative
M8, M9 and M240 normalized samples exactly match LibRaw-decoded sensors; their
full RGB output also exactly matches the unchanged developer supplied with
those independent sensor values in u16 controls. Normalization still uses the
adapter's black/white metadata, and this is not a claim of manufacturer-JPEG
color matching. Six existing Ricoh/Pentax/Sigma DNG controls remain exact.
The real Linux JNI regression additionally covers import, JPEG export,
metadata/orientation and input preservation for byte-LUT and large-strip DNG.
No physical Android device execution is claimed by these host checks.

### Identity look-table metadata

The legacy matrix developer now admits an IFD0 ProfileLookTable only when the
whole table is a verified no-op: LONG/count3 dimensions (hue>=1, saturation>=2,
value>=1), FLOAT data with the exact bounded dimensions product, and every triple
exactly finite `[0, 1, 1]`. The existing 64 KiB per-tag / 512 KiB total metadata
bounds remain unchanged. ProfileLookTableEncoding must be absent (DNG linear
default) or LONG/count1 value0; other encodings remain unverified and rejected.
Partial metadata, a raw-IFD override, malformed payload or a nonidentity table is
rejected, including nonidentity tables without an explicit encoding tag. This
is not implementation of arbitrary DNG look transforms or a model whitelist.

Public Leica M10-R sample7853 was previously rejected solely for explicit
ProfileLookTableEncoding0 despite its identity table. Its 7872x5208 sensor is
below52MP; its 16-bit-container/14-bit-SOF3 large strip uses the existing bounded
CFA spool route. No pixel, memory or disk limits were raised.
