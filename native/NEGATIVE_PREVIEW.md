# Export queue negative-stage illustration

`film-engine/src/negative_preview.rs::write_negative_preview` accepts the three
actual full-resolution little-endian f64 CMY film-density planes, the resolved
`Pipeline.film` profile, image dimensions, exclusive job directory and cooperative
cancellation callback. It does not read the original RGB, rerun film exposure or
construct an inverted original. The checkpoint owner invokes it after committing
stage 4, or when resuming a valid stage 4/5 film-density checkpoint.

## Physical meaning and limitations

For each sampled source pixel and measured wavelength (380–780 nm, 5 nm grid):

```
D(lambda) = base_density(lambda) + sum_c density_c * channel_density(lambda,c)
T(lambda) = 10 ^ -D(lambda)
XYZ = sum_lambda T(lambda) * D65(lambda) * CIE1931_XYZ(lambda)
      / sum_lambda D65(lambda) * CIE1931_Y(lambda)
```

The profile's base/fog, including the colour-negative mask, is retained. Missing
spectral measurements contribute zero, matching the existing physical scanner.
The image is a D65 light-table illustration, independent of the selected final
output colour space or the profile's default scanner/view illuminant. XYZ is
converted to linear sRGB, display-clipped to [0,1], then encoded with the standard
sRGB transfer function. There is no automatic exposure, RGB inversion, paper
printing, scanner glare, positive luminance correction or aesthetic tone curve.
Thus it is **not a prediction of a particular real scanner or the final exposure**.
UI label: **负片阶段示意 · 非最终成片**.

The maximum thumbnail edge is 256 without upscaling. Four stratified source
samples per destination pixel are averaged in XYZ/light, **after** spectral
transmittance conversion, not in logarithmic density. This is an explicitly
approximate spatial thumbnail (fine structure can alias), not exact area
integration. It never allocates a full-resolution image or spectrum tensor.
Three row buffers are bounded to 3 MiB total by a validated 131072 source-edge
limit; normal 50 MP input uses far less. Cancellation is checked every thumbnail
row and around publication. Profiles not explicitly `negative` return `None`;
reversal/positive stock is never misrepresented as a negative.

## Publication contract

- `<jobDir>/negative-preview.png`: RGB8 PNG with sRGB chunk, at most ~197 KiB.
- A stored-DEFLATE PNG encoder uses no added dependencies or vendor codecs.
- Write `negative-preview.png.tmp`, sync file, check cancellation, atomic rename,
  then sync directory. Temp file is removed on errors/cancellation. A stale temp
  from hard termination is removed on retry; an existing published thumbnail is
  not damaged by a failed replacement. Checkpoints remain untouched.
- Consumers observe only the `.png`, refreshing on mtime/size change. They must
  never display the `.tmp`. Ordinary optional-thumbnail errors must not discard a
  valid density checkpoint; interrupted errors retain cancellation semantics.
- The exclusive job workspace and immutable parameter snapshot prevent
  cross-job or changed-profile stale thumbnails. Job deletion owns cleanup.

Tests cover synthetic known optical densities (not inverse RGB), light-domain
averaging, bounded/no-upscale dimensions, positive-profile omission, real Portra
spectral parity with the existing scanner, base/dye dependence, nonfinite input,
and cancellation between sync and rename with previous-publication preservation.
An optional `FILM_NEGATIVE_QA_DIR` produces only a synthetic density chart for
independent PNG-decoder checks. No private image fixture is used or retained.
