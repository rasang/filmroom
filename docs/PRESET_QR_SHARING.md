# Offline recipe QR sharing

Candidate based on code55 (`54a2bcea`). “Recipe” replaces the UI term “preset”; the
private preset store, saved names, atomic writes, photo state and undo behavior
remain unchanged. This work does not change the app version, signing or native
rendering and does not publish an APK.

## UI and data safety

The single bottle-icon entry opens the recipe list. Saving current settings is at
the bottom; long-press a recipe for Share or Delete. Deletion still requires
confirmation. Accessibility and keyboard users have equivalent row actions.
Selecting a recipe does not apply it: the explicit Apply action remains.

Sharing opens a real dialog over the current screen, with the generated QR and
Share image action. The system chooser receives only that generated PNG, through
the dedicated `/cache/preset-qr-shares/` FileProvider root. No photo, private store,
EXIF, source path, account data or diagnostic file is shared. Existing settings,
including crop/composition and custom date/time, are retained and disclosed in the
scope text. A recipient can read the settings; the checksum is not a signature or
sender authentication.

The list's unfilled scan icon opens the camera-or-album choice. Camera permission
is requested only on choosing camera, and refusing permission leaves album
available. The camera capture activity alone is portrait-locked; import and editor
orientation behavior is not changed. Decoding does not save or apply anything.
Confirmation saves a new recipe; duplicate names never overwrite an existing one.

All scanning is bundled/offline ZXing. There is no Internet or broad photo/storage
permission. External input must be a user-selected content URI. Images are bounded
to 8 MiB encoded, 80 MP / 20,000 px source size, sampled to a <=1536 px long edge.

## V2 immutable wire contract

New QR text is `FILMROOM:2:` plus canonical RFC 9285 Base45. The uppercase prefix
and alphabet keep the entire QR in **alphanumeric** mode. Decoded bytes are:

1. 32-byte SHA-256 of the uncompressed binary payload.
2. One byte: 0 means raw payload; 1 means one raw-DEFLATE stream (`nowrap=true`).
3. Raw or compressed payload. The encoder chooses compressed only when smaller.

The payload contains canonical unsigned variable integers (maximum 32768):

- Fixed-slot schema number, currently 1.
- UTF-8 name length and name.
- Number of changed slots, followed by strictly increasing slot IDs and values.
- UTF-8 extension JSON byte length, followed by those bytes (0 means absent).

`PresetQrSchemaV2` freezes all 111 scalar slots, their paths/types, the full code55
baseline and 72 common string tokens. These include serialization metadata as well
as all 89 editor constructor parameters / 103 recursively editable leaves. **Never
regenerate, append to, reorder, or alter these tables or defaults in place.** A new
incompatible table requires a new transport and preservation of this decoder.

Types: Boolean byte 0/1; Float and Double are big-endian IEEE-754 without rounding;
nullable Double has a 0/1 presence byte; nonnegative integral metadata uses uint;
string 0 is null, 1..72 is the frozen token, otherwise the uint is 73 + UTF-8 byte
length followed by the literal bytes. Non-finite numbers are rejected.

Unchanged slots are reconstructed from the **frozen baseline**, never from the
app's changing defaults. Five nullable fields have explicit frozen null semantics:
medium format, both target aspects, preferred output route and manual imprint date.
Tests inject changed future defaults to ensure these remain null. Only fields that
did not exist in this frozen schema use a newer app's defaults when absent.

Future additive fields use the bounded JSON extension; no frozen slot or existing
parent object may be redefined there. A newer app validates/uses those fields.
An older app rejects unknown fields rather than silently losing them. Current
encoder validation and encode/decode equality checks also refuse any field the
transport cannot preserve. Arrays or incompatible field-type/schema changes need
an explicit protocol evolution, not a silent fallback. A field with a special
legacy migration is not a purely additive default: integration must test the QR
prefill path as well as `EditorState.fromJson`. For example, a later rendering-stage
field may deliberately map old missing data to the legacy stage rather than the
new-editor default. Such migrations need an explicit, tested importer exception;
do not bypass them by blindly inserting the new-editor default.

## V1 compatibility and bounds

Existing `FILMROOM:1:` codes remain readable: canonical unpadded Base64URL of
SHA-256 + one zlib stream containing the full JSON envelope. Missing fields in old
v1 codes continue to use the app's defaults; supplied fields are not overwritten.
Existing nested-version migrations remain supported. Older nested versions may not
smuggle newer explicit fields that their legacy reader would ignore.

