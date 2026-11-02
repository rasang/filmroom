package dev.filmroom.codec

import dev.filmroom.model.RawBatchMode
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable import selection. Survives dispatcher/queue waits; never stored in photo parameters. */
class RawBatchSnapshot(val mode:RawBatchMode):AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<RawBatchSnapshot>
}
