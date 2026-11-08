# No-film Portra 400 grain

When `filmStock=none` and ordinary grain is active, grain controls synthesize a separate fixed middle-density
Portra 400 grain field. Only the optical-density residual is applied to the
existing digital log-RGB signal, before the optional paper stage. Portra's
spectral exposure, tonal response, mask and scan colour are **not** applied to
the image. A fixed field is intentional: its grain does not adapt to the image's
exposure density, and spatial grain blur cannot blur the original image.

`grainAmount=0` and inactive grain bypass the new field exactly. Existing saved
grain controls and seed are retained. There is no separate no-film switch. Existing grain amount and controls apply directly. The obsolete beta7 switch is ignored when reading sessions, recipes and backups.
Portra is a fixed donor, not an additional user-selectable film effect.

The CPU and disk-streamed routes share the exact particle model and full-image
coordinates; GPU retains the existing approximate sampling and blur model.
They are not pixel-identical. Preview/export grids and existing RNG layouts
also differ, so only same-grid/same-path determinism is promised. Grain can
change individual channel brightness; “no Portra colour transform” does not
mean grain has no stochastic chromatic component.

The donor uses normalized density midpoint per channel. Sublayer interpolation
uses the same reference bracket as the particle engine; its expected layer sum
is the clean baseline. A shared residual clamp of +/-2 optical-density decades
prevents extreme noise outliers from exploding the light signal.

Budget: GPU original-log copy adds 12 B/pixel; resident tile envelope is rounded
to 272 B/pixel. No-film active preview adds 48 B/pixel, and stream scratch adds
24 B/pixel for preserved original planes. The old ROI owner explicitly rejects
this new stage and falls back to normal/whole-frame preview until it owns the
independent field context. Full-frame render and streamed export are supported.

A cached Pipeline built with grain disabled must be rebuilt to enable a missing
donor. `with_params` is for calibration-equivalent updates; mobile caches key
on complete runtime state and rebuild correctly.
