list3-gainmap.dng is an authored synthetic fixture, derived from this repository's
codecs/tests/fixtures/RGGB.dng. A replacement IFD adds a full-image3-channel,
3×4 analytic gain grid (1+0.1*y+0.2*x+0.1*channel) and exact identity3-plane
WarpRectilinear. No camera photograph, personal metadata or user file is included.
It tests actual JNI import; the separate private sample is supplied only via
FILMROOM_DJI_DNG at validation time and never embedded in source or APK.
