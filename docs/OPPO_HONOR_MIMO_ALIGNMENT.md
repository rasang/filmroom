# OPPO and Honor Mimo alignment

Reference: restored DJI Mimo 2.12.8 APK SHA256 `33aee14572993b62146e9daea225ae4a0ab7d36fbfb7878dcb08d14cf8dd111c`.

## OPPO

- `MotionPhotoOwner` is the actual caller's `Context.packageName`, not `oplus` or DJI's identity.
- Clean generated input uses `OLivePhotoVersion=1`, matching `PrimaryXmpInfo` default.
- Do not invent `MotionPhotoFeatureFlag=1`; the recovered writer does not write this field.
- Existing lowercase UserComment and container/MPF handling remain.
- `VideoLength=0` follows Mimo's clean-input default. The actual video length remains in the Container item. The Mimo decoder uses that length when this auxiliary field is zero; an independent XML reader regression checks the same fallback. Gallery behavior still requires device verification.

Evidence: `olive_editor/creator/a.java` writes `oplus_8388608`, sets owner; `olive_decoder/xmp/PrimaryXmpInfo.java` defaults version to 1; `olive_editor/writer/a.java` emits the owner/version properties without feature flag.

## Honor

The recovered `CaptureLivePhotoManager.l()` constructs `LivePhoto`, sets only photo/video, then encodes it. `LivePhoto` defaults are frameIndex=0, start=0, duration=1000. It does not set the real frame index or clip duration. Thus this alignment writes exactly three padded 20-byte fields:

- `v2_f00`
- `0:1000`
- `LIVE_` plus (MP4 length + 20)

No time-unit inference is needed or made. The JPEG cover remains the app-selected image, as in the input-file-driven Mimo operation. This does not prove that the gallery will play the whole generated clip; real-device verification is needed. Retaining `HONOR_LEGACY` enum avoids breaking existing API identities; it now emits Mimo v2 metadata, not the old v6 candidate.

## Validation

30 platform-free JUnit cases pass, including literal 60-byte Honor comparison, OPPO package injection rejection, all-profile video extraction and EXIF preservation. Android SDK integration build and vendor gallery playback have not been run in this environment.
