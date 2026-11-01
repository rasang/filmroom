package dev.filmroom

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Deterministic test-observer regression. Does not substitute for scheduler/cache tests. */
class DetailCallObservationTest {
    @Test fun concurrentUniqueAppendCanBreakTwoReadAssertionButSnapshotStillRejectsRealDuplicates() {
        val oldIterationEnded=CountDownLatch(1)
        val appendFinished=CountDownLatch(1)
        val calls=object:CopyOnWriteArrayList<Pair<Int,Int>>() {
            override fun iterator():MutableIterator<Pair<Int,Int>> {
                val snapshot=super.iterator()
                return object:MutableIterator<Pair<Int,Int>> {
                    override fun hasNext()=snapshot.hasNext()
                    override fun remove()=throw UnsupportedOperationException()
                    override fun next():Pair<Int,Int> {
                        val cell=snapshot.next()
                        if(cell==(3 to 0) && !snapshot.hasNext()) {
                            oldIterationEnded.countDown()
                            check(appendFinished.await(5,TimeUnit.SECONDS)){"Worker append did not finish"}
                        }
                        return cell
                    }
                }
            }
        }
        calls.addAll((0..3).map{it to 0})
        val workerFailure=AtomicReference<Throwable?>()
        val worker=Thread {
            try {
                check(oldIterationEnded.await(5,TimeUnit.SECONDS)){"Old snapshot was not read"}
                calls.add(4 to 0) // A new unique cell, not a rerender.
            }catch(t:Throwable){workerFailure.set(t)}finally{appendFinished.countDown()}
        }.apply{isDaemon=true;start()}
        try {
            // Exactly the old Activity assertion's two arguments, evaluated left-to-right.
            val oldDistinctCount=calls.distinct().size
            val liveCount=calls.size
            worker.join(5000);assertFalse(worker.isAlive)
            workerFailure.get()?.let{throw AssertionError("Worker failed",it)}
            assertEquals(4,oldDistinctCount);assertEquals(5,liveCount)
            val snapshot=calls.toList()
            assertEquals(listOf(0 to 0,1 to 0,2 to 0,3 to 0,4 to 0),snapshot)
            assertEquals(snapshot.distinct().size,snapshot.size)
            calls.add(3 to 0) // Same-snapshot assertion must still detect a true duplicate.
            val duplicated=calls.toList()
            assertNotEquals(duplicated.distinct().size,duplicated.size)
        }finally{oldIterationEnded.countDown();appendFinished.countDown();worker.join(5000)}
    }
}
