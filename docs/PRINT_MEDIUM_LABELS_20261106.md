# Print-medium colour labels: diagnosis and correction

## Evidence from the reported screen and shipped build

The screenshot's selected paper button says **Ilford Multigrade IV RC**. The
shooting-film selection and explicit output-route setting are not visible, so
neither is inferred from the appearance of the photograph.

The signed build53 APK (0.1.22-beta1, source
`5a11522c4ae37d1989c4df76912f6e581d40f272`) actually contains the Ilford profile
with `channel_model: bw`, `support: paper`, `stage: printing`, three distinct
silver emulsions, and all 81 spectral channel-density rows equal to `[1,1,1]`.
The shipped JSON is byte-identical to source (SHA256
`0f776f1f26f04aca4ec438ed377dc1acf4073dc1a8ae32c51607a6cb1a02521d`).
It is a black-and-white silver paper, not a colour dye paper. Colour camera film
can intentionally be printed through it and the result is monochrome.

The confirmed defect is the UI's old inference that every catalog `kind=paper`
is "Color paper". The kind identifies the physical support, not its colour
model. This wrong suffix appeared in all four supported languages. The loaded
paper ID is not remapped by the selection handler, and the native pipeline
fingerprint already contains both exact film and paper IDs.

The existing film-selection policy remains intact: a stock-default paper tracks
the film's default when appropriate; a manually different paper is retained.
A retained silver paper is still a valid intentional choice for a colour film.
No inference is made that the user's particular screenshot used a colour film
or that they had manually retained a prior paper.

## Change

- Share the existing monochrome print IDs between state capabilities and UI
  classification. There is no new native classification or profile mutation.
- Show the same explicit material type on the current selection button and in
  the picker: colour paper, B&W paper, colour motion-picture print film, or B&W
  motion-picture print film.
- Show a short explanation when a silver print medium is active in the print
  route: colour film also produces a black-and-white print on that medium.
- Supply Chinese, English, Japanese and Korean strings, including the Chinese
  fallback and stable message-key registry.
- Keep scientific profiles, native rendering, saved IDs, film selection,
  calibration data and print/direct route behaviour unchanged.

## Validation

Five focused Android host classes passed all **17 tests**:
`PrintMediumLabelsHostTest` (4), `PrintMediumActivityHostTest` (1),
`SpektraLabProfilesHostTest` (3), `BwProfileStateHostTest` (3), and
`ResponsiveLayoutHostTest` (6). The new tests cover all 10 print-medium catalog
records against their actual scientific profiles, four localized material
categories, manual/default paper retention, saved state, and 16 real Activity
picker selections (four material categories in each of four languages). They
verify the selected paper ID reaches the renderer callback without changing the
colour camera film, and that labels and explanatory text agree with that ID.

The focused build compiled six changed production Kotlin files and two new
test files against the frozen build53 host classes. AAPT2 relinked the actual
resources and dependencies with all **1,108 existing resource IDs preserved**
and only three new string IDs. This is a focused real Android/Robolectric host
overlay, not a fresh full Gradle build, device test, new APK, or release.
An optional broader pre-existing `LocaleHostTest` run was terminated by the host
with exit 137 before completion (no assertion result); it is **not counted as
passing**. The task-specific four-language checks and their final rerun passed.

The frozen build53 host CPU JNI library (SHA256
`e3493c628271b8f45e2151f452f39417fcbaf6c45b4ee19738ac9b16e0599fbe`)
rendered the actual shipped Android profile assets using 19×13 synthetic colour
pixels, no grain/halation/scatter, and the print route. All **six probes passed**.
Maximum RGB spread within a pixel was:

| Shooting film | Ilford Multigrade IV RC | Portra Endura | Kodak 2302 |
| --- | ---: | ---: | ---: |
| Portra 400 | 0.00099564 | 0.49205753 | 0.00022113 |
| CineStill 800T | 0.00088364 | 0.44441554 | 0.00018466 |

Thus both colour camera films retain a colour response on the colour paper and
produce near-neutral silver prints on the two black-and-white media. This is
not a GPU/device reproduction of the user's image, nor a claim that the
screenshot reveals which camera profile or route was selected.

All 423 files under native rendering crates, native profile data, and Android
engine-data assets retain their pre-fix SHA256 hashes. The only native-tree
addition is the reproducible host-JNI probe source; no native code or data was
changed. Private evidence includes source fingerprints, shipped-APK binding,
resource-ID binding, class logs, JNI metrics, and four real UI screenshots.
Those UI screenshots use a solid placeholder through a fake pixel renderer to
isolate interface behaviour; they are not monochrome-image rendering evidence.

The exclusive Android validation window ended with no remaining JVM. Temporary
per-test extraction directories were removed. A 21 MiB resource/class overlay
was explicitly handed to the halation worker for its separate controls test;
it is not a distributable artifact.
