# SpektraLab profiles, reviewed 120 frames and named presets integration

This is the historical pre-artwork verification report for `01deabb3`. The five
new selector illustrations were not yet implemented at that stage; their later
approved integration and validation are recorded in
`FIVE_ROLL_ARTWORK_INTEGRATION_20261106.md`.

## Scope and provenance

Independent integration branch based on official code51 source commit
`554ce76883582698c6330d88e749fd01e1eaa59b` (tree
`5707329a36396f7c1c9dda9a2e02b69fc2ae6788`). The version remains 0.1.21/code51.
No main-branch changes, push, APK build, signing, release, or existing package
replacement was performed.

Owner commits integrated once, in order:

- SpektraLab: `24623737`, `963bae05`, `2ab0e929`, `bea9acbe`, `34296e0a`.
- 120-only additions: `5f1cbde2`, `ec57d3dc`, `abd2ed9d`.
- Named presets: `c219f4d7862bcc6eef95b152889118a6d655d576`.

The combined implementation commit is `2aa396ba76c6ecad1b17765690bc3d79750a2366`.
There were no cherry-pick conflicts. Shared MainActivity changes were inspected:
format capabilities, film-border selection, notices, and preset controls all
remain present. New native/app profile copies are byte-identical; historical
profile data are unchanged. 120 source assets and production implementations
match their reviewed owner commits.

The integration adds a three-method cross-feature test and a missing explicit
Chinese translation of the SpektraLab notices entry. The latter was the only
error found by full lint; it was fixed rather than suppressed.

## Final Android verification

- Full Gradle production/resource compilation passed: all 183 Kotlin sources.
- All 284 existing test/helper Kotlin sources compiled together with Gradle.
- The additional cross-feature test compiled with the same Kotlin 2.0.21 compiler
  against the final production artifact and resources. It was not substituted
  for compilation of the existing test source set.
- All 33 selected host classes passed: **202 test methods**. This is a focused
  runtime suite, not execution of every test in the repository.
- Included: 71 profile/120 tests; 84 preset/regression tests; 3 cross-feature
  tests; and 44 official-code51 export-preview regression tests.
- Cross-feature coverage: all five new film IDs and silver paper through the real
  load dialog/catalog validation; complete parameter preservation; all three
  120-only films across five formats and both directions; latent direction
  retained while the border is off and after enabling default 6x6.
- The actual MainActivity 120-only entry flow passed; unavailable 135 choices and
  white holes remain unavailable.
- All 250 supported current 120 combinations matched preview versus float export
  exactly over **4,988,775 pixels**. This is not a final JPEG/TIFF or real-film
  authenticity claim.
- The complete 16-layout film-card matrix passed in its default single invocation,
  including high DPI and large text. Final host renders were inspected for the
  preset controls, Chinese preset dialog, and large-text film selection.
- Two generator/reproducibility tests passed for the finite v7 120 strips.
- Final full `lintDebug` passed: **0 errors, 84 warnings**. Warnings remain; no new
  lint suppression or baseline was added.
- Each host JVM used a distinct on-disk temporary directory, removed only after
  that JVM terminated. No runtime extraction directories remain from this run.

## Fresh native verification

The final native source was compiled afresh, not linked to the old Android engine.

- Android ARM64: Rust/Cargo 1.88.0, NDK 27.2.12479018, API26 linker,
  `precision-f64,adreno-gpu`, release/thin LTO, 16KiB page flags.
- ARM64 library: 9,509,728 bytes. SHA-256:
  `192522de959eb0e019868ad4baba90ebd5d1bdd1685e1af64f1a675213ede386`.
- All four ELF LOAD segments align to 0x4000. All 32 required JNI exports exist;
  no prior public JNI exports were lost.
- All **27 focused native tests** passed: 8 new profile tests, 3 stock/halo tests,
  2 catalog tests, 4 historical B&W tests, 10 complete profile-route tests.
- A separately freshly built host CPU library rendered the final app source
  assets through JNI: all **10 routes** (five films, direct and print) succeeded,
  with finite, non-flat output.
- The complete core suite remains **96 passed / 2 failed**. The failures are:
  `pipeline::resident_contract_tests::film_log_range_validation_and_split_halos_preserve_contracts`
  and
  `pipeline::resident_contract_tests::film_log_suffix_capture_fallback_and_validation_clear_stale_results`.
  Both also failed in an independent rerun of the pinned baseline binary, whose
  hash was checked, and its 26 core source/manifest files were verified against
  exact commit 554ce768. They are disclosed failures, not counted as passes.

The new ARM64 engine is at `native/artifacts/jniLibs/arm64-v8a/libfilm_engine.so`
in the independent worktree. Generated binaries are intentionally not committed
or copied into an existing release/package. The owner documents' earlier
statements about no rebuilt Android engine describe their pre-integration runs;
this section records the later ARM64 candidate build.

## Limits and evidence

No APK, installation, physical-device, real Adreno/Vulkan execution, x86_64
rebuild, final encoded-image parity, or manufacturer/cross-renderer calibration
was performed here. Filmroom's own grain/RNG/halo remain in place. SpektraLab
source-profile licensing and attribution are retained. Acros II and 800T still
have no approved 120 frame artwork; the new reviewed 120-only strips are HP5
PLUS, T-MAX 100, and Tri-X 400TX, with the evidence limitations in the 120 audit.

Machine-local evidence is under the sibling `filmroom-features-validation-20261106`
directory: original commands, complete build/lint/native/JUnit logs, source and
artifact hashes, `host-results.json`, `arm64-elf-summary.json`,
`source-integration-audit.json`, and `VALIDATION.json`. Review images generated
later by a separate review task use this final integration's compiled classes
and assets and are not additional device-validation evidence.
