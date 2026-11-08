package dev.filmroom.diagnostics

import org.junit.Assert.*
import org.junit.Test

class EffectExportDiagnosticInfoTest {
    @Test fun allReasonsAndStagesRoundTripThroughSafeInfo() {
        for(reason in EffectExportReason.entries) for(step in EffectExportStep.entries) {
            val info=EffectExportDiagnosticInfo(EffectExportScope.VIDEO,EffectExportEvent.ATTEMPT_FAILED,reason,step,
                requestedWidth=8192,requestedHeight=6144,width=2622,height=2356,availableWidth=3000,availableHeight=2700,
                sourceWidth=8192,sourceHeight=6144,resultWidth=8192,resultHeight=6144,maxHeapBytes=536870912,remainingBytes=268435456,
                budgetEdge=2364,codecIndex=2,attempt=3,textureLimit=16384,viewportWidth=16384,viewportHeight=16384,codecErrorCode=-1010)
            val safe=SafeInfo(effectExport=info)
            assertEquals(safe.fields(),SafeInfo.parse(safe.fields()).fields())
            assertEquals(info,SafeInfo.parse(safe.fields()).effectExport)
        }
    }
    @Test fun invalidNumbersAndUntrustedStringsCannotLeak() {
        val injected=mapOf("effectScope" to "VIDEO","effectEvent" to "ATTEMPT_FAILED",
            "effectReason" to "content://private/photo.jpg", "effectStep" to "/private/file",
            "effectWidth" to "-2","effectHeight" to "2000000","effectCodecIndex" to "999999",
            "effectRemainingBytes" to "-1", "effectCodecErrorCode" to "2147483648",
            "effectUri" to "content://private/photo.jpg","effectException" to "secret")
        val clean=SafeInfo.parse(injected).fields()
        assertEquals("UNKNOWN",clean["effectReason"])
        assertEquals("NONE",clean["effectStep"])
        assertFalse(clean.keys.any{it in listOf("effectWidth","effectHeight","effectCodecIndex","effectRemainingBytes","effectCodecErrorCode","effectUri","effectException")})
        assertFalse(clean.values.any{it.contains("private")||it.contains("secret")})
    }
    @Test fun invalidScopeOrEventDropsEntireSnapshot() {
        assertNull(EffectExportDiagnosticInfo.parse(mapOf("effectScope" to "OTHER","effectEvent" to "START")))
        assertNull(EffectExportDiagnosticInfo.parse(mapOf("effectScope" to "VIDEO","effectEvent" to "user path")))
    }
    @Test fun everyScopeAndEventSurvives() {
        for(scope in EffectExportScope.entries)for(event in EffectExportEvent.entries){
            val info=EffectExportDiagnosticInfo(scope,event)
            assertEquals(info,EffectExportDiagnosticInfo.parse(info.fields().mapValues{it.value.toString()}))
        }
    }
    @Test fun metadataDoesNotChangeExistingFields() {
        val old=SafeInfo(width=42,height=24).fields()
        val added=SafeInfo(width=42,height=24,effectExport=EffectExportDiagnosticInfo(EffectExportScope.INPUT_DECODE,EffectExportEvent.DECODE_BUDGET)).fields()
        assertEquals(old,added.filterKeys{!it.startsWith("effect")})
    }
}
