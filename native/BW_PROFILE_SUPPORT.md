# B&W profiles — Beta 2 source integration

## Provenance and exact data

Verified 2026-11-02 through the GitHub connector: Rust upstream `turbasvin/spektrafilm-rs` main is `9dd59b0380194b93686aaa230a8bb9680aa270a4`; Python upstream `andreavolpato/spektrafilm` dev is `6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507`. The three profiles are byte-identical in both trees:

| Runtime ID | Physical identity / role | Git blob |
|---|---|---|
| kodak_doublex | Kodak Double-X 5222 camera negative | 6f30b1ae7b7baf5abbb72b9eba58153988aac387 |
| kodak_trix | Kodak Tri-X 7266 reversal, Super 8 presentation (not still-photo Tri-X 400) | d77c7047417a5546e51808ca5fea7f0e5962f00c |
| kodak_2302 | Kodak 2302 black-and-white print film, printing stage only | 7cb1fb7860d9775fc852c9216cf1203d429cc80a |

Rust paths: `data/profiles/<id>.json`; Python paths: `src/spektrafilm/data/profiles/<id>.json`. Profiles retain Andrea Volpato's metadata and CC BY-SA 4.0 license verbatim; license text remains in SPEKTRAFILM_LICENSE.txt and packaged notices. No manufacturer data or photographic edge reference is redistributed separately. Existing colour data and LUTs are untouched. This is not a claim of independent photographic calibration or Python image parity.

## Scientific behavior

The existing pinned upstream core already resolves BW development-time families and integrates a measured single-channel sensitivity spectrum. It broadcasts that channel for the three-lane implementation, with `[silver spectrum, 0, 0]` spectral density weights. This is not output desaturation or a cosmetic negative inversion.

5222 times: 4, 5, 6.5, 9, 12 minutes (default 6.5). 2302: 2, 3.5, 5, 7, 9 (default 5). 7266 has one supplied entry, 1, not a user-adjustable family. These numbers identify the supplied measurement families, not universal development instructions. Zero API value chooses the upstream middle entry; positive values select nearest. Film and print development selections are serialized independently.

Integration corrections: null log-sensitivity samples become zero linear sensitivity (negative infinity in log space), not unit sensitivity; existing colour profiles have no null sensitivity samples. Selected grain sublayers are also broadcast to three lanes; BW microstructure uses one shared random sample per pixel in sequential and coordinate paths; streamed export no longer rejects supported BW films. These are BW-only branches, preserving historical colour operations. The simplified pipeline constructor also applies BW-specific parameters (colour behavior unchanged).

2302's parametric fit includes skew-normal alpha parameters unsupported by the colour CDF morph. Resolving this **printing** profile clears only the runtime fitted model so all whole/prepared/GPU paths use the original density table and scalar gamma. The source profile is unmodified. Fast/slow/RGB morph and developer-exhaustion controls are unavailable for 2302; scalar contrast × paper gamma remains applicable. We do not fabricate missing skew-model support.

Colour-layer grain scaling, saturation, interlayer DIR and halation hue are unavailable on BW camera film; their stored values survive switching back to colour. Physical grain amount/size, single-layer DIR, spectral exposure, scattering/halation, diffusion, scanner correction where route-valid, and measured development remain available. Post-Beta2 correction: the original integration omitted upstream neutral-filter additions. The database now retains all 160 historical entries unchanged and appends the 38 BW/2302 entries from Python dev `6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507`. BW combinations use the real lookup at TH-KG3; other illuminants retain manual controls and saved preferences. Reversal 50/50 entries are upstream placeholders, not a supported reversal-print route. Colour warmth is unavailable on BW print film; physical enlarger filters retain their spectral exposure behavior.

## Roles and presentation

Catalog: 22 shooting configurations (20 existing + 5222 + 7266), 9 print media (8 existing + 2302). 2302 cannot be selected as a camera film. Reversal requires direct scan. Direct 5222 remains a physical negative scan. 5222 defaults to 2302 print; explicit output-route and unrelated editing preferences are retained.

2302 presentation is selected when it is the active print medium. Otherwise the camera-stock frame applies. Presentation is composited after scientific output, not exposed through the film engine. BW geometry version 4 is separate from immutable historical 135 geometry versions 1–3. See the BW asset contract for the original reviewed artwork, crop masks, and exact checksums. The geometry is a reference-informed artistic contract, not manufacturer metrology certification.

## Verification

Focused native BW tests live in `crates/film-engine/tests/bw_profiles.rs`; the existing profile_routes suite automatically covers all new default routes and print media. Android state and presentation tests cover persistence, parameter capabilities, effective print-stock frames and preview/export composition. Final test results and unrun device stages are recorded in the integration report; this file does not claim an Android device pass.
