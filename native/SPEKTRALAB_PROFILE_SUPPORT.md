# SpektraLab still-film data port

Source: https://github.com/JamesQiu2005/SpektraLab at
`02d82b56cf65f0f8e802a708612edc4e9a4790af` (latest checked 2026-11-06).
Filmroom base: `554ce76883582698c6330d88e749fd01e1eaa59b`, identical source tree
to official `a171737083c212e3d5647984db2dfb668fd96cdd`.

## Scope and provenance

Adds five camera configurations: Kodak Tri-X 400 (400TX), Kodak T-Max 100,
Fujifilm Neopan 100 Acros II, Ilford HP5 Plus 400 and CineStill 800T.
Existing `kodak_trix` is Tri-X **7266 reversal**, not 400TX, and stays unchanged.

The four B&W camera profiles target Ilford Multigrade IV RC. That silver paper
is included solely as the necessary default print-chain dependency, together
with the four exact TH-KG3 filter-2 packs (C0/M0/Y68, exposure multiplier 1).
It is not selectable as a camera film. No new grade selector is introduced;
existing physical enlarger controls remain available.

All six original product JSON files are byte-for-byte unchanged in native data
and Android assets. Each preserves its measured, fitted and assumed quantities
and source data-sheet provenance. Source notices, author attribution and the
paper-grade metadata accompany the port. Upstream's README credits the four
B&W profiles to Hanze Qiu and assigns film/paper profiles CC BY-SA 4.0. The
original 500T profile is Andrea Volpato's CC BY-SA 4.0 data. Filmroom does not
claim manufacturer endorsement or independently measured calibration.

## Schema adaptation

The four B&W camera profiles repeat a single emulsion across three equal lanes,
with one third of neutral silver density in each. The loader checks equality,
then collapses sensitivity/curves/layers/model and **sums** the silver weights.
The existing single-channel engine broadcasts at render time. Collapsing only
the first silver lane would incorrectly retain one third of the density.
These profiles have one fixed development recipe, not selectable time families.

Multigrade has three **different physical emulsions**. The loader preserves all
three sensitivities and curves, each with its own silver contribution. It must
not follow the camera-film collapse or the 2302 development-family selection.
The original density table and scalar contrast are used. RGB dye morph,
colour warmth and development-family controls are unavailable; physical
spectral enlarger filters remain active.

800T remains an alias, not a duplicated or relabelled measured data file. Its
loader inherits the existing 500T arrays and attribution, overrides only source
`name`, `use: still`, `antihalation: no`, and assigns the alias its own stock ID
for cache/state isolation. Its inherited target print is Kodak 2383. Alias
paths are restricted to simple sibling stock IDs, cycles/depth are bounded,
and arbitrary info overrides are rejected. No additional ISO exposure shift
is invented, because the upstream alias does not specify one.

Historical neutral-filter entries retain their exact numeric values. 800T
inherits the existing 500T print calibration packs because the measured
negative spectra and curves are identical. The four new B&W packs only apply
to Multigrade/TH-KG3; other combinations preserve manual values rather than
pretending a calibrated pack exists.

## Intentional renderer boundary

The profile import retains Filmroom's renderer rather than replacing it with
SpektraLab's C++/Metal engine. The initial 0.1.22-beta1 import selected the old
no-antihalation/still-film baseline ([0.30,0.10,0.015], sigma 65 um). It did not
include SpektraLab's CineStill-specific long-tail base reflection or highlight
headroom, so ordinary clipped highlights could show only a narrow orange edge.

The subsequent CineStill repair explicitly opts in **only** stock ID
`cinestill_800t` to SpektraLab 02d82b56's four-component per-sensitive-layer
back-reflection fit, 120 um base, [0.30,0.03,0.003] strengths, and separate 4 EV
highlight headroom. Other stocks, including historical no-antihalation stocks,
keep their prior model. User highlight boost runs first; headroom uses its
result's full-frame maximum. A two-part gate protects dark frames and pixels
far below the brightest source. Disabling halation also disables headroom.
The amount control scales both returned light and halo area for this model;
its radius therefore scales by sqrt(amount). The separate size control retains
its existing linear-radius meaning.

The fit is a source-derived optical approximation, not newly measured
manufacturer calibration. Filmroom's CPU Gaussian IIR and GPU finite FIR
implementations still differ; there is no cross-renderer or physical-device
pixel-parity claim. Grain, DIR, scattering and other stock behavior are not
replaced. See `../docs/CINESTILL_HALATION.md` for path and validation scope.

Film-edge and roll artwork are separate presentation assets. New stocks do not
borrow another stock's branded edge. Unsupported frames are disabled safely;
verified 120 artwork may be integrated independently.

## Verification

`tests/spektralab_profiles.rs` covers exact channel adaptation, fixed-development
semantics, the three-emulsion paper, distinct 800T identity with unchanged
measurements, malformed aliases/unequal lanes, spectral response, neutral
prints, and whole/streamed direct and print routes for every new film.
`mobile::spektralab_stock_tests` covers the different 800T backing baseline and
BW capability isolation. Existing profile-route/BW suites cover old stocks and
all newly exposed routes. Android tests cover selection, defaults, manually
chosen route/paper retention, capability flags and missing-artwork handling.

Test execution results are reported separately. No device GPU, camera fixture,
manufacturer reproduction or cross-renderer parity is implied by host tests.

### Executed host checks (2026-11-06)

- All 27 focused native tests passed: 8 product-profile tests, 3 mobile/halo tests, 2 catalog tests, 4 existing B&W tests and 10 all-profile-route tests. This includes every new film's direct/print whole-versus-streamed output, shared B&W grain, three paper emulsions and 800T's actual highlight-render difference.
- A real host JVM/JNI probe rendered all 5 new films through both routes using Android source assets: 10/10 successful, every output finite and non-flat. Source: `tests/jni/dev/filmroom/engine/SpektraLabProbe.java`. This is a host CPU shared library, not an Android binary.
- The complete core suite reported 96 passed / 2 failed. The two pre-existing `resident_contract_tests::film_log_*` failures reproduced identically on 26 core files verified byte-for-byte against 554ce768. They concern the mock film-log/gamut contract; they are not counted as passes or silently removed.
- All 31 historical profile files and 198 historical neutral-filter entries remain unchanged. All 6 new product JSON files match upstream bytes in both native and app assets.
- Combined Android verification passed: all 181 production Kotlin sources and 22 focused test/helper sources compiled, all 41 tests across 11 profile/UI classes passed, including the full 16-layout film-strip matrix. An additional actual Activity test passed the complete 120-only selection flow for the three separately reviewed stocks. See `../docs/SPEKTRALAB_ANDROID_INTEGRATION.md` for scope and host-memory recovery details.
- Existing precompiled Android `.so` files have NOT been rebuilt: a new APK must rebuild the native engine before it can render these profiles. No APK, release or main-branch publication was performed.
