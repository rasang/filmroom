package dev.filmroom.diagnostics

import dev.filmroom.engine.GpuProbeStatus
import dev.filmroom.engine.GpuProbeSummary
import org.junit.Assert.*
import org.junit.Test

class GpuProbeTelemetryTest {
    @Test fun onlyClosedStatusNumbersAndFlagsSurviveDiskFields(){
        val secret="content://private/image.jpg GPS=secret"
        val fields=mapOf("gpuProbeStatus" to "COMPLETE","gpuProbeTotalMs" to "12.5","gpuProbeMaxAbs" to "NaN","gpuProbeRoiWidth" to "9999","token" to "123456","path" to secret,"driver" to secret,"gpuProbeOutputReplaced" to "false")
        val safe=GpuProbeTelemetry.fromFields(fields)!!.typedFields()
        assertEquals(12.5,safe["gpuProbeTotalMs"]);assertFalse(safe.containsKey("gpuProbeMaxAbs"));assertFalse(safe.containsKey("gpuProbeRoiWidth"))
        assertFalse(safe.toString().contains(secret));assertFalse(safe.containsKey("token"));assertNull(GpuProbeTelemetry.fromFields(mapOf("gpuProbeStatus" to secret)))
    }
    @Test fun summaryIsDiagnosticOnlyAndRoundTripsWithoutIdentity(){
        val info=SafeInfo(gpuProbe=GpuProbeTelemetry.fromSummary(GpuProbeSummary(GpuProbeStatus.CANCELLED)))
        val fields=info.fields();val restored=SafeInfo.parse(fields).fields()
        assertEquals(fields,restored);assertEquals("false",fields["gpuProbeOutputReplaced"]);assertEquals("false",fields["gpuProbeFullRenderMeasured"])
        assertTrue(fields.keys.none{it.contains("token",true)||it.contains("path",true)||it.contains("hash",true)})
    }
}
