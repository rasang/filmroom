# Local app backup v1

Independent feature branch based on beta4 `afa5be1d`. Not part of the frozen beta4 package.

Settings uses SAF CREATE_DOCUMENT / OPEN_DOCUMENT, no broad storage permission, no network or retained URI grant. Export is unencrypted JSON. A cancelled picker writes nothing. Disk I/O is off main; ViewModel survives rotation. Import shows counts and requires confirmation before settings replacement; recipes/history are merged, not replaced. Duplicate imports are idempotent. Conflicting recipe names are suffixed.

Allowlisted settings: language, reset on import, restore last photo (switch only, NOT source), RAW batch selection, single/clear history deletion choices, diagnostic minutes (selection only, NOT logs). Explicit confirmation discloses that deletion choices are restored. Missing old settings retain current values.

Recipes: saved ID/name/time and validated EditorState snapshot, with recursive unknown-field rejection and existing parameter validation/default migration. Never copy filesDir or SharedPreferences wholesale.

Export history: separate atomic metadata archive; allowlisted ID/time/dimensions/format/state/film/color-space/duration. No original/export media, names/URIs/paths, thumbnails, EXIF, parameter snapshot, errors or logs. Archived entries never run, retry, replay animation, or invoke media deletion. Active jobs are represented only as non-executable archived records. Existing real jobs win ID conflicts.

Input: 8 MiB limit, UTF-8 validation, strict JSON without duplicate keys or trailing tokens, bounded nesting/strings/arrays, version/type and field validation. Unknown backup or parameter versions fail before mutation. Two independent stores use AtomicFile, so process failure cannot truncate their data. A multi-store restore is NOT a cross-file transaction: if later storage/settings writing fails, explicitly report partial import, retain writes and existing data, and allow safe deduplicating retry. Never blindly roll back over concurrently created recipes/history. No success is reported for partial import. Failed export can leave an incomplete user-selected file; error tells user not to use it.

Tests: codec/settings allowlist, merge/name conflict/retry, malformed/newer/oversized input; settings SAF actions and cancel-before-write; history persistence/restart, active archive, URI exclusion, deletion isolation. JVM tests must run only after current package builder releases resources.

## Validation (2026-11-07)

213 production Kotlin sources compiled from current branch using exact AGP-captured classpath, plus a fresh incremental compile of the two final codec/SAF UI changes. Actual AGP javac/resources/classes tasks executed. Standard Gradle compiler was memory-blocked; no standard full-Gradle compilation claim. No native rebuild, signing or package publication.

Four focused test classes: 39 tests passed, zero failures/errors/skips (24 new backup tests plus 15 existing saved-recipe store tests). Current test helpers freshly compiled. Covers system Create/Open Document intents, explicit openable documents, confirmation/cancel-before-write, strict codec/settings/stock allowlists, malformed and oversized documents, duplicate merge/name conflicts, archived history restart/cleanup/media-deletion isolation, and existing recipe roundtrips. Not a physical-device SAF/provider test or full project regression suite.

Evidence: `/workspace/shared/filmroom-app-backup-validation/` contains current compiler input hashes/results, retained failed attempts, final focused log and JUnit XML. First test pass exposed missing explicit OPENABLE selector category; fixed and rerun all four classes.
