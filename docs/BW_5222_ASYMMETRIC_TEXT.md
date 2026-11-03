# 5222 asymmetric continuous-strip lettering

User-directed post-Beta4 change; base `47ef9f80d70e15cc7ca0fd664d2dc8eac1ce64dc`.
The previous artwork used centered lettering. User requested asymmetric placement and
slightly clipped text, not movement of the picture/perforations or a new barcode.

## Exact change

Translate the original47px ink mask (text and short marks) **+320 native pixels**
on a1520px-wide canvas. Old foreground bbox `[172,1359,1348,1395]` becomes
`[492,1359,1520,1395]` after canvas clipping. EASTMAN5222 remains fully visible;
roughly44px of the final numeric tail (about1.5 digit advances) falls outside.
The right short mark also moves off canvas; it is not moved separately or replaced.
No horizontal scaling, vertical clipping, invented code or glyph redesign.
This is a deliberate continuous-strip presentation, not randomized manufacturing metadata.

Body4 and the vertical gray112→88 ink gradient remain unchanged. The generator
reconstructs the original Beta3 RGBA and the published Beta4 v6 material byte-exact,
then translates the same ink mask. The geometric alpha remains byte-exact; the photo,
perforations, window/crop, outer cut lines and matte option do not move.

## Versioned compatibility

New print-positive jobs select v7 with a distinct filename and hash. Both published
v6 black/white contracts and their original payload remain in the registry's legacy
list, along with v4. Frozen queues resolve their own exact material. Direct-negative,
7266, 2302 and the capture-film ownership policy are unchanged. No native/science,
UI layout, app version, APK or publication change.

## Validation

Focused host gate passed at2026-11-02 11:25UTC:26 tests,24passed,
2existing optional external-corpus skips,0failures. All6 material tests ran,
including the private same-photo fixture. Log `/workspace/shared/5222-asymmetric-tests.log`. Tests cover old/current alpha, shifted/clipped bounds,
neutral gradient, both mattes and orientations, cropped/zoomed bitmap-preview versus
float-export equality, three color spaces, real same-photo core identity, v4/v6/v7
paused queue reopen, capture-frame ownership with2302, catalog inventory and TriX.
Private screenshots are test inputs only; no photograph is bundled in assets/source.

Actual runtime before/after reviewed at normal size: shifted line and slight tail
clip are visible; before is the delivered Beta4 v6 gray-gradient frame, not the older
gray-base material. Outputs: `app/build/ui-host-renders/5222-positive-sampled/`.
No aggregate Android gate, new native ABI, device or APK verification is claimed.
