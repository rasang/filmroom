package dev.filmroom.livephoto

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/**
 * One execution, one durable publication result. Cancellation before commit still fails normally.
 * After commit, a dispatcher handoff may discard a return value but cannot undo the publication.
 * Only the publisher's successful return may call [committed]; a progress percentage is not proof.
 */
internal object CommittedResultHandoff {
    suspend fun <T:Any> run(
        dispatcher:CoroutineDispatcher=Dispatchers.IO,
        block:suspend CoroutineScope.(committed:(T)->Unit)->T
    ):T {
        val result=AtomicReference<T?>(null)
        return try {
            withContext(dispatcher) {
                block { value -> check(result.compareAndSet(null,value)) { "Publication already committed" } }
            }
        } catch(cancelled:CancellationException) {
            result.get() ?: throw cancelled
        }
    }
}
