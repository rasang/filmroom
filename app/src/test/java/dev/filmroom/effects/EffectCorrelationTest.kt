package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class EffectCorrelationTest {
    @Test fun independentPagesAndRepeatedAttemptsNeverReuseTheirFlow() {
        val ids=(1..1000).map{EffectCorrelation.next()}
        assertEquals(ids.size,ids.toSet().size)
        assertTrue(ids.all{it>0})
        val page=ids[0]
        val attempts=ids.drop(1).map{EffectExportDiagnosticInfo(EffectExportScope.PREFLIGHT,EffectExportEvent.START,
            correlationId=it,parentCorrelationId=page)}
        assertTrue(attempts.all{it.parentCorrelationId==page && it.correlationId!=page})
    }
    @Test fun cancellationAndSubsequentPreviewCannotChangeEarlierCallbackIdentity() {
        val old=EffectCorrelation.next();val next=EffectCorrelation.next()
        val reports=mutableListOf<MotionPreviewDiagnosticInfo>()
        val previous=EffectMotionPreviewLog(clockMs={1},correlationId=old,sink={info,_->reports.add(info)})
        val current=EffectMotionPreviewLog(clockMs={2},correlationId=next,sink={info,_->reports.add(info)})
        previous.end(Outcome.CANCELLED);current.started();current.end();previous.end()
        assertEquals(listOf(old,next),reports.map{it.correlationId})
    }
    @Test fun everyExportPhaseKeepsBothAttemptAndParentIncludingCancellationTerminal() {
        for(event in EffectExportEvent.entries) {
            val info=EffectExportLog.correlated(EffectExportDiagnosticInfo(EffectExportScope.VIDEO,event,
                correlationId=999,parentCorrelationId=998),42,7)
            assertEquals(42L,info.correlationId)
            assertEquals(7L,info.parentCorrelationId)
            assertEquals(info,SafeInfo.parse(SafeInfo(effectExport=info).fields()).effectExport)
        }
    }
    @Test fun sourceFormatComesFromHeaderNotFilename() {
        val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte())
        assertEquals(dev.filmroom.io.formats.PhotoFormat.JPEG,dev.filmroom.io.formats.PhotoHeaders.sniff(jpeg).format)
        assertEquals(dev.filmroom.io.formats.PhotoFormat.UNKNOWN,dev.filmroom.io.formats.PhotoHeaders.sniff("fake.jpg".toByteArray()).format)
    }
}
