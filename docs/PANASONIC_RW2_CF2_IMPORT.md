# Panasonic RW2 CF2 / new-generation prototype

Independent follow-on to the ordinary RW2 prototype at `2faa5c6c`; no automatic Beta integration or release. The content-based `.RW2` adapter now validates and decodes the evidenced v8/CF2 class, rather than routing to an embedded JPEG.

## Intended admitted class
- CF2 v8, nominal12/14/16-bit; decoded u16 sensor values remain unchanged.
- Typed count-prefixed metadata with bounded zero reserved padding; ordered first-match Huffman lookup preserves real shadowed tail entries.
- Identity sensor gamma and zero shift-down metadata; unsupported gamma/shift classes fail closed.
- Positive even contained, disjoint strips covering the complete sensor; checked compressed ranges, <=256 strips, <=52MP sensor domain. Serial exclusive writes replace unsafe parallel whole-image aliasing.
- Bit reader never consumes synthesized padding or reads outside the declared entropy extent. Cancellation checked before allocation and each reconstructed row pair.
- Current actual fixtures: GH6, S5IIX, G9II, GH7, S1RII12bit and S1RII14bit Live Composite/orientation8. All six have2×1 strips. Vertical-strip layouts require synthetic tests and remain without actual-camera coverage.

## Calibration and memory
For new-v8 modes only, use validated file channel black/LinearityLimit tags rather than a model's static white override. Apply the generic officialLibRaw0.22.2 limit conversion4095→4079 and16383→16319; other values unchanged. Requireblack<white and map levels through sensor-origin CFA. Clear cloned v8 profile level overrides before RawImage construction. Existing WB, camera matrices, active/crop and development policy remain in place. Previous versions retain their prior levels and outputs.

Keep the existing256MiB camera-RAW policy and shared lease. Decoder admission charges baseline + full source mapping + full u16 sensor + conservative1MiB CF2 working set. Development admission separately charges retained sensor + bounded float strip and halos. No whole RGB image is allocated by the production import route; the high-memory full-frame RGB oracle is test-only.

The bounded RW2 metadata parser omits unused embedded JPEG tags0x002e and0x0127 (`JpgFromRaw2`), preserving scope restoration and IFD cursor position. This is an allocation omission, never a JPEG fallback or metadata-budget relaxation.

## Verification contract
Full sensor u16 must match officialLibRaw0.22.2; whole float RGB must match LibRaw sensor injected into the stated Rawler development policy, including independent EXIF orientation transformation. This proves sensor equivalence/development preservation, not equivalence to LibRaw colour postprocessing or independent camera colour certification. Existing seven RW2 output hashes and23 NEF/CR2/ARW cases must remain unchanged. Host JNI/Kotlin and Android ABI compile/ELF evidence are separate from phone validation.

Private execution evidence lives in `/workspace/shared/filmroom-panasonic-cf2-development/`; consult its finalREPORT for completed vs pending gates. No APK is produced by this research task.

## Explicit gaps
v7/12bit remains rejected; nonidentity CF2 gamma/nonzero shifts, pixel-shift/high-resolution domains above52MP, unrepresented model/mode combinations, actual Android runtime memory/cancellation and independent colour accuracy are not certified. The suffix-level UI policy does not imply every `.RW2` encoding is supported.
