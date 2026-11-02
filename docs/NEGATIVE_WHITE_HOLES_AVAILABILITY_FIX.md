# Disable ineffective negative-white-hole switch

Follow-up on isolated branch `fix/background-settings-return`, after return-flow fix `885dae0b`. The baseline/released Beta remains untouched.

## Renderer-derived applicability

`FrameAppearanceContext.holeMatteSrgb` always returns white for REVERSAL; toggling the stored preference cannot change its rendering. `CandidateBorderDefinitions` follows the same semantics. For NEGATIVE, **both print and direct routes** select distinct white/black matte definitions (direct via `DirectBorderDefinitions`).

`EditorState.negativeWhiteHolesSupported` therefore uses the renderer's generated `FrameProfileCatalog` classification and valid route, not a handwritten film-name blacklist. Existing catalog parity checks guard the source profile JSON. All current reversal profiles—Provia 100F, Velvia 100, Ektachrome 100 and Kodachrome 64—are disabled; valid negative direct scans are not accidentally excluded.

## UI behavior

The shared portrait/landscape film control is enabled only when the editor is editable, the border watermark is on, and the current film/route supports a varying hole matte. Disabled uses the existing `enabled` helper (`isEnabled=false`, alpha `.38f`), so accessibility sees the same disabled state. The stored check value is retained, never force-cleared; switching back to a negative restores the user's choice. The listener rechecks current applicability before writing. Landscape section invalidation now also includes output route.

Existing four-language “negative white holes” labels remain unchanged. No rendering, export, import or reset policy is changed.

## Validation

- Static diff check: pass. Independent static review: no blocking issue; its suggested positive-click regression was added.
- New focused host tests compare capability with actual matte definition selection for all 20 profiles and legal routes, test stock/mode transitions + JSON/reset preference retention, and measure real controls in portrait/landscape across every reversal, negative print/direct, border on/off, both stored values, busy state, accessibility, disabled listener guards and enabled clicks.
- Existing narrow/2x-font control test updated to expect reversal disabled.
- Actual import regression extended to both negative and reversal films with reset on/off and both stored choices. Existing reset confirmation test retained.
- Fresh final execution: **PASS**, 2026-11-02 02:50 UTC, `BUILD SUCCESSFUL in 1m 35s`. **76 tests, 0 failures/errors/skips**: 3 new availability tests, 5 frame-context/catalog tests, 3 selected reset/import tests, 1 narrow/large-font control test, and 64 background-return controller/integration regressions. Kotlin recovered its first daemon startup automatically and compilation completed.
- Positive listener coverage verifies real clicks update the stored preference on both negative print and direct routes. Disabled synthetic changes cannot write model state.
- Actual import coverage uses both negative and reversal films, reset on/off and both stored choices; imports preserve preference/capability and retain the existing reset-border policy.
- Log: `/workspace/shared/filmroom-white-hole-availability-tests.log`; XML: `app/build/test-results/testDebugUnitTest/`.
- Four actual Android host control-row images: `app/build/white-hole-availability-host-renders/`. Visually inspected negative portrait and reversal portrait/landscape: negative switch is active orange; reversal switch is gray while retained checked state is unchanged. These are host renders, not handset screenshots.
- Runtime layout covers both portrait/landscape and all four current reversal profiles; existing text resources are untouched.

No APK/signing/release/push/version change is authorized or performed by this patch.
