# Direct negative model v12 — isolated review, no activation

14 physical negative films only. Four reversal stocks are not edited. Portra800 uses the accepted v11 typography; its +1/+2 profiles share the base physical frame by explicit approval.

11 monochrome-rail results are review candidates, not authenticated factory edge colors. Three Fuji results are blocked diagnostic drafts. C200/XTRA red/green lines collapse onto one contrast axis and become weak; this loses required chromatic information. PRO400H currently has a light single rail, whose geometry survives, but no stripe-specific dye/exposure data was established. Do not silently route these blocked assets as finished direct-negative artwork.

Each film uses its own existing profile-normalized density minimum and per-channel maximum vector, supplied by the native-model endpoint study. Channel maxima need not occur at one common exposure. Dmax is a contrast upper-bound demonstration, not measured factory lettering exposure. D65 appearance is not a calibrated scanner RGB standard. Out-of-display-gamut endpoint channels are clamped once before continuous linear-transmittance interpolation; manifest records every clipped component.

Existing own artwork is not RGB inverted. Its material and ink anchors yield continuous soft coverage through linear-RGB projection. This is a raster-derived mask, not an original factory latent-image layer. Texture persists only as scalar coverage variation; no added noise or spatial full-frame blur. Exact logical DX masks preserve both25/25A symbols, coordinates, module dimensions and parity; color polarity becomes dark-bit1 and threshold is recomputed from each model palette.

All alpha bytes and aperture/perforation geometry remain exact; no personal photo or reference photograph enters frames/inputs. White holes in the contact sheet are a review display only. Runtime controls white/black matte; PNG holes and image window remain transparent with zero hidden RGB.

build.py plus inputs/ reproduce the output PNG/WebP. Inputs contain only own prior generated WebP assets, model endpoint JSON and verified DX contract. Runtime owner owns immutable new context versions and actual encoded/preview gates; this package does not modify any registry, app source or old asset. Actual codec gates are not yet run for these palettes.
