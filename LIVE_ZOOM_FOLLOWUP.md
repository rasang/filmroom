# Live zoom follow-up (beta9 base)

Base: `723482e3` (beta9). This change does not bump the version or publish a package.

## Evidence

The supplied beta9 diagnostic log (OPPO PKJ110 / Android 17) contains four successful
2048-tier requests (actual full frame 1885×2046), lasting 3.576, 4.708, 4.593 and
4.171 seconds. The subsequent higher-tier requests (actual 2194×2382) entered JNI
and were cancelled after 9.333–16.676 seconds without a recorded NATIVE_EXIT.
Cancellation is checked before that exit log, so absence of the log does not prove
a permanent GPU hang. Native admission had already completed at NATIVE_ENTER.

The refined renderer reserves 76 bytes/pixel for native full-frame/external buffers
plus 96 MiB fixed overhead. At the recorded sizes this leaves roughly 136 MiB vs
37 MiB of the unchanged 512 MiB budget for tiles, before global diffusion fields.
This explains why higher resolution can produce much smaller tiles; it is code
analysis, not a measured device profiler attribution.

## Changes

* Optional whole-frame zoom refinement has an explicit eight-second per-attempt
  time budget (a UI latency policy, not a measured hardware limit). On expiry it
  sends native cancellation, stops the indicator, retains any last valid frame and
  marks that tier failed, rather than repeatedly scheduling it. Underlying native
  ownership remains held until the provider actually drains. This cannot force a
  hung device driver to terminate safely.
* Cancellation chains are established immediately and wait non-cancellably for
  predecessors. A third request cannot skip a first request still draining merely
  because an intermediate queued request was cancelled.
* Live UI ignores hidden ordinary-photo local-detail loading/failure status.
  Visible whole-frame refinement remains indicated normally.
* Pause saves Live scale and normalized horizontal/vertical offset. A new player
  restores them only for the same source and effect, after real surface geometry
  and GPU limits are known. State is also serialized through Activity saved state.
  Changing the image/effect does not inherit an unrelated saved viewport.

## Verification scope

Host tests exercise lifecycle state, viewport geometry, timeout/cancellation and
owned-bitmap recycling. They are not Android-device GLES/native-driver tests.
No export, GPU budget, colour science, native library or beta9 icon changes.
