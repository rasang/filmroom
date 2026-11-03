package dev.filmroom.engine

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

internal object GpuFilmOpticsFixtures {
    fun plan(token: Long = 94): JSONObject {
        val pixels = 6000L * 4000; val device = 20 * pixels + 8 * GpuRenderProtocol.MIB + 65536
        return JSONObject().put("schemaVersion", 1).put("operation", "fullFilmOptics").put("token", token)
            .put("manifestSha256", "c".repeat(64)).put("width", 6000).put("height", 4000).put("passCount", 30)
            .put("deviceBytes", device).put("hostBytes", 16 * GpuRenderProtocol.MIB)
            .put("driverAllowanceBytes", 64 * GpuRenderProtocol.MIB).put("helperAllowanceBytes", 64 * GpuRenderProtocol.MIB)
            .put("combinedPeakBytes", device + 144 * GpuRenderProtocol.MIB).put("diskBytes", 48 * pixels + 65536)
    }
    fun envelope(plan: JSONObject = plan()) = JSONObject().put("ok", true).put("schemaVersion", 1)
        .put("operation", "planGpuFilmOpticsStage").put("plan", plan).toString()
    fun parsedPlan(token: Long = 94) = GpuFilmOpticsProtocol.parsePlan(envelope(plan(token)), token)
    fun request(context: Context, token: Long = 94): String {
        val p = parsedPlan(token); val dir = File(context.cacheDir, "gpu-render/gpu-stage-$token").apply { mkdirs() }
        return JSONObject().put("schemaVersion", 1).put("operation", p.operation.wire).put("token", token).put("parentHandle", 11L)
            .put("jobDir", dir.canonicalPath).put("manifestSha256", p.digest).put("budgetBytes", p.combinedPeakBytes)
            .put("width", p.width).put("height", p.height).put("totalPixels", p.width.toLong() * p.height * 3).toString()
    }
    fun result(): JSONObject {
        val p = parsedPlan()
        return JSONObject().put("ok", true).put("schemaVersion", 1).put("operation", p.operation.wire).put("token", p.token)
            .put("manifestSha256", p.digest).put("gpuUsed", true).put("fullImage", true).put("width", p.width).put("height", p.height)
            .put("channels", 3).put("passCount", p.passCount).put("elapsedMs", 1234.5).put("setupMs", 3.0).put("uploadMs", 2.0)
            .put("dispatchWaitMs", 4.0).put("readbackMs", 5.0).put("guardMs", 6.0)
            .put("backend", GpuFilmOpticsProtocol.NATIVE_BACKEND).put("adapter", "/private/photo SECRET_DRIVER_TEXT")
            .put("vendor", 20803).put("shaderF64Advertised", false).put("uploadBytes", 1234L).put("readbackBytes", 1234L)
            .put("dispatches", 150).put("deviceBytes", p.deviceBytes).put("sampleCount", p.passCount.toLong() * (p.width + p.height))
            .put("sampleMaxAbs", 1e-4).put("sampleRms", 1e-5).put("sampleToleranceRelative", 0.001).put("sampleToleranceAbsolute", 0.001)
            .put("sampleScope", GpuFilmOpticsProtocol.SAMPLE_SCOPE)
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class GpuFilmOpticsProtocolTest {
    @Test fun exactPlanIncludesFiveResidentPlanesTwoTransfersAndWholeProcessHeadroom() {
        val p = GpuFilmOpticsFixtures.parsedPlan()
        assertEquals(20L * p.width * p.height + 8 * GpuRenderProtocol.MIB + 65536, p.deviceBytes)
        assertEquals(p.deviceBytes + 144 * GpuRenderProtocol.MIB, p.combinedPeakBytes)
        assertEquals(48L * p.width * p.height + 65536, p.diskBytes)
        assertTrue(GpuRenderProtocol.parseStagePlan(GpuFilmOpticsFixtures.envelope(), 94, GpuRenderOperation.FILM_OPTICS) is GpuFilmOpticsPlan)
    }
    @Test fun dimensionsPlanesBudgetsPassLimitsAndUnknownFieldsFailClosed() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("width", 40001) }, { it.put("width", 10000).put("height", 10000) }, { it.put("passCount", 31) },
            { it.put("deviceBytes", 1) }, { it.put("hostBytes", 1) }, { it.put("combinedPeakBytes", Long.MAX_VALUE) },
            { it.put("diskBytes", 1) }, { it.put("driverAllowanceBytes", 1) }, { it.put("helperAllowanceBytes", 1) },
            { it.put("operation", "fullCameraDiffusion") }, { it.put("sourcePath", "/photo") })
        edits.forEach { edit -> val p = GpuFilmOpticsFixtures.plan(); edit(p); assertTrue(runCatching { GpuFilmOpticsProtocol.parsePlan(GpuFilmOpticsFixtures.envelope(p), 94) }.isFailure) }
    }
    @Test fun perAxisScopeAndExactGuardCoverageSurvivePrivacySafeRoundTrip() {
        val p = GpuFilmOpticsFixtures.parsedPlan()
        val result = GpuFilmOpticsProtocol.parseNativeResult(GpuFilmOpticsFixtures.result().toString(), p)
        val wire = GpuRenderProtocol.encodeResult(result.first, result.second)
        assertEquals(result, GpuRenderProtocol.parseStageResult(wire, p))
        assertTrue(wire.contains(GpuFilmOpticsProtocol.SAMPLE_SCOPE))
        listOf("adapter", "SECRET", "/private", "token", "manifestSha256", "c".repeat(64)).forEach { assertFalse(wire.contains(it)) }
        assertTrue(runCatching { GpuPrintScanProtocol.parseResult(wire, GpuPrintScanFixtures.parsedPlan()) }.isFailure)
    }
    @Test fun wrongScopeIncompleteCoverageAndWeakerToleranceNeverSucceed() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("sampleScope", "full_image_f64") }, { it.put("sampleCount", 1) }, { it.put("sampleToleranceAbsolute", 0.01) },
            { it.put("sampleToleranceRelative", 0.0) }, { it.put("sampleMaxAbs", "NaN") }, { it.put("guardMs", -1) },
            { it.put("passCount", 29) }, { it.put("dispatches", 166) }, { it.put("channels", 2) }, { it.put("fullImage", false) })
        edits.forEach { edit -> val r = GpuFilmOpticsFixtures.result(); edit(r); assertTrue(runCatching { GpuFilmOpticsProtocol.parseNativeResult(r.toString(), GpuFilmOpticsFixtures.parsedPlan()) }.isFailure) }
    }
    @Test fun pureRenormalizationMayHaveNoGaussianGuardButRetainsExactScope() {
        val p = GpuFilmOpticsProtocol.parsePlan(GpuFilmOpticsFixtures.envelope(GpuFilmOpticsFixtures.plan().put("passCount", 0)), 94)
        val r = GpuFilmOpticsFixtures.result().put("passCount", 0).put("sampleCount", 0).put("sampleMaxAbs", 0.0).put("sampleRms", 0.0).put("dispatches", 3)
        assertEquals(GpuRenderCode.OK, GpuFilmOpticsProtocol.parseNativeResult(r.toString(), p).first)
        assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuFilmOpticsProtocol.parseNativeResult("""{"ok":false,"code":"GPU_OPTICS_NUMERIC_CHECK"}""", p).first)
    }
}
