# Live/effect diagnostics coverage (beta33-based)

This is local diagnostics, not remote analytics. Payloads use a fixed vocabulary and bounded numeric values; no URI, filename, camera/gallery package name, photo content, metadata timestamp, exception text or source-audio content is recorded.

## High-risk paths and added evidence

- Input decode: `IMAGE_BOUNDS`, `DECODE_RESULT`, `DECODE_SOURCE`, `COMPOSE_SOURCE`, plus existing source/result/requested dimensions and heap budget. Distinguishes the previous high-resolution composition failure from codec failure.
- Picker preload: separate `PRELOAD` operation, preparation stages, duration, failure category, and cancellation outcome. Preload errors still fall back to destination loading; instrumentation does not change that behavior.
- Original sound: lock wait, decode, mixing and encoding stages; no-audio result, unavailable base track, cache hit, mixed output bytes, and explicit fallback/error categories. A successful operation may contain `AUDIO_FALLBACK` because fallback to the existing effect sound is intentional.
- Audio playback: focus denial, MediaPlayer numeric error/extra, prepare/start exception category, and late-audio upgrade. No recurring poll or per-frame events.
- Video: codec/EGL/texture setup and finalization/publish boundaries, overall elapsed milliseconds, and completed video sample count. Draw/present/drain loops do not emit per-frame events; they still update the last failure stage.
- Live: selected writer profile and support-state indices, recovery failure count, actual-size resolution, probe/cover/package/verify/publish stages, cover retry, and cleanup failure. SDK validation/resource/verification/publication codes remain distinguishable without preserving exception messages.

`profileIndex` follows `LivePhotoProfile` at this version: 0 Google, 1 Xiaomi-compatible, 2 Oplus, 3 Vivo single, 4 Vivo pair 2104, 5 Vivo pair legacy, 6 Honor legacy, 7 Samsung SEF107, 8 Samsung SEF106. `supportIndex`: 0 supported, 1 unsupported, 2 unknown. These represent selected candidates/recorded evidence, NOT proof a user's gallery will play the saved asset.

## Still requires device validation

1. Original-motion media with/without audio, unsupported codecs and HDR sources; verify fallback is not silently mistaken for original playback.
2. Rapid next/previous, Back during preload, background/foreground, and late audio readiness; ensure stale callbacks never restart a previous photo.
3. Maximum export dimensions/low-memory pressure, codec fallback and cancellation at each stage.
4. MediaStore denial/failure and interrupted paired publication/recovery on each vendor gallery.
5. Successful format verification does not establish gallery playback compatibility.

No import/decoding compatibility policy was broadened by these changes.

## Publication result handoff risk

`NormalizedLivePhotoSdk.convert` publishes inside `withContext(IO)`. Coroutine prompt cancellation can discard the returned `ConversionResult` at the dispatcher handoff after publication is already committed. `LIVE_COMMITTED` is therefore emitted in the SDK's COMPLETE callback, before that return, separately from `LIVE_SAVED` at the caller. A cancelled operation containing LIVE_COMMITTED must not be interpreted as proof that nothing was saved. The controlled coroutine test demonstrates this handoff mechanism only; it does not simulate Android MediaStore. The production `CommittedResultHandoff` now retains a result only after successful publication; cancellation at return reuses that result without re-running publication. Cancellation before commit remains cancellation. Non-cancellable cleanup retains the caller dispatcher at its outer boundary, and a destroyed/leaving Activity does not show a success dialog. Production-helper race tests cover this handoff; Android MediaStore still requires device validation.

## Diagnostic delivery

PRELOAD、LIVE_PHOTO 和预览音频回调通过有界 IO 队列写入，避免主线程同步 fsync。分享诊断包前等待队列 barrier；队列过载可丢弃诊断，并记录拒绝入队次数。编码/解码 worker 的原有日志保持直接写入，不新增逐帧事件。
