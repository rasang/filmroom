# Archived GPU research checkpoints

These patches preserve independent experimental source. They are not integrated into the application and are not a release, a full-chain quality pass, or evidence of physical-device performance.

## Reconstruct the source

All patch bases are available from the public performance branch at commit `b53002f4e359a66d6ae87660920b97e437d7958e`. Use a separate clean checkout of that exact commit. Never apply these patches over an active development checkout.

- R28: apply `r28.patch`.
- R29: apply `r28.patch`, then `r29.patch`.
- R30: apply `r28.patch`, then `r30.patch`. R30 is a separate descendant of R28, not R29.
- R31: apply `r28.patch`, then `r30.patch`, then `r31.patch`.
- Continuation: apply only `continuation.patch` to the public base. This restores six changed files from the independent continuation experiment, not its original full historical build.

Use `git apply --check` before each `git apply`. Binary-format algorithm source is embedded in Git binary-patch format. All published file bytes can be checked against the corresponding SHA-256 manifest. The patch overlays retain other files from the public base; they are not exact directory exports of a historical worktree. Only manifest-listed files constitute the recovered checkpoint.

`verify_archive.py` verifies patch application and all retained manifest file hashes through an isolated Git index, without changing your checkout or running builds. Run it from a clone containing the public base and this archive, for example `python research/checkpoints/verify_archive.py`. It needs Python 3 and Git only. It writes temporary indexes and necessary Git objects, but does not change branches, the normal index, or source files.

## Intentionally withheld fixture

R30/R31 refer to `native/crates/film-engine/src/research_optics64_point/actual384_params.json`, an actual rendering-parameter fixture. Its content is intentionally excluded; its SHA-256 is recorded in the manifests. No photo, RAW image, log, local execution report, generated Python bytecode, or build cache is included.

The code is recoverable, but that private fixture is NOT recoverable from this repository. Test compilation or execution paths that require it are not self-contained. Obtain the original authorized fixture separately and check its recorded hash before reproducing those tests. Do not substitute guessed values and call the result the original experiment. Other tests may also require external original-image-derived inputs; this archive does not publish those inputs or claim end-to-end reproducibility.

## Validation scope

The publication checks cover source identity, patch application, static dependencies, and privacy filtering only. No new build or runtime test was run for this archive.

Historical scoped evidence recorded by the project: R28/R29 exposure work had exact complete-384 exposure and fault-contract checks; R30 had ARM type-check and 20 focused host/composer checks. Those results are not full-image-chain, 50MP, Android-device, or Adreno success, and their private execution evidence is not in this archive. R31 point-GPU-owner runtime validation remained pending at capture. The full scientific-chain quality threshold remained unresolved. Continuation's six-file snapshot has no new validation claim.
