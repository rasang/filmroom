package dev.filmroom.imprint

/** Dialog dismissal is queued by Android; a previous editor must not clear its successor. */
internal class ImprintPreviewSession {
    private var generation = 0L
    fun begin(publish: (ImprintSettings?) -> Unit): (ImprintSettings?) -> Unit {
        val token = ++generation
        return { settings -> if (token == generation) publish(settings) }
    }
}
