# beta27 Activity and motion source raw verification

Verified against JADX 1.5.3 fallback instruction output from the delivered beta27 APK, not only structured Java decompilation. Evidence retained in `reverse-support/preview-fallback.java` (onDrawFrame lines 1434–1694) and `reverse-support/source-fallback.java` (frameAt lines 270–294).

## Correct control flow

```kotlin
var elapsed = playback.elapsed(clock(), start, duration)
if (!playback.stopped && !motionSuspended) {
    ensureMotion()
    val ready = tickMotion(if (motionPrimed) elapsed.coerceIn(0, duration) else 0)
    motionFramePrepared = motionFramePrepared || ready
    val audioPrepared = previewAudio.isPrepared(kind, playingSlow, motionFile)
    var canStart = ready && audioPrepared
    if (!motionPrimed && !canStart) {
        val now = clock()
        if (motionWaitBegan == 0L) motionWaitBegan = now
        start = now
        if (now - motionWaitBegan < 1500L) {
            renderer.draw(width, height, duration, playingSlow, filmSlide, filmDirection)
            return // no frame-completion, audio request, or playback advance yet
        }
        if (!motionFramePrepared) {
            surfaceMotion?.let { motionLog?.decoder(it.snapshot()); it.close() }
            surfaceMotion = null
            motion?.close()
            motion = null
            motionLog?.path(STATIC, TIMEOUT)
        }
        canStart = true
    }
    if (!motionPrimed && canStart) {
        motionPrimed = true
        preparedSourceAudio = audioPrepared
        motionWaitBegan = 0
        start = clock()
        motionLog?.started()
        if (pendingFirstFrame) {
            motionLog?.frame(pendingFirstPts, 0, pendingFirstSubmitNs)
            pendingFirstFrame = false
        }
        elapsed = 0 // only the transition to primed resets elapsed
    }
}
renderer.draw(width, height, elapsed.coerceIn(0, duration), playingSlow, filmSlide, filmDirection)
frameDrawn = true
if (!reportedFrame) { reportedFrame = true; postOnAnimation { firstFrame?.invoke(this) } }
if (!playback.stopped && !audioRequested) {
    audioRequested = true
    // Capture current epoch, start, slow and preparedSourceAudio before posting.
    runOnUiThread {
        if (audioEpoch == capturedEpoch && preview === thisPreview && !working && lifecycle >= RESUMED) {
            previewAudio.play(kind, capturedSlow, capturedStart, motionFile, capturedPreparedSourceAudio)
        }
    }
}
playback.rendered(elapsed, duration)
if (playback.stopped) { renderMode = WHEN_DIRTY; disposeMotion(true, SUCCESS) }
// Any Throwable in the runCatching block invokes renderError().
```

`EffectMotionSource.frameAt`: after composition (or fallback to decoded bitmap), recycle decoded bitmap only if composition returns a different bitmap. Then:

```kotlin
val blurred = if (preview) null else EffectTextures.defocused(composed)
lastUs = sampleUs
return EffectMotionFrame(composed, blurred)
```

The structured Java output incorrectly inverted this preview branch, the audio-request branch, and the final error branch. It also conflated the elapsed variable with the constant zero. The fallback instructions resolve these ambiguities.

This is static control-flow verification, not Android device playback, timing, or audio validation.
