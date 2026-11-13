# GPU export route policy (beta5)

This changes route selection, not pixels, tile geometry, memory budget, or effect kernels.
The export allowance remains `(available - low-memory threshold) / 3`, capped at
1024 MiB, with existing joint-memory, storage-binding and temporary-storage checks.

## Ordinary FilmLog route

After global-field construction is finished, plan both full and split alternatives
against their actual live reservations. Prefix retains camera fields and kernels;
suffix releases camera fields and kernels. Optional preview reservations remain
charged. Both split plans must fit; a valid full plan survives a split capacity
failure. Invalid inputs are not converted into a capacity fallback.

For the same image and halo, compute expanded pixel totals by clipping every edge
tile. Call these `F`, `P`, and `S`; image pixels are `N`. A full chain applies both
operator groups over `F`. Splitting applies prefix operators over `P` and suffix
over `S`. We require `P <= F` and `S <= F`, so no common operator gains domain work.
No submit count is treated as a FLOP or timing weight.

Splitting adds file and GPU boundary overhead. Exact ordinary-route spool bytes:

- full: `12 * (F + N)`
- split: `12 * (P + S + 2*N)`

Transfer/readback domains also increase from `F` to `P+S`; suffix additionally
copies FilmLog into its GPU working buffer. The conservative admission margins are:

- `P+S <= 1.6*F` (at least 20% mean operator-domain saving)
- split file bytes at most 2 times full
- split tile count and row-I/O call count at most 3 times full
- full-frame resident plans always remain full

These margins are explicit heuristics, not device-calibrated execution times or a
guarantee of speedup. Marginal cases remain full. The beta is for same-source,
same-parameter, comparable-temperature device A/B tests. The former 64-tile cutoff
no longer controls this ordinary route. Existing actual phase/geometry diagnostics
plus `gpu_route_split_geometry_v1` / `gpu_route_full_geometry_v1` identify the choice.

## Physical back-reflection

Physical halation is NOT a two-phase chain. It includes scatter, directional FIR
passes and multiple disk intermediates. It retains its separate previous policy
(and capacity fallback), tagged `gpu_route_physical_policy`; this version does not
claim to optimize that route with the ordinary model.

## Verification

Automated cases cover the reported 7347x6767 geometry across 512, 594, 869, 992 and
1024 MiB, clipped edge tiles, whole/small-halo cases, narrow geometry, forced routes,
capacity errors, physical policy separation and overhead vetoes. Output equivalence,
cache and cancellation tests are separate from policy tests. Software Vulkan tests,
when run, are not phone-GPU or device performance validation.
