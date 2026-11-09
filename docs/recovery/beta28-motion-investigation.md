# beta28 motion regression: confirmed restoration fixes

- Compared input copy/retention, extractor, preview/decoder ownership and frame gates to beta27 DEX. No confirmed restoration mismatch in these paths yet.
- An apparent movieLength `break` mismatch in ordinary JADX output was disproved by fallback/raw DEX: invalid size returns null in beta27. Do not change it based on JADX high-level output.
- Real generated JPEG+AVC fixture passes `EffectMotionInput.prepare`, not just extractor; output equals original AVC bytes. ffprobe sees H.264 64x48, 5 frames, 0.5 seconds. This is a generated fixture, not the user's file or a handset playback test.
- Confirmed GLES2 shader restoration regression in beta28: blur kernel used integer offsets with vec2 multiplication. Host Mesa GLES2 rejected it; floating-point offsets compile. Raw DEX later confirmed beta27 uses int-to-float and append(F), whereas beta28 omitted the conversion and used append(I). Ordinary JADX Java output hid this distinction.
- Fix makes the generated kernel offsets floats. Test covers all 21 generated offsets; input integration covers success, cancellation cleanup and still-file rejection.
- Initial input/shader run: 4 tests passed. Later fallback run: 16 passed; typed diagnostic persistence tests also passed. Final aggregate verification follows the latest raw-DEX corrections. No actual handset decoding/playback tested.

## Real public device samples

Public JPEG Motion Photo samples from NightMean/MotionPhotoSamples and immich-app/test-assets were tested with the beta28 compiled extractor (not modified production input code). Extracted videos were actually decoded by host FFmpeg:

| Sample | Extraction | Decoded frames |
| --- | --- | --- |
| Samsung Tab S9 | pass | 31 |
| Samsung A34 5G | pass | 72 |
| Samsung S20FE | pass | 47 |
| Pixel 9 Pro XL Ultra HDR | pass | 46 |
| Pixel 6 Pro | pass | 40 |
| Samsung One UI 5 | pass | 88 |
| Samsung One UI 6 JPEG | pass | 75 |
| Pixel 8a HDR | rejected | MP4 has two video tracks; existing beta27 validator requires exactly one |

These are public real-device samples, not the user's problematic files. Host extraction and FFmpeg decoding do not verify Android MediaCodec/SurfaceTexture playback.

## Additional bounded fallback protections

- Bitmap preview's GPU blur failure formerly called `resetMotionFrame`, replacing the just-uploaded sharp video frame with the original still while returning success. It now permanently disables that renderer's GPU blur and generates the blur from each current bitmap frame on CPU. Native-raster test confirms changing red/blue video frames remain changing after a simulated GPU failure. This path may be slower than hardware blur.
- A Surface decoder which has not delivered its first frame after 1.5 seconds now gets one attempt through the existing bitmap decode fallback, rather than going straight to permanent static fallback. The bitmap attempt has its own existing 1.5-second budget; playback itself stays 3 seconds. Unsupported sources may still fail both routes.
- These are independently valid failure-path improvements. Raw DEX plus the GLSL compile failure and reset-to-still path now establish a concrete restoration regression; the reported handset is still not directly tested. Six new fallback tests pass; combined input/shader/fallback run is 16/16.

## Confirmed raw DEX corrections

- Original beta27 blur lambda: `add-int/lit8 → int-to-float → StringBuilder.append(F)`; beta28: `add-int/lit8 → StringBuilder.append(I)`. This produces invalid strict-GLSL-ES-1.00 scalar types and triggers the reset-to-original-still path. The initial interpretation based on JADX Java was wrong; raw DEX supersedes it.
- `EffectExportResolution.desired` also used source aspect where beta27 used final result aspect. Restored both sceneAspect/opticalLayout inputs to result dimensions and added opposite-orientation and film-rail canvas regressions.
