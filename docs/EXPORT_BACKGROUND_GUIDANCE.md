# Export background guidance (independent candidate)

> Beta 19 follow-up: the original always-confirm-on-return policy below is superseded for freshly verified standard-allowed returns by [EXPORT_BACKGROUND_RETURN_FIX.md](EXPORT_BACKGROUND_RETURN_FIX.md). Unknown/restricted returns still require confirmation.

Baseline: `83f1c33a0dc799eb9831f198be2ea327fb930641`. API 29 minimum, target 35 unchanged. No engine, parameter, preset, export queue or native changes.

## Decision and honest limits

- Read only `ActivityManager.isBackgroundRestricted()` and `PowerManager.isIgnoringBatteryOptimizations(packageName)`; either read may fail and becomes unknown.
- Explicit standard restriction -> explain that Android reports restricted background activity.
- Standard restriction false AND Doze exemption true -> continue immediately, without an extra popup. The internal name is `STANDARD_ALLOWED`, **not** OEM unrestricted.
- Otherwise -> “cannot confirm / may be restricted”, not “you have not enabled it”. Manufacturer-specific settings have no reliable common public read API; OEM unlimited can be enabled while standard exemption is off, or vice versa. We cannot promise zero false prompts or detect all OEM restrictions. Permanent ignore is the user's durable way to suppress this advisory.
- No reflection, hidden APIs, OEM databases, private permissions, direct exemption request, autostart/overlay permission prompts, accessibility automation or silent changes.

## Flow

The gate sits before the existing notification prompt and export-options dialog. It retains only an intent to open options, not an image job or changed parameter values.

1. Explicit export click -> one live gate. Standard-allowed or permanently ignored -> existing flow.
2. Warning has three full-width stacked actions: orange `#E5A35D` **Open settings**, then visually secondary Ignore and Always ignore. Back/outside cancels without continuation.
3. Ignore -> existing flow once. Always ignore -> private SharedPreferences flag, then same flow once. The flag changes neither OS settings nor permission state.
4. Open settings -> only navigation, never an export or an inferred grant. On return, re-read public status and show **Continue export / Cancel**. Both unchanged/unknown and newly standard-allowed states require explicit continuation. Continue opens existing options, where the actual export remains another explicit action.
5. A saved Bundle preserves WARNING or RETURN_CONFIRM across rotation/recreation/process recreation. CONTINUING is never replayed. If restored content is unavailable, MainActivity's eligibility check ends the pending intent; it never exports stale parameters.
6. Repeated export clicks while warning, settings pending, notification prompt or options are active do not re-enter. Closing options releases the gate. No automatic exports in onResume.

## Settings compatibility

Use documented, public application details (`package:dev.filmroom`), then the public battery-optimization list, then general Settings. Each route must resolve and is protected against RuntimeException (including SecurityException/ActivityNotFoundException). Narrow manifest queries allow resolution with target-35 package visibility. Total failure leaves an explicit continuation/cancel choice and a manual-settings toast.

No documented, stable single-app battery-page action covering API 29–35 was established; do not present hidden `APP_BATTERY_SETTINGS` or an unverified OEM component as a public SDK guarantee. App details is the reliable entry with brand-specific find instructions:
- vivo/iQOO: background power management / high background power use.
- OPPO/realme/OnePlus: battery usage / allow background activity.
- Xiaomi/Redmi/POCO: battery saver or background settings / no restrictions.
- Other brands: general battery/background usage instructions.

Labels differ across ROM, region and version; this is guidance, not a claim that exact screens were device-tested. The manufacturer only affects copy, never security/permission assessment.

## Sources checked 2026-11-01

- Android ActivityManager public reference: https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted()
- Android PowerManager public reference: https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)
- Android background optimization: https://developer.android.com/topic/performance/background-optimization
- Android Settings public reference (app-details and battery-optimization list intents): https://developer.android.com/reference/android/provider/Settings
- Android Doze guide, including difference between settings navigation and direct-exemption request: https://developer.android.com/training/monitoring-device-state/doze-standby
- vivo official support category listing high background power consumption: https://www.vivo.com/en/support/questionList?categoryId=54716
- Xiaomi official support naming background “No restrictions”: https://www.mi.com/global/support/faq/details/KA-515548/
- OPPO-hosted OxygenOS 14 manual describing Allow background activity (same-family terminology, not proof of all ColorOS variants): https://s3.eu-west-3.amazonaws.com/par-sow-cms-oppo-com/cms/default/1717410490532-3f874fba-7f9d-458b.pdf
- Community vendor compatibility research considered, not treated as a public API contract: https://github.com/urbandroid-team/dont-kill-my-app

