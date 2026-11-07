# Frozen QR decoder regression

These are the unchanged failing image and exact QR text from the real beta4
`PresetQrLogoHostTest.realLogoCodesDecodeOriginalSmallJpegPerspectiveAndAlbum`
run at source commit `a3a3f1e7ec8772b8956fa9730a6bd63118807298`.

- Source evidence: `phase-preset-gate-final-a3/qr-artifacts/6-perspective.png`
  and `6-v2.txt` in the beta4 Android release validation directory.
- Image: 600 × 600 pixels, real launcher icon, four-corner perspective transform.
- Text: 730 characters, version 24 / 113 modules, H error correction.
- PNG SHA-256: `46ead17c4f0bdca0d18454f91c749f0ca6970684fa5308bbf0e2766395da6324`
- Text SHA-256: `1d2413e3f1029d24e0251d04817549fe2e0e5d2e4e61eb1d222cd27fbbce5dd3`

Do not regenerate this image when changing the renderer or codec. The regression
must also protect importing existing shared images, not only newly generated ones.
The text must be read without trimming: spaces belong to the Base45 alphabet.
