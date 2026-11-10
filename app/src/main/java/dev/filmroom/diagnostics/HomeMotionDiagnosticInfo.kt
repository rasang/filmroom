package dev.filmroom.diagnostics

import dev.filmroom.effects.EffectKind

// Reconstructed from the authorized exp6 APK; not recovered original source.
enum class HomeMotionEvent { PREPARE, START, FINISH, STOP, REJECT, FAIL, HOLD_PENDING, HOLD_TRIGGER, COVER_READY }
enum class HomeMotionReason { NONE, NOT_READY, MOVED, MULTITOUCH, RELEASED, STATE_CHANGED, LIFECYCLE, MODE_CHANGED, GL_ERROR, MEMORY, CANCELLED, UNKNOWN }

data class HomeMotionDiagnosticInfo(
    val event: HomeMotionEvent,
    val kind: EffectKind? = null,
    val reason: HomeMotionReason = HomeMotionReason.NONE,
    val elapsedMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    internal fun fields(): Map<String, Any> = buildMap {
        put("homeMotionEvent", event.name)
        kind?.let { put("homeMotionKind", it.name) }
        put("homeMotionReason", reason.name)
        elapsedMs?.takeIf { it in 0..60_000 }?.let { put("homeMotionElapsedMs", it) }
        width?.takeIf { it in 1..30_000 }?.let { put("homeMotionWidth", it) }
        height?.takeIf { it in 1..30_000 }?.let { put("homeMotionHeight", it) }
    }

    companion object {
        internal fun parse(fields: Map<String, String>): HomeMotionDiagnosticInfo? {
            val event = HomeMotionEvent.entries.firstOrNull { it.name == fields["homeMotionEvent"] } ?: return null
            return HomeMotionDiagnosticInfo(
                event,
                EffectKind.entries.firstOrNull { it.name == fields["homeMotionKind"] },
                HomeMotionReason.entries.firstOrNull { it.name == fields["homeMotionReason"] } ?: HomeMotionReason.UNKNOWN,
                fields["homeMotionElapsedMs"]?.toLongOrNull()?.takeIf { it in 0..60_000 },
                fields["homeMotionWidth"]?.toIntOrNull()?.takeIf { it in 1..30_000 },
                fields["homeMotionHeight"]?.toIntOrNull()?.takeIf { it in 1..30_000 },
            )
        }
    }
}

object HomeMotionLog {
    fun record(
        event: HomeMotionEvent,
        kind: EffectKind? = null,
        reason: HomeMotionReason = HomeMotionReason.NONE,
        elapsedMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
    ) {
        QueuedDiagnosticTrace(
            Operation.HOME_MOTION,
            SafeInfo(homeMotion = HomeMotionDiagnosticInfo(event, kind, reason, elapsedMs, width, height)),
        ).end(if (event == HomeMotionEvent.FAIL) Outcome.FAILED else Outcome.SUCCESS, SafeInfo())
    }
}
