# vivo single-file alignment

Reference: Mimo 2.12.8 APK SHA256 33aee14572993b62146e9daea225ae4a0ab7d36fbfb7878dcb08d14cf8dd111c, bundled vivo SDK 2.4.2. Recovered `LivePhotoProcess.pack`, `MediaExtendInfo`, `MediaExtendInfoForVideoBoxFormat` (fallback control flow checked), and `MotionPhotoMuxer`.

For the implemented version-1 single-file profile, append a terminal MP4 `uuid` box with 16-byte usertype `vivoMediaExtInfo`. Payload is `vivo` + UTF-8 JSON containing `com.android.camera.livephoto=<28-byte marker>` (computed as `motionphoto0001` padded to 28 chars) and `version=2014`, big-endian JSON length, `cameralbum!`, big-endian 47, the same 28-byte marker, four 0xff bytes and the 11-byte signature. JSON key order is not a semantic constraint. The unit fixture spells the bytes independently of the implementation.

XMP video length includes this complete box. Existing terminal recognized vivo extension is replaced, not duplicated; unknown/malformed metadata is rejected. The verifier validates the complete MP4 including the new box, then reports the media prefix range to preserve the SDK's existing extraction/hash contract (as for paired output). Input files are unchanged.

Identity: the SDK's package-to-source table does not contain `dev.filmroom`, returns -1 and its muxer omits nonpositive sources. We therefore omit `VMotionPhotoSource`, rather than using DJI's 12. We also do not claim the proprietary MediaKit version `1.0.0.0-portrait_live-0213`: this is an independent implementation. These identity/version differences are intentional and remain documented. The implemented media protocol is version 1; no unsupported later version is claimed.

No true-device gallery playback result is implied. Test fixtures check framing, final video extent, original media extraction, corruption rejection and replacing an already-appended marker. Android integration and physical vivo gallery testing remain required.
