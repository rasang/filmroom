# Beta 19 verified settings-return UX fix

## Source / observed issue

- Baseline: `1a05595fa4fda0a9676a0a38466ab727cc5ed6b6`, version `0.1.19-beta.1` / code 20.
- Worktree: `/workspace/shared/filmroom-background-return-development`, branch `fix/background-settings-return`.
- Observed issue: a redundant confirmation appeared after returning from allowed background settings.
- Authorized change: after the user opens settings and returns with verified standard allowance, show a short success Toast and immediately open export **configuration**, not start a job. Unconfirmed/restricted returns still require the explicit decision.

## Implementation contract

- Add `SETTINGS_PENDING` to distinguish successful settings navigation from `RETURN_CONFIRM` (a visible decision, a failed navigation, or a legacy saved state).
- On `SETTINGS_PENDING` resume, freshly read the unchanged public assessment. Only `isBackgroundRestricted == false` AND `isIgnoringBatteryOptimizations == true` qualify as `STANDARD_ALLOWED`. Unknown/failed read/restricted never produces success.
- Set `CONTINUING` before showing a short localized “Android background checks passed” Toast and calling the dedicated verified callback. This states only what the public Android checks established; it does not claim an OEM-specific permission was granted, that settings changed, or that all restrictions are off.
- The dedicated MainActivity callback invokes `exportOptionsDialog()` directly, avoiding another optional notification-permission popup on this successful return path. Other entry/ignore paths retain the existing notification flow.
- `exportOptionsDialog()` still validates editor/photo eligibility. It does not modify presets or enqueue work on creation; export submission remains inside its explicit positive-button listener. Missing/unrecoverable editor state ends the pending request safely.
- Save/restore preserves pending navigation; consumed continuation is not replayed. Repeated taps/resumes are ignored. Cancel/failed routes/old `RETURN_CONFIRM` state cannot become automatic success on restoration.
- Unknown or restricted return retains the existing readable Continue/Cancel dialog. Toast and button resources cover zh/en/ja/ko; existing orange primary and permanent/once ignore are unchanged.
- No added permission, OEM private API, SDK change, native change, release/version bump, signing or publication. Baseline/released APK untouched.

## Verification plan / status

- Static diff check and five locale XML parses: PASS.
- Independent reviewer: no blocking findings in phase logic, public-state semantics, callback wiring or export submission boundary. Its identified integration-coverage gap is addressed by a passing MainActivity host integration test.
- Functional controller matrix: 21 methods × API29/31/35; new checks include successful return/no second modal, short Toast, exactly-once callback, normal resume silence, pending recreation, consumed-state non-replay, restricted/unknown returns, failed-route restoration, cancellation, legacy saved state, double Open taps, four-language Toast.
- Existing 32 warning/return layout combinations retained.
- MainActivity integration fixture uses the actual production callback and verifies options modal, no optional notification popup, no exporting flag, parameter preservation, repeated-resume idempotence and cancel safety; captures a host screenshot.
- Final fresh build/validation: **PASS**, 2026-11-02 02:33 UTC, `BUILD SUCCESSFUL in 1m 56s`. 63 controller tests + 1 layout test containing 32 combinations + 1 MainActivity integration = **65 tests, 0 failures/errors/skips**. Production and test Kotlin compiled from this isolated Beta checkout.
- Log: `/workspace/shared/filmroom-background-return-tests.log`. XML: `app/build/test-results/testDebugUnitTest/`.
- Fresh integration image visually inspected: `app/build/background-return-host-renders/verified-return-export-options-zh.png`; it shows the normal “Export new photo” options, not the obsolete return-confirmation modal. The user must still press Save to submit. This is host Skia evidence, not a physical handset screenshot.
- Reproduce: source `/workspace/shared/android-host-tools/env.sh`, set `ANDROID_USER_HOME=/workspace/shared/android-host-tools/android-user`, unset `ANDROID_PREFS_ROOT`, then run `gradle -I /workspace/shared/android-host-tools/writable-test-home.init.gradle -I /workspace/shared/android-host-tools/trusted-test-ca.init.gradle :app:testDebugUnitTest --tests dev.filmroom.ui.ExportBackgroundGuidanceHostTest --tests dev.filmroom.ui.ExportBackgroundGuidanceLayoutHostTest --tests dev.filmroom.ExportBackgroundReturnIntegrationHostTest --no-daemon --max-workers=1`.
- No APK, tag, release, push or version change was made. This is a tested source patch for the next explicitly authorized build.

Host tests and public-state fakes cannot establish settings behavior on every vivo/OPPO/Xiaomi ROM. Physical OEM verification remains an explicit limitation.
