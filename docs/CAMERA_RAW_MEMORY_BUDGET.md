# Camera RAW memory ceiling in 0.1.19-beta.1

User-authorized NEF/CR2/ARW per-operation ceiling: **256 MiB** (formerly128). This is an admission estimate, not an allocator hard limit or a promise against Android low-memory process termination.

- Content routing, not the filename suffix, selects the camera policy. DNG remains128MiB at the Kotlin entry; HEIC, export encoding, engine policies and their existing limits are unchanged.
- The live device policy may choose a lower budget (minimum128MiB). Android low-memory state, available-memory/threshold headroom and the shared two-worker ledger remain enforced. One frozen request supplies both the native JSON value and exact ledger charge; existing16MiB safety per worker remains additional.
- Metadata-only camera operations retain their32MiB reservation and do not allocate a full256MiB worker lease.
- Native ordinary camera, Nikon HE/ordinary rows, smallNEF and ARW6 admission accept the explicit request up to256MiB;257MiB and overflow fail closed. Native generic DNG Budget::default remains128, and ARW6 legacy wrapper callers retain128 unless using the explicit-budget API.
- Existing streaming tile/strip maxima,52MP limit,1GiB applicable scratch budgets, algorithms and output resolution are unchanged. Streaming row planning remains based on the128MiB tier. The whole-integer fallback preserves old admissible strip sizes; newly admissible larger integer domains use no more than the historical96MiB tile allowance, with128-row maximum.
- This Beta has host/regression/build verification only; device testing is still required. No claim of a global disk reservation or universal camera compatibility is made.
