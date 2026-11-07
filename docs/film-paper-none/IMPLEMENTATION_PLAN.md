# Independent film / paper omission

Status: source investigation and icon draft only. NOT enabled in selectors or native. No APK.
Base: a3a3f1e7; beta4 tree remains untouched.

## Product contract

Two independent existing-list entries named None, ID `none`. No global mode.
- both actual profiles: retain existing arithmetic and output byte compatibility.
- no film, no paper: scene-linear RGB remains scene-linear RGB; applicable camera optics/exposure and output transformations still apply. Do not promise original-file byte identity.
- selected film, no paper: retain film exposure/development, grain/halation. A negative requires an explicitly calibrated positive conversion, not direct negative scanning. Positive film retains positive scan semantics.
- no film, selected paper: expose selected paper from a defined neutral RGB-to-print bridge; no named film profile may silently substitute for none.

The last two bridges are new numerical algorithms, not merely selection/visibility changes. Their exact response needs tests and a documented calibration before enabling UI.

## Confirmed hazards in current source

`profile_catalog::valid_route` validates both profiles even on direct route.
`Pipeline::process` consumes film density in printing, and scanning consumes print/film density.
`io.scan_film=true` on a negative generates negative output; not paper-none positive output.
ColorReference explicitly does not correct negative direct scans.
PreparedStreamedFinish, GPU resident full/banded/tiled chains, film-log / corrected-density caches bypass ordinary `process`; changing process alone would leave export wrong.
EditorState.selectFilm can automatically replace paper when it equals previous default. Selecting None must not erase the independent paper choice.
Old QR frozen slot defaults and old export snapshots must remain unchanged. Existing fields can hold sentinel values, but old readers must reject unknown profile IDs with upgrade prompt, not substitute defaults.
None has no physical frame capability; switching back should restore saved controls rather than reset them. No-film thumbnail is the existing 135/120 geometry in dashed outlines.

## Acceptance matrix

Four stock combinations × negative/positive/B&W where applicable × preview/full/banded export.
Neutral ramp and color chart: finite output, monotonic positive image, no channel swapping, no treating density as RGB.
Both-none neutral settings: near-identity scene-linear test (with output color conversion explicitly accounted for).
Real-profile combination bit-exact legacy regression.
Camera AE uses full photo, never border or ROI; pre-imaging borders retain their input-stage contract.
Effects that require absent medium are unavailable (grain, halation, film DIR/development; paper gamma/development, calibration). Stored values preserved; reselecting actual profile restores them.
Shared camera diffusion/lens and output transforms remain supported, tested independently.
Old preset/QR/queue missing fields preserve old behavior; `none` survives new presets and backup. New codes rejected by older profile catalogs, never silently restored.
No current beta4 modification, no shared heavy JVM/native build until owner releases resource window.
