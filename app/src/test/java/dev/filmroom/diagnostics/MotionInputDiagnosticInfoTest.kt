package dev.filmroom.diagnostics

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MotionInputDiagnosticInfoTest {
    @Test fun safeInfoRoundTripsEveryStatusAndRejectsUntrustedFields() {
        for(status in MotionInputStatus.entries) {
            val info=MotionInputDiagnosticInfo(status,1024,512)
            assertEquals(info,SafeInfo.parse(SafeInfo(motionInput=info).fields()).motionInput)
        }
        assertNull(MotionInputDiagnosticInfo.parse(mapOf("motionInputStatus" to "content://private/secret")))
        val parsed=MotionInputDiagnosticInfo.parse(mapOf("motionInputStatus" to "SUCCESS","motionInputCopiedBytes" to "-1","motionInputVideoBytes" to "999999999999"))!!
        assertEquals(0L,parsed.copiedBytes);assertNull(parsed.videoBytes)
    }
    @Test fun pairDiagnosticsRejectUntrustedStringsAndBoundCounts() {
        val parsed=MotionInputDiagnosticInfo.parse(mapOf(
            "motionInputStatus" to "PAIR_REQUIRED", "pairLookup" to "content://private/image",
            "pairMatch" to "/private/video.mp4", "pairPermission" to "PRIVATE",
            "pairCandidates" to "999", "pairReadFailures" to "-1", "pairRejected" to "999"))!!
        assertNull(parsed.pairLookup); assertNull(parsed.pairMatch); assertNull(parsed.pairPermission)
        assertEquals(9,parsed.pairCandidates); assertEquals(0,parsed.pairReadFailures); assertEquals(9,parsed.pairRejected)
        assertFalse(parsed.fields().toString().contains("private"))
        val info=MotionInputDiagnosticInfo(MotionInputStatus.PAIR_REQUIRED, pairLookup=PairLookupStatus.NO_VISIBLE_SAME_NAME,
            pairMatch=PairMatchStatus.INVALID_PAIR, pairPermission=PairPermission.PARTIAL, pairRejected=1, pairSavedFallback=true)
        assertEquals(info,SafeInfo.parse(SafeInfo(motionInput=info).fields()).motionInput)
    }
    @Test fun typedInputReportPersistsAcrossStoreReopen() {
        val dir=Files.createTempDirectory("motion-input-diagnostics-").toFile()
        val clock=object:DiagnosticClock { override fun wallMs()=1800000000000L;override fun elapsedMs()=100L }
        try {
            val info=SafeInfo(motionInput=MotionInputDiagnosticInfo(MotionInputStatus.EXTRACTION_FAILED,2000))
            val first=DiagnosticStore(dir,clock,"a".repeat(32));first.end(first.begin(Operation.IMPORT,info),Outcome.FAILED,info);first.flush()
            val second=DiagnosticStore(dir,clock,"b".repeat(32))
            assertTrue(second.snapshot(5).events.any{it.stage==Stage.END && it.info.motionInput==info.motionInput})
        } finally {dir.deleteRecursively()}
    }
}
