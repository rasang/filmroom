# Window-adaptive editor layout

The editor chooses its existing two-pane preview/controls layout when the current Activity window is at least 600 dp wide. Ordinary full-window landscape keeps the previous two-pane behavior; a narrow multiwindow uses the compact layout. This uses the Activity's configuration/window dp, never a physical display size or device-model allowlist.

Examples: 600×800 and 840×900 use side-by-side panes even while held upright; 393×852 uses the compact editor. A 640 dp-wide split window supports panes; a 480 dp-wide split window uses compact controls. The 600 dp boundary is consistent with Android's medium-width window class: https://developer.android.com/develop/ui/views/layout/use-window-size-classes

System-bar visibility is independent: upright expanded windows do not become fullscreen merely because their controls use two panes. Actual landscape/full-window retains the existing status-bar-only fullscreen policy. Insets continue to protect cutouts, navigation, captions and IME.

Scope: continuous-screen unfolded foldables, tablets and resizable windows. This is not a folding-sensor or hinge detector. The project does not consume Jetpack WindowManager FoldingFeature; physical hinges/occluding folds and half-open tabletop arrangements are not specially routed around. Do not describe this change as verified hinge avoidance or physical-device posture support.

Configuration changes recreate Activity views while retaining the editor ViewModel. Current photo, parameters and composition draft stay in that ViewModel; selected group/tool, zoom and per-group scroll positions use existing saved state. A multiwindow change that alters the layout policy requests ordinary recreation rather than keeping mismatched compact/wide control trees.

Verification is host-only (Robolectric API29/31/35). It cannot verify actual OEM fold transitions, display continuity, hinge occlusion or system-bar rendering on a phone.
