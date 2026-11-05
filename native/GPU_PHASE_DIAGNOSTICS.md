# Native GPU phase diagnostics, schema 1

This is diagnostic instrumentation only. It does not change shaders, image arithmetic,
GPU admission, cancellation, publication, fallback, or the GPU batching contract.
Native callback failure is advisory and must not fail an otherwise valid render.

## Android ABI

- `NativeGpuProbeBridge.nativeGpuPhaseSnapshot(token: Long): String` exports
  `Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeGpuPhaseSnapshot`.
- On the rendering worker, native calls the kept static method
  `dev.filmroom.engine.NativeGpuTelemetryBridge.onNativePhase(String): Boolean`
  using JNI signature `(Ljava/lang/String;)Z`.
- `true` acknowledges Android's recorder policy. `false`, a missing method/class,
  or a Java exception sets sticky diagnosticCode 1. Exceptions are cleared and
  original render execution continues. No Activity or main-thread object is used.
- The hard-kill watchdog must not call the recorder or wait for JNI telemetry.
  Independent telemetry polling may read the in-memory snapshot while a driver
  call is blocked. Native snapshots use `try_lock`, never a driver/Java/IO lock.

The payload has only closed integer codes, durations, counters, and a random
request token. It contains no image/photo data, filenames, paths, adapter names,
parameters, manifest hashes, exception text, or arbitrary driver messages.

## Numeric wire schema

Root fields (all required and nonnegative integers, except events array):

- schemaVersion: 1.
- token: request token. Invalid nonpositive JNI tokens return token 0 and unknown
  state. Tokens are process-local and are not names or content hashes.
- stageCode: 0 unknown; 1 camera diffusion; 2 automatic exposure; 3 highlight
  boost; 4 film exposure; 5 film optics; 6 film DIR; 7 exposure log; 8 film grain;
  9 print exposure; 10 print diffusion; 11 print scan; 12 scan tail; 13 finish tail.
- phaseCode: 0 unknown; 1 prepare; 2 device; 3 pipeline; 4 upload; 5 compute;
  6 readback; 7 numerical guard; 8 candidate commit; 9 cleanup.
- stateCode: 0 unknown; 1 started/running; 2 ended; 3 aborted.
- activityCode: 0 ordinary stage/image work; 1 synthetic admission. Grain's
  original RNG, software-binary64 arithmetic, sampler, and prepass checks are
  admission activity, not image processing. Their nested compute/readback events
  preserve activity 1.
- stepCode: 0 ordinary operation; 1 instance/adapter discovery and request;
  2 device request; 3 shader-module creation; 4 compute-pipeline creation;
  5 driver error-scope validation. Setup substeps retain their containing phase.
- sequence: monotonic per-token event sequence, including terminal and first
  diagnostic-failure transitions. Missing sequence numbers are expected after
  batching, polling, callback throttling, and bounded ring eviction.
- elapsedMs: phase or substep elapsed monotonic milliseconds. It grows for a
  running snapshot; ended/aborted values are the measured duration.
- totalElapsedMs: elapsed native stage lifetime, frozen after terminal.
- terminalCode: 0 running; 1 success; 2 failure; 3 cancelled; 4 caught panic.
- diagnosticCode: 0 no observed callback failure; 1 callback unavailable/failed
  (sticky), unknown token, or snapshot registry momentarily unavailable.
- failureCode: 0 no native failure; 1 unclassified. Exact static codes 2..229 are
  defined by `GPU_PHASE_FAILURE_CODES.json`. The mapping is append-only for this
  schema; never re-sort existing IDs. Unrecognized/freeform messages map to 1.
- events: last at most 32 actual edges. Each has sequence, phaseCode, stateCode,
  elapsedMs, totalElapsedMs, activityCode and stepCode. This is bounded history,
  not a complete per-dispatch trace. No string values are allowed.

Native emission is tested against the same fixture consumed by Android tests:
`app/src/test/resources/gpu-native-phase-v1.json`.

## Timing and evidence semantics

The registry stores at most 16 tokens and 16 nested live scopes per token.
Completed tokens can be evicted; live tokens are never evicted to admit another.
The event ring is updated at actual function/call boundaries. It is copied and
serialized only when a callback is due or an independent snapshot is requested.
A callback is due at the first start/end/abort for each (activity, step, phase),
then at most once a second while transitions occur, and always at terminal.
Android's recorder separately decides which events need a durable flush.

Start callbacks occur before the corresponding driver call. A blocked call
therefore leaves a running phase/substep visible to independent polling.
Nested phases overlap: do not add their elapsed times. In combined exposure and
print-scan dispatches, readback is nested within compute until the mapped result
confirms submitted work completed. Queue encoding/submission and batched waits
are separate compute scopes in other runners. An ended setup/encoding scope is
not a claim that a later GPU submission or numerical guard succeeded.

Cleanup sentinels bracket actual driver-owned Runner fields in Rust declaration
order, including on early-return paths after construction. They do not merely
announce cleanup before calling a function that still owns those resources.
Temporary FFT buffer ownership is similarly bracketed. Setup failures before a
Runner has been fully constructed can have cleanup inside their setup substep;
they must not be interpreted as a successful stage or successful device use.

First major phase entries and bounded periodic snapshots make a hang visible
without fsync per grain chunk. Sudden process death between periodic snapshots
can lose the current chunk/subphase; the major-stage start and last acknowledged
snapshot remain the evidence. Recorder failure is exposed, never converted into
an invented successful flush. There is no per-instruction or power-loss guarantee.

## Validation

Native unit tests cover bounded retention, independent-thread live snapshots,
nested scopes, safe numeric-only serialization, advisory callback failure,
sequence changes, admission/substep identity, real destructor coverage, fixture
parity, exact failure-code table parity, JNI callback Keep/signature, and a
100,000-scope overhead microbenchmark. The microbenchmark reports monotonic times
without a flaky speed threshold and asserts callback counts are bounded.
Android tests must parse the shared fixture and retain activity/step/failure codes.
The final native ABI verification must include the additional exported snapshot
method (66 declared JNI methods rather than the previous 65).
