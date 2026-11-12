# Filmroom 0.1.22.1-beta2

- versionCode: 100; package: dev.filmroom; original release signing key.
- Based on beta1; retains vendor-neutral Vulkan admission and all existing device, memory and completion-safety checks.
- Separates Front preprocessing from spatial render tiling, preserving the actual halo required by PrintLinear preprocessing.
- Plans main rendering and prefix/suffix splitting independently of preprocessing tile count; no longer requires the combined chain to fit before trying viable split plans.
- Adds bounded preprocessing/render progress and updates cache geometry keys.
- No RAW orientation changes or independent Live-preview experiment changes.

## Validation boundaries

The prior local-only implementation was reconstructed after workspace recovery; this release is bound to the newly reviewed and tested source, not the unavailable old commit.
Targeted native geometry/progress, cache replay/cancellation and Front pixel/seam tests are rerun on the cloud software Vulkan environment. Known baseline geometry assertions are documented separately in the release result; no physical-device acceptance is claimed.
The original product GPU completion timeout remains unchanged. Earlier full-resolution diagnostic runs required a longer wait in a separate diagnostic build; those results do not establish full-resolution success with the shipping timeout on a phone.
