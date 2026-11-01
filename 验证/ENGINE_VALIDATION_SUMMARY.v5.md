# V5 actual-size native engine validation

## Verified user-domain result

The production `mobile::export` path completed both requested synthetic sizes at
their original dimensions, with default384 MiB operational budget and all
default Portra400/Endura physical effects active. No image resize, finite halo,
grain/halation omission or server was used.

| Image | CPU wall time | Child peak RSS | Native logical scratch peak | Output |
|---|---:|---:|---:|---:|
|4000×3000 /12,000,000 pixels|107.441 s|38,352 KiB|1,056,000,000 B|144,000,000 B|
|8192×6144 /50,331,648 pixels|382.875 s|38,516 KiB|4,429,185,024 B|603,979,776 B|

These are cloud Linux x86_64 release CPU measurements, **not phone timings or
Android runtime memory measurements**. RSS is the isolated child process's
Linux `wait4` high-water mark, excluding the application, decoder and OS file
cache. Reports contain all output finite checks, exact dimensions, hashes and
source/binary identities. This is large-size execution evidence, not a
full-size comparison against an in-memory Python render.

## Correctness and failure handling

- Twelve small complete exports /757,653 final f32 samples matched the same
  whole-image CPU pipeline bit-for-bit, including portrait/landscape chunk
  edges, three films and papers, and public photographic-control extremes.
- Exact disk spatial tests separately checked64 shape×sigma combinations /
  356,576 samples, including FIR/IIR threshold, huge sigma and narrow8192/6144
  dimensions. Maximum difference was0 for those cases.
- Actual12MP and50MP cancellation checks returned `CANCELLED`; input hashes were
  unchanged and owned scratch plus partial output were absent.
- A12MP child with1 MiB `RLIMIT_FSIZE` and ignored `SIGXFSZ` returned `IO_ERROR`
  and cleaned up. This injects **EFBIG, not ENOSPC**. It does not fill the disk.
- Additional small cancellation checks cover a spatial stage and cancellation
  after final output bytes were already written.

## Storage / speed tradeoff

Measured native scratch high-water mark is88 bytes/pixel, independently matched
to a maximum11 live f64 planes. The first verified release deliberately keeps
the conservative **128N+64 MiB additional native-scratch reserve**. The app also
accounts for input/output, encoded output and its reserve. Preflight reservation
is not a claim that every reserved byte is written or simultaneously allocated.
The benchmark JSON field `reservedNativeBytes` is the **152N+64 MiB total**,
including 12N input and 12N output: at 50,331,648 pixels this is7,717,519,360 B.
The engine-only additional scratch reserve is6,509,559,808 B. The App adds its
encoder/reserve needs separately; neither figure is measured live scratch.

Storage-backed full-image physics is I/O-heavy: the50MP run's42 spatial passes
alone read33,822,867,456 bytes and wrote the same amount cumulatively; pointwise
passes add more I/O. This is a correct bounded-memory CPU baseline, not a claim
of fast interactive50MP processing or low flash-write volume. Native scratch is
removed on success. Process-death recovery belongs to the app's private journal.

## Fidelity boundaries

The color pipeline remains physical film/paper processing with spectral stage
precomputation, not a composite RGB look LUT. Internal33³ stage interpolation
retains its separately measured approximation. The existing large-sigma IIR
Gaussian approximation is retained, but V5 removes V4 finite-halo truncation.
Coordinate-based grain preserves the physical distributions, not NumPy's exact
sequential random texture. Preview and original-size grain are not pixelwise
identical. Decoding/orientation/color interpretation is a separate component;
these results do not by themselves establish RAW/camera-format support.

## Evidence

- `results/file_export_v5/{12mp-default,50mp-default,12mp-cancel,50mp-cancel,12mp-write-fault}.summary.json`
- `results/v5-streamed-reference/provenance.json`
- `results/v5-disk-spatial-validation.json`
- `native-v5/STREAMING_STORAGE.md`
- Final V5 native manifest supplies Android ABI hashes and exact final test
  scope. V4 manifests and timings remain historical, not relabeled as V5.
