# beta30 combined preview and original-access fixes

Version: 82 / 0.1.30-beta30. Based on beta29 plus the following reviewed fixes:

- Preview-only warmup clears the surface to the page background rather than showing the end-of-animation photograph. The 3-second timeline begins after bounded preparation. Export timing and composition are unchanged by this preview fix.
- Once a source has been identified/extracted as Live video, export requires a decoded first frame and does not publish a successful static/frozen result after a later decode failure. Decode errors are explicit, and pending output cleanup is retained.
- Prepared original audio can join an already-started preview at its current timeline position. Pending callbacks are canceled on stop/replay; this does not replay audio already missed and is not a guarantee of device-independent gapless MediaPlayer switching.
- Original access recovery accepts only readable originals or Android-provider-certified equivalent media URIs. It never guesses IDs, names or private filesystem paths. Persisted recovery updates the record, compare-and-set token and page-cache identity together; failed writes remain session-only and are not cached as durable links.
- The missing-original dialog supports requesting photo access and retrying, while retaining explicit reselection. Old picker URIs that Android cannot map still require reselection. Missing/deleted files are not recreated.
- New metadata-only backups preserve validated crop/border geometry, presentation parameters and capture metadata. Old backups remain readable but cannot recreate omitted fields. GPS/diagnostic text is not added to backups. This is not a full original-media backup; embedded Live bytes are never re-encoded by the recovery flow.

## Validation and limits

- Final combined targeted/affected host regression: 313 tests, zero failures/errors/skips.
- Required saved-preset/backup compatibility gate: 122 tests, zero failures/errors/skips.
- Full release lint completed with 0 errors and 113 warnings.
- Integrated warmup clear rendered through host GLES: portrait and landscape surfaces clear all 330,000 pixels without sampling either photograph.
- An attempted all-class host suite did not complete: Gradle daemon disappeared. Three failures printed before interruption were independently reproduced on unchanged beta29: an outdated border asset-count assertion, an outdated toolbar label assertion, and an unresolved pre-existing async candidate-border UI timeout. No full-suite pass is claimed; those tests were not silently changed or removed.
- Real Android uninstall/restore provider mapping, MediaCodec playback and late-audio switching remain unverified without a device. The existing prohibition on starting an emulator was respected.
