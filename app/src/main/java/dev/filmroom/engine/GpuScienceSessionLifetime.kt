package dev.filmroom.engine

/** An accepted stage binds both native session identity and its immutable packet. */
internal data class GpuScienceStageTicket(
    val epoch: Long, val frameGeneration: Long, val sequence: Long,
    val token: Long, val manifestSha256: String,
) {
    init {
        require(epoch > 0 && frameGeneration > 0 && sequence > 0 && token > 0)
        require(Regex("[0-9a-f]{64}").matches(manifestSha256))
    }
}

/**
 * Persistent helper ownership. A stage/frame seal ends packet access only.
 * Neither seal releases the process reservation or resets sequence/epoch state.
 * All methods are synchronized because death, pressure and JNI may race.
 */
internal class GpuScienceSessionLifetime(val epoch: Long) {
    init { require(epoch > 0) }
    private var helperRequested = false
    private var startupAcknowledged = false
    private var openAttempted = false
    private var sessionOpened = false
    private var retiring = false
    private var dead = false
    private var finished = false
    private var lastGeneration = 0L
    private var frameGeneration: Long? = null
    private var frameBeginPending = false
    private var frameFinishPending = false
    private var lastSequence = 0L
    private var stage: GpuScienceStageTicket? = null

    @Synchronized fun requestHelper(): Boolean {
        if (helperRequested || retiring || finished) return false
        helperRequested = true
        return true
    }
    @Synchronized fun acknowledgeStartup(callbackEpoch: Long): Boolean {
        if (callbackEpoch != epoch || !helperRequested || dead || finished) return false
        startupAcknowledged = true
        return true
    }
    /** Must precede an OPEN send, including a send with an uncertain outcome. */
    @Synchronized fun attemptOpen(callbackEpoch: Long): Boolean {
        if (callbackEpoch != epoch || !startupAcknowledged || openAttempted || retiring || finished) return false
        openAttempted = true
        return true
    }
    @Synchronized fun opened(callbackEpoch: Long): Boolean {
        if (callbackEpoch != epoch || !openAttempted || sessionOpened || retiring || dead || finished) return false
        sessionOpened = true
        return true
    }
    @Synchronized fun attemptBeginFrame(callbackEpoch: Long, generation: Long): Boolean {
        if (callbackEpoch != epoch || !sessionOpened || retiring || dead || finished || frameGeneration != null || generation <= lastGeneration) return false
        lastGeneration = generation
        frameGeneration = generation
        frameBeginPending = true
        return true
    }
    @Synchronized fun frameOpened(callbackEpoch: Long, generation: Long): Boolean {
        if (callbackEpoch != epoch || frameGeneration != generation || !frameBeginPending || retiring || dead || finished) return false
        frameBeginPending = false
        return true
    }
    /** The sequence is consumed before RUN. A rejected/uncertain send is never replayed. */
    @Synchronized fun attemptStage(ticket: GpuScienceStageTicket): Boolean {
        if (ticket.epoch != epoch || frameGeneration != ticket.frameGeneration || frameBeginPending || frameFinishPending || stage != null || retiring || dead || finished || lastSequence == Long.MAX_VALUE || ticket.sequence != lastSequence + 1) return false
        lastSequence = ticket.sequence
        stage = ticket
        return true
    }
    /** Caller must authenticate Binder and validate the native queue/packet-access seal. */
    @Synchronized fun stageSealed(ticket: GpuScienceStageTicket): Boolean {
        if (stage != ticket || retiring || dead || finished) return false
        stage = null
        return true
    }
    @Synchronized fun attemptFinishFrame(callbackEpoch: Long, generation: Long): Boolean {
        if (callbackEpoch != epoch || frameGeneration != generation || frameBeginPending || frameFinishPending || stage != null || retiring || dead || finished) return false
        frameFinishPending = true
        return true
    }
    @Synchronized fun frameSealed(callbackEpoch: Long, generation: Long, sequence: Long): Boolean {
        if (callbackEpoch != epoch || frameGeneration != generation || !frameFinishPending || stage != null || sequence != lastSequence || retiring || dead || finished) return false
        frameGeneration = null
        frameFinishPending = false
        return true
    }
    /** Cancellation, trim, idle close and any protocol/driver uncertainty permanently retire this epoch. */
    @Synchronized fun requestStop(callbackEpoch: Long): Boolean {
        if (callbackEpoch != epoch || dead || finished) return false
        retiring = true
        return true
    }
    /** Only a definite startService rejection, not a bind failure or timeout, can prove no helper. */
    @Synchronized fun confirmStartRejected(callbackEpoch: Long): Boolean {
        if (callbackEpoch != epoch || !retiring || startupAcknowledged || openAttempted || dead || finished) return false
        helperRequested = false
        return true
    }
    /** Only the tracked remote Binder death after START_NOT_STICKY acknowledgment is sufficient. */
    @Synchronized fun confirmDead(callbackEpoch: Long): Boolean {
        if (callbackEpoch != epoch || !helperRequested || !startupAcknowledged || dead || finished) return false
        retiring = true
        dead = true
        return true
    }
    @Synchronized fun canReleaseSessionLease(): Boolean = retiring && (!helperRequested || dead)
    @Synchronized fun finish(): Boolean {
        if (finished || !canReleaseSessionLease()) return false
        finished = true
        return true
    }
    @Synchronized fun isRetiring(): Boolean = retiring
    @Synchronized fun isDead(): Boolean = dead
    @Synchronized fun isIdle(): Boolean = sessionOpened && frameGeneration == null && !retiring && !dead && !finished
    @Synchronized fun activeStage(): GpuScienceStageTicket? = stage
    @Synchronized fun nextSequence(): Long? = if (lastSequence == Long.MAX_VALUE || retiring || dead || finished) null else lastSequence + 1
}