Unknown keys/versions/enums, unsupported film/paper IDs, invalid types/ranges,
duplicate keys or slot IDs, noncanonical varints/Base45/Base64, invalid UTF-8,
non-finite values, trailing data and checksum mismatches fail closed. Inflation is
limited to 32 KiB. Legacy input limit remains 2200 characters. New shares are capped
at 1800, below QR version40/H alphanumeric capacity (1852). Oversize shows an error:
no parameter deletion, upload, URL substitution, lossy precision or implicit
multipart fallback. Arbitrary future recipes are not promised to fit one code.

## QR rendering and logo

Generated PNGs have 8 pixels per module, an untouched four-module white quiet zone
and **H** correction (the old implementation used M). The real shipping launcher
asset is centered on a white patch. Patch edge is an odd number of modules, capped
at 17% of the matrix side and at 11 modules: at most 2.9% area. One white module
surrounds the icon. Finder/timing patterns and outer alignment structures remain
uncovered; some versions' central alignment pattern is occluded. H alone is not a
guarantee that arbitrary occlusion is safe.

Every generated image must decode back to its exact payload before it is shown.
If needed, the renderer tries other legal QR masks and smaller logo patches. It
never knowingly returns an unreadable code or drops the logo silently. Common
codes display compactly near 260 dp, within the available dialog width; denser
codes request a larger width. The final width is rounded down to a whole number
of screen pixels per module, avoiding unequal cell widths from fractional scaling.
Export retains the original module-aligned PNG.

Image and album decoding retain adaptive local thresholding for uneven lighting,
with a global-threshold retry if ZXing cannot read it. Dense antialiased perspective
images can otherwise lose finder-pattern detection depending on the data mask,
even at the same QR version. Both paths support inverted images. This is bounded
to four attempts on the same pixels, with no resizing, blur, or relaxed payload
validation. Camera capture still uses the bundled scanner's frame decoder.

## Measured compression

Seven real code55 serialized presets were measured, including the complete
non-default 89-parameter test fixture. Names are Chinese in this comparison.

| Recipe | Old chars, M | Full-JSON dictionary + Base45 candidate | Chosen binary chars, H + logo | Old/new matrix |
|---|---:|---:|---:|---|
| Default | 1298 | 160 | 76 | 137 → 41 |
| Warm portrait | 1326 | 208 | 122 | 137 → 49 |
| Gold street | 1330 | 220 | 116 | 137 → 49 |
| B&W | 1305 | 209 | 112 | 137 → 49 |
| Reversal | 1323 | 199 | 94 | 137 → 49 |
| Medium-format date | 1370 | 265 | 136 | 137 → 53 |
| All 89 changed | 1487 | 928 | 698 | 145 → 113 |

Normal examples lose 90–94% of text and 85–91% of matrix area despite stronger
correction and the logo. The all-changed example loses 53% of text and 39% of
matrix area. Fixed-slot binary was chosen over full-JSON dictionary compression
because it saves another 29–53% of text in these examples, at the cost of an
immutable table and stricter protocol tests. No values are omitted semantically.

## Verification and limits

Host coverage includes literal v1 and v2 golden codes, frozen-schema hash, future
new-field defaults, changed old defaults (including nullable slots), all-field
round trips, 100 high-entropy presets, exact Float/Double tests, corruption/bounds,
48 length/seed combinations through render self-check, real generated PNGs through
album input, compact display/JPEG/rotation/perspective, permission denial,
portrait-lock intent, cancel/back/rotation and store preservation. The release
compatibility Gradle gate includes the compact codec and logo test classes.
It also retains the exact 600px beta4 perspective image that exposed the local
thresholding blind spot, checking both direct pixels and real content-URI album
import independently of any later changes to rendering or payload generation.

The seven PNGs all decode. Normal examples pass 260 px display plus JPEG85,
90-degree rotation and 430 px perspective simulation. The all-changed dense code
passes 360 px/JPEG and 600 px perspective; a stronger 430 px perspective test failed
and is retained in validation evidence. It may need a closer, steadier camera view.
These are Robolectric/ZXing image tests, **not a physical phone camera test**. Actual modal ImageView pixel tests at 360 dp/mdpi pass at 245 px for
the default code and 242 px for the all-changed code. The earlier 264 px fractional
scaling failure is fixed by integer module scaling. Final integration tests and
device acceptance are reported separately. Full merged Gradle/lint/package checks remain an integration step.
