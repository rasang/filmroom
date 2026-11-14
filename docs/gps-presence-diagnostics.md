# GPS presence diagnostics

This change adds evidence, not an OEM GPS-loss fix. Existing permission, picker
redaction, metadata validation and export policy are unchanged.

- Import: existing media-location permission, selected/original/fallback route,
  bounded fallback reason, GPS tag presence seen by the metadata parser, and
  validated snapshot tag presence.
- Ordinary and Live export: snapshot presence and best-effort output image
  readback. Readback occurs on IO; it does not decode image pixels.
- Fields are enums/booleans only. No coordinate values, provider authorities,
  filenames, paths, URIs or exception text are added.
- COORDINATE_TAGS means the four latitude/longitude/ref tag names are present,
  not that values are correct. sameTagSet compares GPS tag names only.
- ORIGINAL is an attempted stream route, not proof that a provider supplied
  unredacted bytes. NONE at import cannot distinguish an originally ungeotagged
  photo from provider redaction. READ_FAILED is not equivalent to no GPS.
- Output readback is additional provider/EXIF I/O. Failures only affect diagnostics;
  Live readback runs after commit and must not convert a saved result into failure.
- A snapshot/output tag mismatch locates a write/readback tag-set discrepancy;
  added output tags can also make sameTagSet false, so false alone is not loss.
  Matching tags cannot prove coordinate equality or system-gallery indexing.
  Coordinate equality is asserted in opt-in public-fixture host tests, never logs.

Host tests simulate the provider and do not prove vendor-gallery behavior.
