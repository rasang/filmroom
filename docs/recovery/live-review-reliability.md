# Live review reliability follow-up

Based on beta29. Scope is limited to already-identified single-file Live inputs;
this does not add HEIC, companion-file selection, or multi-video-track support.

## Export

- Failure to open a known source video is no longer converted to a missing optional
  source. The UI reports decoding failure and does not claim a successful save.
- A missing initial decoded frame or unavailable decoder aborts encoding before
  publication. Attempt cleanup removes its pending output.
- Repeated timestamps may legitimately reuse a previously supplied frame; this
  is not treated as failure. The check does not require pixel changes (a real
  video can show a motionless scene).
- The existing retriever export decoder remains in use. This change prevents
  false success; it does not promise support for every phone codec.

## Preview audio

The visual warmup remains bounded. If the original audio mix finishes after the
warmup, a main-thread, playback-identity-checked callback replaces the base track
with the mix, seeking to the current animation time. Stop, replay, page change,
and audio-focus loss cancel this callback. An expired 3-second animation does
not restart audio. A source without a usable audio track ends polling normally.
A player replacement may have a brief device-dependent transition; phone audio
latency remains unverified.
