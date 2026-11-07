# Approved five-roll selector integration

## Scope

This local functional update follows integration commit
`01deabb325562ff9ba2abb1f25b04104020836dc`. That earlier integration contained the
five new scientific profiles, three reviewed 120 strips and named presets, but
its five new selector illustrations were still missing. This update completes
that visual item after user approval of the five model review images.

The APK asset set gains only five original lossless 240 x 240 RGBA WebPs, copied
byte-for-byte from the approved editable model deliverable. The five assets add
105,896 bytes. All 20 previous WebPs remain byte-identical. The complete selector
now maps 27 profile IDs to 25 physical-stock illustrations. The manifest was
reconciled to the actual existing artwork bytes, including previously omitted
BW models; no earlier image was changed. The source-model pack is separate.

Each new image shows simplified 135 and 120 packaging. This is not a frame-strip
capability claim. None of the five new profiles enables a 135 strip. Only Kodak
400TX, T-MAX 100 and Ilford HP5 PLUS enable their reviewed 120 strips. Acros II
and CineStill 800T keep frame controls disabled. 400TX is distinct from 7266;
800T is distinct from the Vision3 5219 can. Scientific film size is unchanged.

The runtime change is limited to five exact-ID artwork mappings. Frame-capability
code has a comment-only clarification. MainActivity, EditorViewModel, EditorState,
all border implementations, native sources and scientific profile data remain
unchanged from the previous integration. Source/byte preservation is checked.

## Attribution

The asset provenance and manifest record source product pages, the approved
source archive SHA-256, simplified geometry and label limitations, and the
separation between original artwork and third-party profile data. No reference
photograph, Blender file, font binary, or review-board image enters the APK.
Labels were originally typeset with DejaVu Sans, not traced proprietary logos.
DejaVu is Bitstream Vera based, with DejaVu changes in the public domain; it is
not SIL OFL. Existing project licensing and profile attribution are preserved.

## Verification

All 183 production Kotlin files and 286 test/helper Kotlin files compiled with
Gradle. Two final test-only changes were subsequently compiled with the same
Kotlin 2.0.21 compiler against the exact compiled production/test classpath:
`ReviewedFilmRollArtworkHostTest` received a per-stock history fixture reset,
and `SpektraLabProfilesHostTest` replaced obsolete missing-art expectations with
approved exact-ID bitmap expectations while retaining frame capability checks.

Full debug lint also passes with 0 errors and 84 warnings.

The final per-class runtime matrix passes **211 methods in 35 host test classes**.
This is a focused suite, not every repository test. It includes the prior
33-class / 202-method functional matrix, three new artwork methods, and six
carousel-position regression methods. The initial batch's obsolete missing-art
assertion and exit 1 are preserved separately; its corrected class passes 3/3.
Final totals count unique methods, not reruns, and do not relabel that initial
batch as an uninterrupted pass.

New checks cover all packaged manifest hashes and profile mappings, exact five
approved image hashes, bitmap dimensions/alpha bounds, physical 135/120 versus
actual strip capability, and real MainActivity selection of each new stock.
Activity checks preserve scroll position, explicit route, scientific film size,
brightness/grain, repeated selection, undo/redo and category round trips. Three
reviewed stocks expose five 120 formats without 135; the other two remain
unsupported. Five screenshots are saved from the actual application view.
The existing 16-case font/density layout matrix also passes.

The rerun 120 preview/float-export parity matrix passes all 250 combinations and
4,988,775 compared pixels. Named-preset and new-profile cross-feature tests pass.
The generator and native tests from the previous integration are carried forward
only where their exact source inputs are unchanged, not claimed as rerun here.

## Native and release boundary

The prior freshly rebuilt ARM64 engine is retained byte-for-byte:
`192522de959eb0e019868ad4baba90ebd5d1bdd1685e1af64f1a675213ede386`,
9,509,728 bytes. Its native source and scientific data are unchanged. It was not
rebuilt a second time for this artwork-only update. The prior 27 focused native
tests, 10 actual host JNI routes, 32 JNI exports and 16 KiB ELF alignment evidence
remain tied to those identical inputs. The full core suite's two documented
film-log mock failures also reproduce on the exact code51 baseline; see the
previous integration report rather than treating them as new artwork failures.

This functional tree retains version 0.1.21/code51. Beta version metadata,
release assembly, signing, identity checks and delivery belong to the separate
beta release step. This integration does not claim an installed APK, Android
hardware/GPU execution, or full renderer pixel equivalence. No main merge or
GitHub publication occurs here.

## Evidence

The external audit bundle `filmroom-five-roll-integration-validation` contains
full Gradle/K2 logs, final per-class results and original failed expectation,
source and artifact hashes, copied application screenshots, asset preservation
proof, and a complete executable class list. `VALIDATION.json` records final
lint and execution counts. The earlier `FEATURE_INTEGRATION_20261106.md` remains
a historical report of the pre-artwork functional integration.
