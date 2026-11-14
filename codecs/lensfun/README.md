# Pinned full Lensfun database

`database/` contains all 56 original schema-2 XML files from the pinned archive
in `PROVENANCE.json`. Their contents are unchanged; every file has an exact SHA-256.
They contain 1,059 camera records, 52 mount definitions and 1,580 lens records.

| Calibration present | Lens records |
| --- | ---: |
| Distortion | 1,538 |
| Transverse chromatic aberration | 1,021 |
| Vignetting | 717 |
| All three | 591 |
| TCA or vignetting without distortion | 39 |

These are database records, not unique physical lenses or claims about every
RAW file. Some records describe different calibration sensors of the same
lens; others describe a compact camera's built-in lens or an attachment.

## Build-time compilation

From `codecs/`:

```sh
python3 tools/generate-lensfun-profiles.py
python3 tools/generate-lensfun-profiles.py --check
```

The generator writes `src/lensfun_profiles.rs` and `DATABASE-STATS.json`. Android
uses these static arrays and sorted name indexes. It does not load the XML or
add a native Lensfun/GLib runtime. Generated Rust source size is not APK size.
The original XML and the large generated source are retained for reproducibility.

The generator preserves camera and lens aliases, private and public mounts,
directed mount compatibility, camera variants, crop factors, aspect ratio,
projection type and real-focal values. No operation requires the presence of
distortion data. The 30 fisheye/panoramic/stereographic records remain present;
the correction pipeline preserves their original projection.

The fixed Lensfun 0.3.4 XML loader replaces an earlier calibration at an equal
float32 key with the later value, retaining the original slot. The generator
follows that rule: five duplicate distortion keys and 95 duplicate TCA keys
are replaced. All original lines remain in the unchanged XML. Linear TCA is
represented by the exactly equivalent poly3 terms with zero higher orders.

`approved.xml` and the RF16 constants are retained solely as compatibility
fixtures for the previously validated route; they no longer define the full
database inventory.

## Identity and selection

`src/lensfun_database.rs` performs exact normalized lookup. Case, whitespace,
typographic hyphens, equivalent aperture spelling and explicit XML aliases are
accepted. It does not rank partial names or edit-distance matches. Known brand
spellings such as Nikon Corporation and Nikon, or Lumix and Panasonic, are
equivalent. The pinned database's `Micro 4/3` and `Micro 4/3 System` spellings
refer to the same mount standard.

An `I/II` record explicitly shares one calibration between those generations,
so its index includes the unsuffixed first-generation name, `I`, and `II`.
Other generations and converter suffixes are not discarded. Different actual
lens models remain ambiguous. Alternate calibration crops of one model can
share a specific XML alias even when their primary model names differ.

The matcher requires a known, unambiguous camera crop and compatible mount.
It prefers the closest supported calibration crop of the same model; it does
not use an APS-C calibration across an otherwise uncalibrated full-frame
outer area. A database-declared adapter compatibility permits an independently
identified lens; it does not establish an identity for an unnamed lens.

An unnamed lens can be identified only by a fixed camera's private mount and
actual focal length. Separate converter-only records still require an explicit
combination identity. A combined fixed-lens family which retains the generic
`fixed lens` alias, such as GR III plus GW-4, instead has discrete optical
groups: 13.8 mm and 18.3 mm must match exactly as float32. The pipeline must
select only the matching group's distortion, TCA and vignetting rows and must
not interpolate across those attachment groups.

## Independent verification

`tests/lensfun_database_oracle.py` loads every original lens record separately
through the unmodified official native Lensfun 0.3.4 C API and official schema
converter. Its fixture contains all 1,580 native focal/aperture ranges, covering
6,320 float32 values. Rust tests compare the compiled table against those
independent values, not against a second call to the generator.

```sh
python3 tests/lensfun_database_oracle.py \
  --library /path/to/liblensfun.so.0.3.4 \
  --converter /path/to/official/xml_converter.py --check
```

Registry tests exercise the full indexes, alternate crop records, manufacturer
and model aliases, attachment conflicts, private fixed-camera mounts and all
camera/lens identity combinations required to reach the complete inventory.
These are lookup/parameter checks. Actual operation admission, original RAW
geometry, embedded-parameter precedence and real-image optical verification
are separate pipeline checks.
