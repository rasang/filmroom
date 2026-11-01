# Android UI / application host verification — 2026-10-31

Final command on the frozen application source:

```sh
source /workspace/shared/toolchains/env.sh
export JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Duser.home=/workspace/shared/toolchains/java-home'
gradle --no-daemon --console=plain testDebugUnitTest lintDebug \
  -PfilmroomHighresFixtures=/workspace/shared/filmroom-highres-fixtures
```

**Result: BUILD SUCCESSFUL; 133 tests, 133 passed, 0 skipped, 0 failures, 0 errors.** Lint completed successfully; warnings remain, not a warning-free claim.

The optional nine high-resolution import tests initially skipped without a fixture property. All were subsequently executed successfully using the existing synthetic fixture generator. Inputs are 12/50 MP synthetic RGB/RGBA PNG and JPEG plus eight EXIF quadrant variants, outside the source tree. No user photograph was copied into test assets or source deliverables.

## UI evidence

`CameraLayoutRenderHostTest` renders the actual Activity through Robolectric native graphics and host Skia: 320×640 and 740×320 at font scales 1 and 2 across seven categories, 28 images in `app/build/ui-host-renders/`. Geometry assertions preserve a substantial preview, at most one scalar slider, and no ellipsized TextView lines. Selected categories and tools scroll into view. Representative images were visually inspected, including portrait adjustment, portrait font-2 scan/film, and landscape font-2 print.

The image fixture is a generated gradient. These are **host Activity renders**, not design mockups, emulator screenshots, actual-device tests, or evidence of successful real-photo rendering. Large text may require scrolling the tool pane; it does not shrink to tiny typography. The top overflow action can wrap on a 320 dp screen at font scale 2.

Targeted tests cover Chinese intrinsic-width wrapping, readable dialogs, long filenames outside the header, one selected control, print-only/conditional controls, stage animation mapping, cancellation UI, stateful export modal, dynamic native-plan storage budget text, rotation retaining the ViewModel/export state/selection, parameter JSON/history/safe-log round trips, actual GPU telemetry allowlisting, and rejection of wide-gamut output from the legacy sRGB-only Bitmap path.

GPU requested and active are separate diagnostics. Active requires consistent actual batch evidence, supported backend/stage and startup validation. Raw adapter names, error strings, paths and photos are not recorded.

## Other checks included

The aggregate includes PNG color-profile tests, metadata warning tests, existing diagnostics/export ownership and native capability tests. Native algorithm parity, JNI ABI builds, full original-size native workloads and APK signing are owned by their separate verification records; this document does not substitute for those results.

Launcher source retained in `design/launcher-source.png`; earlier vector sources preserved under `design/legacy-icons/`. `design/launcher-qa/masks.png` was inspected at small and masked sizes and reviewed by the parent task. Full-bleed ivory outer color does not introduce a black border.

## Environment-local evidence

- Build log: `/workspace/shared/filmroom-repair/ui-startup-gate-final.log`
- Test XML: `app/build/test-results/testDebugUnitTest/`
- Lint: `app/build/reports/lint-results-debug.html`
- Synthetic input generator: `tools/generate_highres_fixtures.py`

Do not bundle generated 600 MB input fixtures, build caches, local toolchain or private user inputs into the source package/APK.

## Final startup-recovery defect fix

Application startup launches private-work recovery on a process-owned IO scope without blocking the UI. Its gate starts Running, publishes explicit Failed on cleanup errors, admits editor operations only after Ready, and cannot run the janitor again after success. Retried failures are single-flight; Activity recreation or leaving the screen does not cancel the process gate. Pending export cleanup and source restoration remain behind the gate. Failed recovery retains a Chinese actionable retry notice and bounded diagnostic counts; an unreadable directory is not treated as empty success.

New tests cover blocked workers during recovery, exceptions becoming Failed, concurrent retries, irreversible success, a fresh-process gate, directory enumeration failure, and actual Activity/ViewModel failure/retry behavior. The latter isolates AndroidX's static AndroidViewModelFactory between Robolectric Applications and asserts that Activity, ViewModel and the injected gate share the expected Application; production behavior is not bypassed. The first aggregate exposed this test-only singleton mismatch; the final aggregate above passed all 133 tests after fixing test isolation. Production gate and test contract were independently reviewed.

## 2026-10-31 feedback revision (0.1.2 candidate)

- Removed the editor's bottom message banner from the view hierarchy. Transient messages use deduplicated Toasts; RAW interpretation notes remain in Photo Information. Startup cleanup failure remains an actionable blocking retry dialog.
- The selected parameter now measures at content height, with no vertical ScrollView in the normal editor. Horizontal selectors are separate; landscape uses compact text tool selectors. Large font scale (>1.35) exposes an explicit expanded adjustment dialog instead of clipping the controls. Tool changes reset that dialog's scroll and title.
- Export progress is a compact dark rounded surface, displaying the current local preview and a plain-language stage. Real native-pass text, storage budget, local-processing explanation and diagnostics are behind Details. Animation does not claim completion percentage.
- Actual Activity/dialog host renders: `app/build/ui-host-renders/`, at 320×640, 360×780 and 740×320, font scales 1×/2×; expanded large-text landscape dialog included. These are Robolectric/Skia renders, **not physical-device screenshots**.
- Optional `FILMROOM_UI_PHOTO` reads an external local preview for render tests. No user photo is bundled in source or app assets. Without it, tests use a synthetic gradient.
- Final shared verification on version 0.1.2/code 3: **163 host tests passed, 0 failures, 0 skips** (including high-resolution fixture tests); `lintDebug` succeeded. Log: `ui-carousel-verified.log` in the parent repair workspace. Physical-device validation remains outstanding.

- Message deduplication uses stable operation-attempt identity across import, preview and export, including when intermediate states are not observed. Real ViewModel regressions cover repeated undo/retry/import/export failures, paused collection, duplicate binds and rotation.

- Film and parameter strips preserve exact pixel offsets across same-width rebuilds and keep stable item anchors across viewport changes; saved state restores the positions. Regression tests click the last film and verify actual scroll positions after model updates, asynchronous render completion, category round trips and recreation/landscape changes.
