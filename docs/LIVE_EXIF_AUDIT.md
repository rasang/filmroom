# Live Photo EXIF audit (2026-11-10)

Compared Filmroom dev `5c2b2d9` with the reconstructed official DJI Mimo 2.12.8 APK
SHA-256 `33aee14572993b62146e9daea225ae4a0ab7d36fbfb7878dcb08d14cf8dd111c`.
This is a static format comparison, not gallery playback certification.

## Confirmed EXIF fixes

* Xiaomi: `MiLivePhotoTools.muxLivephoto` sets `livePhoto=1`. The Xiaomi EXIF
  dictionary defines it in ExifIFD, tag `0x9A01`, TIFF BYTE (type 1). Write it in
  the final rebuilt EXIF, not before `Jpeg.clean`, which discards old EXIF.
* Oplus: the OLive creator sets `UserComment` to lowercase `oplus_8388608`.
  Filmroom previously used uppercase `Oplus_8388608`; the writer and validator
  now agree with Mimo's text. Other OLive XMP differences are not changed here.

## Other brands and non-EXIF metadata

* Vivo single-file: its XMP fields and MP4 extension marker are separate from
  EXIF. They must not be treated as missing EXIF tags. No new required EXIF tag
  was established in the inspected muxer path in this pass.
* Vivo paired-file: 2014 pairing information is in JPEG/MP4 trailers, not EXIF.
* Honor: the inspected `LivePhotoUtils.encode` concatenates image/video and
  appends a trailer. Its known trailer differences are not EXIF fixes.
* Samsung: Filmroom uses SEF, whereas Mimo routes Samsung through OLive. Adding
  Oplus EXIF to SEF without changing/validating the format is not justified.

## Ordinary capture metadata is a separate omission

The app already has `PhotoMetadata`, but Live export forwards only capture dates.
The final packager deliberately rebuilds EXIF. Make/model, exposure, aperture,
ISO and focal length are therefore not currently propagated into Live exports.
The patch now forwards validated camera/exposure/lens facts through a bounded
allowlist into the final metadata writer, including make/model, aperture, shutter,
ISO, focal length and exposure compensation. Missing values remain missing.
Dates keep their existing dedicated path; validated GPS fields are preserved.
Output dimensions/orientation/sRGB replace old values. Freeform UserComment is
not copied because it may contain stale vendor flags. Vendor UserComment stays controlled by the selected format.
No post-packaging rewrite is performed, preserving video offsets and MPF sizes.

## Verification

28 platform-free core JUnit tests passed with the Kotlin compiler, including
all-profile packaging/extraction, dates, vivo pairing and the new Xiaomi EXIF
validation/mutation checks. Full Android Gradle/lint was not run in this recovered
environment because the Android SDK and previous Gradle cache are unavailable.
