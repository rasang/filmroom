# Post-Beta2: restore upstream BW neutral filter data

## Root cause and correction

The first BW integration imported the measured profiles but omitted their matching neutral-filter database additions and disabled database lookup for all BW pairs. Thus 5222 + colour paper used stored generic manual C0/M65/Y55, producing the reported orange cast. The initial diagnosis correctly observed missing *bundled* entries, but incorrectly generalized this to missing *upstream* entries. The proposed “uncalibrated combination” warning was withdrawn, not shipped.

The correction appends 38 previously missing entries from official Python dev commit `6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507`, blob `a9e8b65aa0b3cc7c3054bd1bc26f986af4169920`, `src/spektrafilm/data/filters/neutral_print_filters.json`. Existing 160 entries are retained exactly as parsed numeric values. The official dev changes 128 historical entries as well; those changes are deliberately NOT imported, preserving existing colour output. The combined file is therefore not claimed byte-identical to a single upstream version.

Added entries: Double-X/Tri-X under each of the eight existing print media (16) and all 22 film entries under 2302. All are for TH-KG3. The upstream reversal entries [0,50,50] are placeholders, not certification of a reversal-print workflow; reversal remains direct-scan-only. The original upstream licensing remains in the bundled license materials; these are selected original data entries, not invented calibration.

For 5222 + Fujifilm Crystal Archive Type II + TH-KG3, the original entry is C0, M94.3863254258598, Y115.91640660609802. The existing manual control range 0–200 already supports this domain. Automatic lookup uses the original f64 table path and does not write over saved manual values. The stored database toggle remains authoritative. BW combinations with an illuminant absent from the table continue using their stored manual filter values, with database capability unavailable. No grayscale postprocessing, profile synthesis or combo disabling is added.

## Compatibility and evidence

All eight colour-print profiles were independently compared against that Python dev version. Every numeric scientific field, including fitted curve parameters, is identical to the bundled profile. The only data difference is the nonnumeric fitted-model type spelling `cdfs` versus `norm_cdfs`. No paper profile was replaced.

Using the actual frozen Beta2 native library and passing the original Crystal filter values manually changes the same neutral-ramp midtone RGB from (0.6842,0.2649,0.0464) to (0.2835,0.2783,0.2754). This verifies the direction of correction without changing engine algorithms; it is not a promise of perfectly neutral whole-density-range output. Genuine paper curves can retain tone-dependent colour differences.

The source correction restores normal database-driven runtime selection (not hardcoded per-photo overrides). Focused tests cover nine paper entries, automatic lookup versus explicit original values, absent illuminants, preserved manual selection and values, and the UI table's exact pair availability. New-host JNI and old-colour byte regression results are recorded in the external task handoff; no new APK or release is implied by this document.

Sources:
- https://github.com/andreavolpato/spektrafilm/blob/6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507/src/spektrafilm/data/filters/neutral_print_filters.json
- https://github.com/andreavolpato/spektrafilm/blob/6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507/src/spektrafilm/runtime/params_builder.py

## Focused correction gates

- Native BW mobile suite: 4 passed, including all nine auto-vs-explicit-original filter lookups and complete default-effect BW exports.
- Android host: 16 passed across BW filter availability, BW parameter capabilities, film selection persistence and effect controls.
- New-host JNI: 27 valid routes (5222 print/direct and 7266 direct × nine print media) produced complete finite outputs; all nine invalid 7266 print combinations rejected. Maximum preview/export absolute sample difference 0.00085574 (existing distinct grain sampling layouts).
- All 18 direct-route files and the 5222→2302 default export are byte-identical before/after this correction.
- Four frozen Beta2 colour cases (default effects, clumps, Gold/2383 morph, Provia direct correction) match the new host library byte-for-byte.
- Nondefault development 9/7: auto→manual→auto restores identical pixels; explicit original filter tuple matches lookup within float ABI tolerance.

No new Android ABI binaries or APK were built for this isolated correction. Existing checkpoint identity hashes the entire data tree; old paused jobs cannot silently reuse pre-correction orange intermediate data after the database changes and may require re-enqueue. Local-detail identities include serialized parameters and the data revision. This compatibility behavior was inspected statically; an upgrade/resume test on a real device has not been run.
