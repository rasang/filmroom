# Fuji separate-stripe direct-negative model v13

## Observed / documented versus modeled
C200 CA24 manufacturer bulletin p4, section13 explicitly describes one green solid line plus two red broken lines on one side of processed film:
https://asset.fujifilm.com/www/us/files/2019-09/cce1e1943550fc3e76c22411066f0100/films_c200_datasheet_01.pdf

Superia X-TRA400 CH23 bulletin p4, section14 explicitly describes one red solid line and one green short broken line on both sides:
https://asset.fujifilm.com/master/emea/files/2020-10/9a958fdcc6bd1442a06f71e134b811f6/films_superia-xtra400_datasheet_01.pdf
Actual un-inverted old Superia strip photographed by Mark Frey qualitatively corroborates dark red/green rails against orange/brown base; lighting/resolution and unclear generation prohibit matched-era calibrated RGB claims:
https://emulsive.org/reviews/film-reviews/fuji-film-reviews/film-stock-review-three-rolls-with-made-in-usa-fujifilm-400
Only its lower old Superia strip is relevant; not upper new USA Fuji or middle Kodak.

PRO400H bulletin p6 supports line geometry in a monochrome diagram but supplies no hue:
https://asset.fujifilm.com/master/emea/files/2020-10/a6cb96275e4957ddc7b3ca932b7755e5/films_pro-400h_datasheet_01.pdf
Its current dark monochrome line is a model illustration, NOT a measured color or confirmation of the prior pale-yellow generated line. No independent own-stock physical-color evidence has been established.

## Reproducible model
The same-stock profile's spectral channel-density basis was checked directly: C200 absorption peaks700/545/450nm and XTRA695/545/450nm establish cyan/magenta/yellow channel ordering. Red-family rails use the same profile's M+Y channelwise maxima with C=0; green-family rails use C+Y maxima with M=0. Add profile base spectral density, integrate 10^-density with D65 and CIE matching functions, convert XYZ to linear sRGB, then clamp display gamut once. JSON records density vectors, un-clamped linear outputs, clamp channels, sRGB display palettes, source profile/math hashes and peak wavelengths.

These channelwise maxima are deliberately explicit upper-bound model choices, not factory printing exposures; maxima need not be reached at one common scene exposure. Manufacturer hue names and shapes support qualitative reconstruction but do not validate these numerical spectra, density strengths or RGB values. The model is not a calibrated raw scan. No other stock's palette is borrowed.

## Pixel scope
Only C200/XTRA's existing independent rail masks are recolored. Masks reuse exact prior generated-artwork rows and dash ranges; mask PNGs and hashes are included for proof. Alpha and hole geometry are unchanged. Outside those masks, every RGBA pixel equals v12; text/25/DX are untouched. PRO400H is byte-identical in decoded pixels to its v12 candidate. DX bits, placement, parity and dark-bit1 threshold are unchanged from v12 and all visible native code pixels read back correctly. Native PNG and lossless WebP RGBA are equal. Actual runtime codec validation remains separate.

build.py reproduces candidates from inputs/ (own prior artwork and extracted model numerical data). prepare.py documents extraction from the project; it is not needed when inputs are present. Reference photographs and PDF screenshots are not bundled in runtime or generator inputs. Review shows white holes as an optional display matte; transparent PNG geometry is unchanged. None of these files independently authorizes runtime activation or release.

## Remaining evidence boundary
The broader18-stock audit retains actual physical-image gaps for PRO400H, VISION3 50D and Verita200D. Fifteen have partial own-stock physical/scan support, not complete rawscan replicas. Root visual approval accepts this model-reference reconstruction for isolated integration; it does not establish measured stripe colors, factory exposures, or complete authenticity. Existing source limitations remain in force.
