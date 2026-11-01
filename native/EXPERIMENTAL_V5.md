# V5 full-resolution local engine

V5 uses stage-separated disk-backed physical processing. It has twelve small whole-pipeline bit-exact comparisons, real 4000×3000 and 8192×6144 original-size exports, large-image cancellation, and bounded write-failure cleanup checks. These are Linux host results, not Android runtime results.

Exact full-image FIR/IIR spatial passes use bounded memory independent of halo. Film/paper, grain, halation and DIR remain physical stages. There is no server and no silent resize. See STREAMING_STORAGE.md, PORTING.md and the final V5 validation manifest supplied with the release evidence. Earlier V4 artifacts are historical and are not part of this source package.

The source bundle includes the full profile data inventory. The app exposes only its selected film/paper profiles; the full inventory does not mean all stocks are selectable in this release.
