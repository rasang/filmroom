# Exact preview grain: bounded submissions and parallel parameters

This change preserves the sequential MT19937 state, seeds, 256-pixel sampler
kernels, Poisson-all then binomial-all ordering, distribution expressions, and
layer accumulation order. It does not substitute a coordinate RNG, omit grain,
or make a mobile speed claim.

## Queue ordering

Each `DispatchBatch::submit_chunk` optionally calls `write_buffer`, then
immediately submits the consuming dispatch. A subsequent write is not recorded
until that submit has happened. Prepared chunks use upload/prepare submission,
then sampler submission, then the next chunk. Uniform buffers are distinct
immutable snapshots for every dispatch. Dependencies use wgpu's ordered queue
resource transitions, not CPU waiting or unsafe raw Vulkan operations.

Pinned wgpu 24.0.5 `src/api/queue.rs` documents that write_buffer copies host data
immediately to staging and schedules the transfer on the next submit. Official
reference: https://wgpu.rs/doc/wgpu/struct.Queue.html#method.write_buffer .
Do not rewrite this as several writes into one shared slot followed by a single
submission containing all consumers: all consumers could see the last write.

Sixteen chunks form one batch, at most32 submissions with the prepass. The batch
wait retains the existing5-second timeout and cancellation polling. The sticky
numeric flag is read after the batch and never reset. Any failure discards the
whole stage's outputs through existing ownership/publication guards. Cancellation
is also checked before each submit; there is no cancellation exit between a write
and its consumer submit. Dropped/failed batches do not publish partial output.

## Parallel deterministic preparation

The existing `gg_parameters` expression runs once per invocation in op63,
then stores probability, saturation and lambda as three exact64-bit values.
The serial op52/op53 uses those stored bits, with unchanged MT consumption.
This moves interpolation and parameter divisions off the single sampler lane.
It still computes parameters twice across the full Poisson and binomial passes;
image-sized caching and independent-stream concurrency are deliberately absent.

Scratch occupies float lanes979712..982016, immediately after the sequential
input slot and below parameter storage1028096. It is9KiB, independent of image
size, and does not overlap sequential input, MT state or calibration tables.
Coordinate production does not use it. Prepass invalid arithmetic is ORed into
the sticky flag, and serial sampling exits if that flag is already set.

Every device admission additionally runs actual op63 then op64 on32 synthetic
pixels, comparing all9 encoded parameter lanes and particle/developed counts,
word consumption and sampled output against the original host model. Existing
3120 sampler cases are retained, giving3152 total. Arithmetic admission also
checks the same store/load bit transport. These gates run on each device request;
host/Naga tests do not claim that they have passed on a mobile GPU.

## Bounded resources and accounting

At most32 uniform/command submissions are pending. Explicit sequential payload
is16*3072=49,152 bytes plus32*160=5,120 bytes of uniforms. The internal budget
reserves64KiB per submitted command plus payload, under a4MiB admission envelope.
The grain stage plan explicitly adds4MiB to hostBytes (16→20MiB) and to
combinedPeakBytes; admission rejects plans exceeding the unchanged768MiB hard cap (and the caller's unchanged request budget).
The existing64MiB unknown-driver and64MiB helper allowances are NOT consumed or
reduced. This conservative extra logical reservation is not a measured or
enforceable bound on opaque driver allocation. Fixed device planes and transfer
buffers do not grow. Coordinate mode uses one chunk/batch but reserves the same
extra4MiB, keeping the admission contract simple and conservative.

Final readback uses at most262,144 pixels, separately from the256-pixel MT chunk:
1MiB raw bytes,1MiB float conversion and2MiB encoded output, at most4MiB of
transient host data. No sampling batch is pending at readout. All byte/dispatch
metrics count actual operations; dispatchWaitMs includes encoding/submission and
batch completion, uploadMs covers enqueue work, readbackMs covers map completion.
No speculative wait-count field is added to closed summary schemas.

For a sequential plan, C=chunkCount and B=ceil(C/16). Numeric flag reads are
1+3*(layers*2*B+micro*B+1). Dispatches include2*C/layer additional preparation
passes and2 additional admission dispatches. Admission readback adds1792 bytes.
Native and Android strict expected-count validators must change together.

## Validation and remaining risk

Tests cover batch resource rejection, partial batches, nine seed/layer streams
with4097 pixels crossing chunk/batch/twist boundaries, exact parameter transport,
sampler tuples/output/consumed words, and scratch nonoverlap. Existing shader
Naga and full-image oracle tests remain relevant. No physical GPU is available
in this environment. Millions of distribution calls still run serially per MT
stream using software binary64; reduced synchronization and parallel preparation
are substantive improvements but do not establish a particular preview latency.
