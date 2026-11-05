# Exact grain GPU implementation and verification status

This path retains the original distribution formulas, sequential MT19937 preview
streams, coordinate SplitMix64 export streams, seeds, random-word order, software
binary64 operations and layer accumulation. It does not use Gaussian distribution
approximations, change the image size, or omit dye-cloud/microstructure effects.

## Ordered bounded execution

A `DispatchBatch` owns an immutable upload arena and one command encoder.
Upload-to-working-buffer copies are interleaved with their consuming dispatches;
uniforms remain distinct until the GPU fence. Sixteen sequential chunks form a
batch with one queue submission. Repeated queue writes into a shared slot before
submission are not equivalent and are not used. Cancellation before submission
drops the encoder; submitted work retains its resources until a completed fence
or confirmed helper termination. Numeric failure is sticky for the request.

Sequential preview executes the whole Poisson image pass before the binomial
image pass. Each chunk contains at most 256 pixels. Coordinate export uses at
most 16384 pixels per chunk. Preparation and sampling are separate entry points:
63 prepares Poisson, 52/65 samples it, 66 prepares binomial using the actual Poisson
result, 53/64 samples it. The old combined 51/61 giant entry points are not compiled
into the new production families. No RNG restart occurs between phases.

## Bounded ALU representation

Only selected deterministic arithmetic regions of the original rejection code
are encoded as typed software-binary64 instructions. Original branching,
comparisons, rejection loops, random sampling and expression ordering remain.
The unchanged original generic sampler is the offline oracle.

Binding 8 contains a 4800-byte immutable code prefix (93 Poisson and 207 binomial
instructions, 16 bytes each) followed by 544 bytes per frame: 72 prepared f32 lanes
holding encoded exact values, plus 32 software-binary64 registers. This is 144064
bytes for preview/admission capacity 256 and 8917696 bytes for coordinate capacity
16384. Sequential sampling uses one live register frame while retaining each
pixel's prepared fields. Coordinate invocations own distinct frames. Diagnostic
frames never index the image-sized buffers, including 1×1 images. The host plan
also reserves 9600 bytes for the code copies/staging. Shader/source hashes and
closed layout/version checks reject drift before allocation.

## Unchanged admission and counters

The full gate retains 3120 original particle tuples, 96 binomial boundaries,
64 stored-prepass tuples, 96 independent zero-rate checks, 98304 random words,
and 1280 arithmetic checks. The reported sample census is 3280; density RMS uses
3184, excluding the fixed-n boundary tuples. All sample values, random-word
consumption, steps, failure transport and state/cursor checks remain required.

Diagnostic dispatches are 25+4*ceil(3120/capacity), hence 77 sequential or 29
coordinate. Production layers use 4*C+1+S dispatches. Readback census remains
1269184 bytes and the existing flag-read formula remains unchanged. Native plan
JSON and strict Android validators must agree; old memory/dispatch fixtures are
not accepted as evidence for this layout.

## Evidence and remaining gates

The standalone observed/plain dual-ALU software-Vulkan harness passed 34981
observed samples, 559696 observer fields, 163 full MT snapshots, 24 negative cases
and 2 cancellation cases. Whole-image sampling fixtures preserved the original
CPU tuple/output/random-state results. This is genuine Vulkan execution on
SwiftShader, a software CPU backend, not an Adreno device or a speed result.

The combined production layout and complete grain graph have separate tests for
all retained admission checks, 1×1 images, 65×65 sequential images crossing 17
chunks, 129×129 coordinate images crossing 16384 and wrapping origins, padded
lanes, malformed descriptors, original effects, cancellation and atomic output
cleanup. All seven explicit production-graph Vulkan gates passed on 2026-11-04,
alongside 57 host tests and ARM type checking. The source-admission flag is now
enabled for the existing experimental route; the complete per-device runtime
census is still mandatory. Evidence is recorded in
`results/preview-stall-opt72/grain-phased-integrated/integrated-gates-summary.json`
in the development workspace. The 65×65 sequential output maximum absolute
error was 8.620e-6 and the 129×129 coordinate output maximum was 9.470e-6 versus
the original CPU result, because the retained spatial stages use f32. Sampler
and RNG checks are exact. These results do not establish physical Adreno speed,
new persistent-context behavior, or the full Android preview/export chain.

Persistent GPU sessions are a separate change. Reused buffers require fresh
initialization and exclusive occupied slots; transient slots are returned only
after queue completion. A stage seal proves packet access ended, not process
death. Session memory reservations remain held until helper termination.
