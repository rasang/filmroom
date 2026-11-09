package dev.filmroom.diagnostics

import dev.filmroom.io.formats.PhotoFormat
import org.junit.Assert.*
import org.junit.Test

class EffectCorrelationDiagnosticTest {
    @Test fun correlatedSnapshotsRoundTripThroughSafeInfo() {
        val effect=EffectExportDiagnosticInfo(EffectExportScope.INPUT_DECODE,EffectExportEvent.INPUT_FORMAT,
            correlationId=42,parentCorrelationId=7,inputFormat=PhotoFormat.HEIF)
        val input=MotionInputDiagnosticInfo(MotionInputStatus.SUCCESS,3,correlationId=7)
        val preview=MotionPreviewDiagnosticInfo(MotionPreviewPath.STATIC,correlationId=7)
        val safe=SafeInfo(effectExport=effect,motionInput=input,motionPreview=preview)
        assertEquals(safe,SafeInfo.parse(safe.fields()))
    }
    @Test fun untrustedTokensAndFormatTextAreNeverRetained() {
        for(value in listOf("-1","0","9223372036854775808","content://private/photo.jpg","/private/secret","1e4")) {
            val raw=mapOf("effectScope" to "INPUT_DECODE","effectEvent" to "INPUT_FORMAT",
                "effectCorrelationId" to value,"effectParentCorrelationId" to value,"effectInputFormat" to value,
                "motionInputStatus" to "SUCCESS","motionInputCorrelationId" to value,
                "motionPreviewPath" to "STATIC","motionPreviewCorrelationId" to value)
            val clean=SafeInfo.parse(raw).fields()
            assertFalse(clean.keys.any{it.endsWith("CorrelationId")})
            assertEquals("UNKNOWN",clean["effectInputFormat"])
            assertFalse(clean.values.any{it.contains("private")||it.contains("secret")})
        }
    }
    @Test fun invalidDirectNumbersAreAlsoSuppressed() {
        val fields=SafeInfo(effectExport=EffectExportDiagnosticInfo(EffectExportScope.VIDEO,EffectExportEvent.START,correlationId=-1,parentCorrelationId=0),
            motionInput=MotionInputDiagnosticInfo(MotionInputStatus.CANCELLED,correlationId=-1),
            motionPreview=MotionPreviewDiagnosticInfo(MotionPreviewPath.STATIC,correlationId=0)).fields()
        assertFalse(fields.keys.any{it.endsWith("CorrelationId")})
    }
}
