# Parameter interaction contract

- Finite, short-label choices (metering, on/off, diffusion, illuminant, route) use
  `SettledChoiceSelector`. A swipe selects the nearest centered value after 160 ms
  of scroll inactivity. Dragging and intermediate fling positions do not call the
  model. A committed distinct selection calls `parameters(..., true)` exactly once.
- Binding/restoring state, layout/rotation, and detaching an in-flight rail do not
  commit. The callback reads the current model, not an old captured parameter copy.
- 48 dp previous/next targets, optional direct option taps, focusable controls,
  spoken value/index, and accessibility scroll actions provide non-swipe access.
- Long paper names keep the readable full-name list and explanations; they are
  deliberately not forced into a narrow rail. Film artwork is unchanged pending
  user approval of an external coherent set.
- Every existing adjustment is directly present in its category's horizontal tool
  strip. There is no “精细” gate or menu. The editor still mounts only one control.
  Print-only and dependent controls retain their pipeline applicability guards.
- `exactValue` only controls numeric formatting, not visibility.
- Main editor contains no new vertical scrolling slot. At 2× font scale the existing
  expanded adjustment sheet is retained; its redundant title was removed to keep
  the selected control usable in short landscape windows.
- Editable 24 dp vectors (`ic_tool_*`) distinguish semantic families: metering,
  auto exposure, contrast, temperature, saturation, diffusion, halo, scatter,
  reflection, decay, grain size, layers, dye cloud, uniformity, clumps, curves,
  coupling, paper, flash, scan sharpening, endpoints, gamut, and GPU. Channel
  controls have identifiable R/G/B and C/M/Y marks. Related radius/blur/curve
  operations intentionally share their family glyph rather than arbitrary art.
- Carousel scroll-retention code is untouched. Tool strip and film strip retain
  their per-category saved anchor/offset semantics.

## Host verification

Focused command (official toolchain, no emulator, no APK packaging):

```
JAVA_TOOL_OPTIONS=-Duser.home=/workspace/shared/toolchains/java-home \
  gradle --no-daemon :app:testDebugUnitTest \
  --tests dev.filmroom.SettledChoiceSelectorHostTest \
  --tests dev.filmroom.FilmEffectsControlsHostTest \
  --tests dev.filmroom.CameraLayoutRenderHostTest \
  --tests dev.filmroom.FilmCarouselPositionHostTest
```

Tests cover settled swipe without follow-up tap, one undo step, no intermediate
commits, silent restore/layout, disabled/accessibility/arrow behavior, discarding
pending work on detach, every exposed tool reachable, semantic icon family count,
long-name paper exception, and existing carousel-position regression cases.
`CameraLayoutRenderHostTest` generates actual Activity/Skia host renders at
320×640, 360×780 and 740×320, font 1×/2×, plus metering rail and expanded sheet.
These are host Android renders, not claimed physical-device screenshots.

Verification on 2026-10-31: focused suite 16/16 passed (selector 5, tool coverage
4, actual layout/vector host rendering 4, carousel retention 3). All 46 new
VectorDrawable geometries are distinct. The rendered contact sheet is
`build/ui-host-renders/parameter-vector-families.png`; metering viewport renders
are `{320x640,740x320}-font{1,2}-metering.png` in the same directory. The 2×
landscape test asserts the selected rail is fully visible without vertical scroll.
The concurrent `lintDebug` run found no selector/vector issues, but did find one
unrelated queue-screen ResourceType error (literal scroll-view id); its owner was
notified to replace it with a declared `R.id` and rerun aggregate lint.

## 0.1.3 device-feedback regression: short rails clipped

The centering insets used to be applied from `HorizontalScrollView.onSizeChanged`,
after the rail had already been measured. On its first layout the two-option
on/off rail could measure 176 px although its children plus the newly added
insets required 352 px; Android clipped the text and most of the selected pill.
The insets are now calculated before measuring the rail, and silent selection
restoration happens after its children are laid out. The rail explicitly uses
wrap-content bounds. This also handles changed option widths on rebind without
relying on a viewport-size callback.

Regression coverage checks the very first traversal (without an extra pass),
1/2/3/7 choices, every selected index, 160/440/480 dpi, widths 360/411/740 dp,
rebind and portrait/landscape resizing. It verifies measured content width,
selected-item centering and full viewport containment, not merely non-ellipsized
text. Actual MainActivity/Skia host PNGs for auto exposure off/on at 360/411 dp
and 440/480 dpi are `build/ui-host-renders/autoExposure-*.png`. They are host
renders, not physical-device screenshots. Existing swipe settling, single undo,
arrow/accessibility, cancellation, disabled and detached-control checks remain.

## Compound auto-exposure choice

The film tool strip has one `自动曝光` control: `关闭`, `平均`, `中位数`,
`中央重点`, `局部`, `矩阵`, `多区域`, `亮部重点`. Selecting a method enables
AE and sets metering in one immutable state update / undo step / preview request.
Turning it off preserves the remembered method. The existing global reset restores
both fields together. Saved UI selection `metering` resolves to `autoExposure`.
The engine, persisted settings and export JSON still use the original independent
`autoExposure` boolean and `metering` string; no schema or native change is needed.
Control coverage records both parameter IDs against the compound tool rather than
dropping metering from coverage. Film cards and the queue/export buttons are unchanged.
