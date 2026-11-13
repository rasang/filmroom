package dev.filmroom.export

import dev.filmroom.engine.ExportDisplayProgress
import org.junit.Assert.*
import org.junit.Test

class MeasuredStageWorkTest {
    private fun clock():()->Long {var now=0L;return {now+=200_000_000L;now}}
    @Test fun measuredPassesAdvanceOnlyOnRealWorkAndReserveCompletion() {
        val values=mutableListOf<ExportDisplayProgress>()
        val work=MeasuredStageWork(7,listOf("compose","encode","publish","finalize"),clock(),values::add)
        work.start();work.report("compose",50,100)
        assertEquals(12,values.last().displayPercent)
        work.report("compose",100,100)
        assertEquals(24,values.last().displayPercent)
        work.finish("compose");assertEquals(25,values.last().displayPercent)
        work.report("compose",10,100) // stale callback cannot regress
        work.report("encode",100,100);assertEquals(49,values.last().displayPercent)
        work.finish("encode");work.finish("encode") // native + repository completion duplicate
        work.report("publish",100,100);assertEquals(74,values.last().displayPercent)
        work.finish("publish");work.finish("finalize")
        assertEquals(99,values.last().displayPercent)
        assertTrue(values.zipWithNext().all{(a,b)->a.done<=b.done})
        assertEquals(100,ExportDisplayProgress(8,1,1).displayPercent)
    }
    @Test fun callbacksAreCoalescedToVisiblePercentNotEveryRow() {
        val values=mutableListOf<ExportDisplayProgress>()
        val work=MeasuredStageWork(1,listOf("input"),clock(),values::add)
        work.start();for(row in 1..10_000)work.report("input",row.toLong(),10_000)
        work.finish("input")
        assertEquals(100,values.size);assertEquals(99,values.last().displayPercent)
    }
    @Test fun cannotCertifySkippedPass() {
        val work=MeasuredStageWork(7,listOf("encode","publish")){}
        assertThrows(IllegalStateException::class.java){work.report("publish",1,2)}
    }
}
