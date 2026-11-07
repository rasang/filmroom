# Named editing presets

The editor now has Load preset and Save preset buttons immediately to the left
of Compare. Compare keeps its existing position, gesture, and original/effect
toggle. All three controls retain 48 dp targets and share the existing reserved
bottom control band, outside the image viewport.

## Scope

A named preset is the complete `EditorState.json(includePresentation = true)`
snapshot, with the latent orientation included: film and paper IDs; all scientific
controls (including inactive values), route and remembered route preference; GPU/output color choices; border/120
format/orientation; crop and composition; date/exposure imprint settings,
including a manually entered date/time. There are currently 89 constructor
fields. The preset-only snapshot adapter serializes medium portrait preference
even while 120 is inactive, without changing engine/session/queue JSON identities.

A preset does not contain a photo, source path, URI, EXIF, preview bitmap/zoom,
queue job, export format/quality/size, or app preferences such as language and
restore-last-photo. Applying one to a different photo intentionally reuses its
saved normalized composition and custom date; the save dialog states this scope.
Rendering still clamps composition to the target image's geometry. Ordinary photo
import keeps its existing reset/crop/date semantics.

## Interaction

- Save captures the current state and asks for a 1–60 Unicode-character name.
  Whitespace is trimmed and NFC normalization is applied; empty/control-character
  names are rejected. Case-insensitive duplicate names are rejected, never silently
  overwritten. The same name can be used again after deletion.
- Loading presents a bounded scrolling, single-selection list, with content-height
  rows so long names wrap at large font sizes. Selection alone
  changes nothing. Apply sends all parameters through one normal editor commit,
  so one Undo restores the prior state and Redo reapplies it. An equal preset does
  not add a history entry but exits comparison view.
- Cancel, Back, outside dismissal, and cancelling deletion do not edit or save
  anything. Deletion asks for confirmation and never edits the current photo.
- Save and Apply are bound to the dialog's photo key and original parameter
  snapshot. A changed photo, changed parameters, busy editor, or composition draft
  blocks a stale action. Repeat taps cannot open stacked copies or duplicate writes.
- Rotation restores an unsubmitted name draft or list selection only when the
  same photo and exact editor state remain. A pending disk write is not resubmitted.
  A backgrounded dialog is dismissed. Undo history remains the normal in-memory
  editor history; named presets survive application restarts separately.

## Persistence and compatibility

`filesDir/saved-presets.json` is private to the app and independent of source-photo
cache cleanup, queue recovery, and the restore-last-photo preference. The application
currently disables Android backup, so uninstall/clear-data removes these presets.
Presets can be shared as offline QR images and imported after explicit confirmation; see [QR sharing](PRESET_QR_SHARING.md).

The format has a top-level version, UUID identity, normalized name, creation time,
full parameter JSON string, and SHA-256 of that exact string. A process-wide lock
serializes read/modify/write, and Android `AtomicFile` provides interrupted-write
recovery. Reads and writes run off the main thread. Successful Save is shown only
after the file is written and read-back bytes match. A bound of 200 entries / 2 MiB
limits damaged/oversized data and keeps each operation small.

Unknown top-level versions, malformed data, duplicate IDs/names, or checksum
failures block mutation without silently resetting or overwriting the file. A
newer parameter schema or unsupported nested version/enum can remain in the list,
but Apply reports incompatibility. Unknown film/paper IDs are checked against the
current packaged catalog, so newly integrated film stocks work without a second
preset-specific list. An incompatible entry can still be explicitly deleted.
Missing older optional editor fields use the existing `EditorState.fromJson`
defaults. Future incompatible field/schema changes must bump the parameter or
preset format version, rather than relying on an older reader to guess.

## Verification

`./gradlew :app:verifySavedPresetCompatibility` is a dedicated mandatory gate for
`check` and release APK/AAB packaging. It runs the recursive coverage, real-file
store, preset UI, integrated-profile, QR codec, and QR UI host suites with one worker at a time,
starting a fresh worker for each class.
The gate always executes rather than accepting an old up-to-date test result.
It does not build the native engine or change the saved-preset file format.

`SavedPresetCoverageHostTest` discovers fields independently of JSON serialization,
recursing through imprint, composition, crop, and crop transform. Every configurable
leaf must have exactly one legal, non-default probe that changes only that leaf.
At the current schema there are 103 configurable leaves; the fixed
`imprint.layoutVersion` schema marker is checked separately, not counted as a
control. A new leaf, unknown nested type, duplicate probe, or probe left at its
default fails coverage rather than silently passing.

Each probe is saved to an actual private-file store and read by a fresh store
instance, with whole-state equality and explicit non-null JSON-key checks. A
mutation test removes every probed JSON field in turn and requires detection.
Combined probes and disabled/latent presentation settings are also round-tripped.
A frozen legacy imprint-v1 snapshot verifies older optional-field defaults and
byte-preserving reads/unrelated writes. Existing future-version, corruption,
atomic-write, undo/redo, and stale-dialog checks remain in the gate.
The two QR suites additionally require the frozen v1 code, additive missing-field
defaults, malformed/future-code rejection, share access boundaries, and confirmed
save/cancel behavior. Omitting either QR class fails the gate.

When adding a parameter, add its probe and update the serializer/deserializer
together. For a new nested state type, add explicit recursive handling; do not
generate probes from serialized JSON or waive the field with a blanket exclusion.
Keep any unavoidable schema-marker exclusions narrow and justified. Format changes
still require an explicit compatibility/migration decision. These checks catch
omissions; they do not make the handwritten serializer automatic.

New tests: `SavedPresetsUiHostTest`, `presets.SavedPresetStoreHostTest`.
Existing regression tests: `PresetResetSettingsHostTest`,
`PhotoPreviewClearanceHostTest`, `FilmSelectionPreferenceHostTest`,
`CropModelHostTest`, `EditHistoryTest`, and imprint tests.

These are host Android/Robolectric tests and renders, not physical-device
acceptance. No version, signing configuration, native engine, or publication
change is part of this feature branch. The user selected option 3: a preset list with a downward apply arrow and
a matching list with a plus for save. Both are editable 24 dp VectorDrawables
inside the unchanged 48 dp touch targets.

The focused host suite covers all 89 nondefault fields and nested presentation,
all 120 formats and latent orientation, independent store instances, name
validation, corrupt/future data protection, interrupted-write recovery, save and
delete cancellation, one-step undo/redo, stale dialog rejection, rotation, and
unknown catalog IDs. UI renders cover 320 dp and 360 dp portrait plus 740 × 320 dp
landscape, with 1×/2× fonts, and large-font Chinese/English dialogs.

The final focused run passed 84/84 tests across nine classes: 15 store, nine
preset UI, 11 existing preset/settings, one preview-clearance, seven film-choice,
six crop, three history, 11 imprint-content, and 21 imprint-contract tests.
Production Kotlin and focused tests compiled successfully; final dialog and UI
test changes were compiled separately with Kotlin 2.0.21 and used first on the
verified runtime classpath. Full Gradle/lint and APK verification of the combined
feature branches remain integration checks. Exact source/artifact hashes and
per-class logs are recorded in the separate validation report. A host render is not a hardware screenshot. The final
integrated source tree still requires its release-build and physical-device checks.
