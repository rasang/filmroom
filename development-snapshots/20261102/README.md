# Development backup — 2026-11-02

This is a **source recovery branch**, not a release or an integrated/tested build.
Public parent: `83f1c33a0dc799eb9831f198be2ea327fb930641`.
The working source is a filtered snapshot of private Beta4 `47ef9f80d70e15cc7ca0fd664d2dc8eac1ce64dc`.
It is **not byte-identical** to that private tree: native `.so` libraries were deliberately omitted (and stale public binaries removed). The original private recovery archive retains the matching native references. Build native libraries from the matching source before attempting an APK; this backup itself has not been built or phone-tested. No new RAW/photos, SDK/oracle code, credentials, build output or runtime logs are included. Existing public files are inherited from the public parent unchanged unless listed in this snapshot.

## Independent work lines — not integrated

- `samsung/changes.patch` plus `samsung/new-files/`: apply to exact Beta4 baseline above. Six modified and six new own-code files. Snapshot taken 2026-11-02 14:24 UTC. Six focused colour unit tests passed on host; full release/colour regression was still running. Samsung S23/S23+/Expert RAW complete colour validation, old user RAW regression and phone verification are **not certified**. Includes third-party notices; Adobe SDK remains external oracle only and is not distributed here.
- `rw2/changes.patch`: complete 30-file Panasonic RW2 line, exact base `1a05595fa4fda0a9676a0a38466ab727cc5ed6b6`, result `32d78746d6de2737844e7d6b8326478c36098494`. Owner reports 13 real samples host-checked and 78 Rust tests; no new phone/APK validation. High-resolution/pixel-shift smoke testing remains pending. Rawler modifications retain upstream licence notices.
- `contour/contour-v3.patch`: exact Beta4 baseline. Three production files and two portable tests for early export contour thumbnails/cache cancellation. Patch applicability checked by owner; v3 **not compiled or tested** at snapshot time. Ad-hoc harness files are archival source only, use external fixtures/prototype and are not part of app build.
- `5222/changes.patch`: approved asymmetric 5222 text, commit `05c1bd6f1443cf1fa4864cbfe2b14d44c44a3956`, separate patch from exact parent `47ef9f80d70e15cc7ca0fd664d2dc8eac1ce64dc`. Owner reports 24 tests passed / 2 skipped; not merged here. Approved authored UI asset is carried inside this binary patch.

Do not apply all patches blindly: their baselines overlap and integration needs review and regression. No installation package or GitHub Release was produced by this backup.

Public backup omits private review-source identifiers, screenshot hashes and sampling reports. Authored asset hashes and licences remain. The 5222 patch has provenance-only redactions; runtime code/asset bytes are unchanged.