## Verification status

- Static `git diff --check`: passed.
- XML parse and equal new key sets across default/zh/en/ja/ko: passed.
- Unit tests added: `dev.filmroom.ui.ExportBackgroundGuidanceHostTest`, 14 methods, API 29/31/35. Covers tri-state including read-unknown, actual public-probe execution, immediate allowed, permanent/once ignore, duplicate clicks, cancel, settings return unchanged/allowed, missing/protected settings fallbacks, pending recreation and no continuation replay, fake OEM instruction selection, four-language stacked orange action structure.
- Final focused Gradle validation: **PASS**, 2026-11-01 15:10:16 UTC, 55 seconds (short-copy revision `fe667e15`). `ExportBackgroundGuidanceHostTest`: 42 tests (14 methods × API29/31/35), no failures/errors/skips. `ExportBackgroundGuidanceLayoutHostTest`: one test with 32 screen combinations (4 languages × portrait/landscape × 1x/2x font × warning/return), no failures/errors/skips.
- Layout assertions include visible orange primary structure, warning action single-line at normal font, 48dp targets, no clipped/ellipsized labels, actual ScrollView last-action reachability, and both return actions within dialog bounds. Images are host Android/Skia renders, not phone screenshots.
- Manual image inspection: Chinese 320dp normal warning, English 740dp 2x warning scrolled to actions, Chinese 320dp 2x return, Japanese 740dp 2x return. Buttons are complete and the primary action is orange.
- Test-driven fix: manually changing Android framework ButtonBarLayout to vertical produced a zero-width Cancel action during framework measurement. Return confirmation now uses its own vertically stacked button container inside a ScrollView, just like the warning; no framework button-bar mutation remains.
- Initial infrastructure failures (not app regressions): read-only Robolectric home fixed by a test-JVM writable `user.home`; Android artifact HTTPS PKIX fixed by the approved system CA trust store, without disabling TLS. All API29/31/35 dependencies subsequently downloaded and final tests executed.
- Reproduce: `source /workspace/shared/android-host-tools/env.sh; export ANDROID_USER_HOME=/workspace/shared/android-host-tools/android-user; unset ANDROID_PREFS_ROOT; gradle -I /workspace/shared/android-host-tools/writable-test-home.init.gradle -I /workspace/shared/android-host-tools/trusted-test-ca.init.gradle :app:testDebugUnitTest --tests dev.filmroom.ui.ExportBackgroundGuidanceHostTest --tests dev.filmroom.ui.ExportBackgroundGuidanceLayoutHostTest --no-daemon --max-workers=1`.
- Final log: `/workspace/shared/filmroom-background-guidance-short-copy-final-tests.log`; reports: `app/build/test-results/testDebugUnitTest/`; 32 renders: `app/build/background-guidance-host-renders/`.
- OEM physical devices are NOT verified; host layout QA above does not prove OEM Settings routes. Required manual matrix: vivo OriginOS/Funtouch, OPPO ColorOS, Xiaomi MIUI/HyperOS; API29/31/35 representative devices; settings already on/off, settings missing, back/cancel, repeated taps, rotation and app death while in settings; four languages at 320dp and large font. Confirm orange button and all actions remain reachable in portrait/landscape.

## Integration / release boundary

MainActivity touch points only: one guidance field/helper; onCreate restore; onResume/onDestroy/onSave delegation; exportDialog gate; exportOptionsDialog eligibility guard and onDismiss release. No composition-control, section-icon or toolbar layout code is changed. These lifecycle/export hunks must be preserved when integrating other MainActivity work.

All other production changes are a standalone UI helper, 17 new strings in default/zh/en/ja/ko plus Chinese fallback mapping, and three narrow manifest intent queries. No new permissions. No external dependencies or SDK changes.

No push, tag, APK or Release was produced. This candidate must not be released alone. Integrate only after shared regression checks, and publish only after the user's explicit release confirmation.

## Final copy refinement (revalidated)

The 2026-11-01 post-review readability revision shortens all four languages to a risk statement, one manufacturer-appropriate settings direction, and a separate 12sp muted note that permanent ignore only hides the reminder. Button labels, ordering, handlers and the state machine are unchanged. **This revision was independently rerun on 2026-11-01 at 15:10 UTC: all 42 functional tests and all 32 warning/return layout combinations passed.** Fresh Chinese 320dp normal, English landscape 2x and Korean 320dp 2x renders were inspected: the compact hierarchy is readable, all actions are reachable, and the primary action remains orange. The original longer-copy pass is not used as evidence for this revision.
