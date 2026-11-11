# Effect preview regression

`EffectPreviewStageTest` runs Android View measure/layout on Robolectric API 35.
It reproduces why changing a child's LayoutParams in `onSizeChanged` is too late
for that traversal, then covers the replacement in a weighted portrait root,
stage resize, repeated landscape/portrait changes, and incoming/outgoing pages.

`test-result-letterbox.py` compiles and draws the production final-frame GLSL on
an offscreen EGL GLES2 context. It needs Python 3 and Mesa/EGL, no pip packages:

```sh
EGL_PLATFORM=surfaceless python3 tools/effects/test-result-letterbox.py
```

This checks actual pixels for both orientations of aspect mismatch and the
matching export aspect. It is host graphics coverage, not vivo device playback
or MediaCodec export validation.

Device regression: enter viewfinder with a 4096×3072 landscape result, replay
several times, switch to portrait and back, show/hide original-Live audio/pair
controls, rotate or resize the window, and export. The preview must remain
contained and centered with no repeated edge rows/columns at the end; exported
frame dimensions must retain the result's landscape aspect.
