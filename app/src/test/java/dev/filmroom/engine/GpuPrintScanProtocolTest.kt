package dev.filmroom.engine

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

internal object GpuPrintScanFixtures {
    fun plan(token: Long = 92, width: Int = 6000, height: Int = 4000): JSONObject {
        val pixels = width.toLong() * height; val c = minOf(65536L, pixels); val t = 100_000L
        val device = 4 * t + 36 * c + 4096; val host = 16 * t + 128 * c + 2 * GpuRenderProtocol.MIB
        return JSONObject().put("schemaVersion", 1).put("operation", "fullPrintScanLogXyz").put("token", token)
            .put("manifestSha256", "b".repeat(64)).put("width", width).put("height", height).put("mode", "printFromDensity")
            .put("chunkPixels", c).put("chunkCount", (pixels + c - 1) / c).put("tableScalars", t)
            .put("deviceBytes", device).put("hostBytes", host).put("driverAllowanceBytes", 64 * GpuRenderProtocol.MIB)
            .put("helperAllowanceBytes", 64 * GpuRenderProtocol.MIB).put("combinedPeakBytes", device + host + 128 * GpuRenderProtocol.MIB)
            .put("diskBytes", 48 * pixels + 8 * t + 65536)
    }
    fun envelope(plan: JSONObject = plan()) = JSONObject().put("ok", true).put("schemaVersion", 1)
        .put("operation", "planGpuPrintScan").put("plan", plan).toString()
    fun parsedPlan(token: Long = 92) = GpuPrintScanProtocol.parsePlan(envelope(plan(token)), token)
    fun request(context: Context, token: Long = 92): String {
        val p = parsedPlan(token)
        val dir = File(context.cacheDir, "gpu-render/gpu-stage-$token").apply { mkdirs() }
        return JSONObject().put("schemaVersion", 1).put("operation", p.operation.wire).put("token", token).put("parentHandle", 11L)
            .put("jobDir", dir.canonicalPath).put("manifestSha256", p.digest).put("budgetBytes", p.combinedPeakBytes)
            .put("width", p.width).put("height", p.height).put("totalPixels", p.width.toLong() * p.height * 3).toString()
    }
    fun result(): JSONObject {
        val p = parsedPlan()
        return JSONObject().put("ok", true).put("schemaVersion", 1).put("operation", p.operation.wire).put("token", p.token)
            .put("manifestSha256", p.digest).put("gpuUsed", true).put("fullImage", true).put("width", p.width).put("height", p.height)
            .put("channels", 3).put("mode", p.mode.wire).put("chunkCount", p.chunkCount).put("elapsedMs", 1234.5)
            .put("backend", GpuPrintScanProtocol.NATIVE_BACKEND).put("adapter", "/private/photo SECRET_DRIVER_TEXT")
            .put("vendor", 20803).put("shaderF64Advertised", false).put("uploadBytes", 1234L).put("readbackBytes", 1234L)
            .put("dispatches", p.chunkCount).put("deviceBytes", p.deviceBytes).put("sampleCount", GpuPrintScanProtocol.expectedSamples(p))
            .put("sampleMaxAbs", 1e-6).put("sampleRms", 1e-7).put("sampleToleranceRelative", 2e-5).put("sampleToleranceAbsolute", 2e-5)
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class GpuPrintScanProtocolTest {
    @Test fun exactResidentBudgetIncludesBothTableRepresentationsAndAdditionalProcessPeak() {
        val p = GpuPrintScanFixtures.parsedPlan()
        assertEquals(65536, p.chunkPixels); assertEquals(367L, p.chunkCount)
        assertEquals(p.deviceBytes + p.hostBytes + 128 * GpuRenderProtocol.MIB, p.combinedPeakBytes)
        assertTrue(p.combinedPeakBytes < GpuPrintScanProtocol.HARD_CAP)
        assertEquals(48L * 6000 * 4000 + 8 * p.tableScalars + 65536, p.diskBytes)
        assertTrue(GpuRenderProtocol.parseStagePlan(GpuPrintScanFixtures.envelope(), 92, GpuRenderOperation.PRINT_SCAN) is GpuPrintScanPlan)
        assertTrue(runCatching { GpuRenderProtocol.parsePlan(GpuPrintScanFixtures.envelope(), 92) }.isFailure)
    }
    @Test fun rejectsUnknownModeMisbudgetWrongChunkGeometryAndCrossStagePlan() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("mode", "arbitrary native method") }, { it.put("operation", "fullCameraDiffusion") },
            { it.put("chunkPixels", 32768) }, { it.put("chunkCount", 366) }, { it.put("tableScalars", 0) },
            { it.put("deviceBytes", 1) }, { it.put("hostBytes", 1) }, { it.put("driverAllowanceBytes", 1) },
            { it.put("helperAllowanceBytes", 1) }, { it.put("combinedPeakBytes", Long.MAX_VALUE) },
            { it.put("diskBytes", 1) }, { it.put("tableScalars", Long.MAX_VALUE) }, { it.put("sourcePath", "/photo") })
        edits.forEach { edit -> val p = GpuPrintScanFixtures.plan(); edit(p); assertTrue(runCatching { GpuPrintScanProtocol.parsePlan(GpuPrintScanFixtures.envelope(p), 92) }.isFailure) }
    }
    @Test fun samplesCoverEveryChunkIncludingShortLastChunkAndAllChannels() {
        for ((width, height, expected) in listOf(Triple(1, 1, 3L), Triple(7, 1, 21L), Triple(8, 1, 24L), Triple(32769, 2, 30L))) {
            val p = GpuPrintScanProtocol.parsePlan(GpuPrintScanFixtures.envelope(GpuPrintScanFixtures.plan(width = width, height = height)), 92)
            assertEquals(expected, GpuPrintScanProtocol.expectedSamples(p))
        }
    }

    @Test fun requestNeedsExplicitWhitelistedStageAndExactCommonOwnerFields() {
        val context = RuntimeEnvironment.getApplication()
        val raw = GpuPrintScanFixtures.request(context)
        val request = GpuRenderProtocol.parseRequest(raw, context)
        assertEquals(GpuRenderOperation.PRINT_SCAN, request.operation)
        assertTrue(GpuRenderProtocol.matches(request, GpuPrintScanFixtures.parsedPlan()))
        assertFalse(GpuRenderProtocol.matches(request, GpuRenderFixtures.parsedPlan()))
        for (operation in listOf("../../other", "fullCameraDiffusion", "nativeCancelGpuPrintScan")) {
            assertTrue(runCatching { GpuRenderProtocol.parseRequest(JSONObject(raw).put("operation", operation).toString(), context) }.isFailure)
        }
        assertTrue(runCatching { GpuRenderProtocol.parseRequest(JSONObject(raw).put("totalPixels", 24_000_000L).toString(), context) }.isFailure)
        assertEquals(GpuRenderOperation.CAMERA_DIFFUSION, GpuRenderProtocol.parseRequest(GpuRenderFixtures.request(context), context).operation)
    }
    @Test fun summaryRoundTripsWithOnlyBoundedCountersAndStageIdentity() {
        val p = GpuPrintScanFixtures.parsedPlan()
        val result = GpuPrintScanProtocol.parseNativeResult(GpuPrintScanFixtures.result().toString(), p)
        assertEquals(GpuRenderCode.OK, result.first)
        val wire = GpuRenderProtocol.encodeResult(result.first, result.second)
        listOf("adapter", "SECRET", "/private", "token", "manifestSha256", "b".repeat(64)).forEach { assertFalse(wire.contains(it)) }
        assertEquals(result, GpuRenderProtocol.parseStageResult(wire, p))
        assertTrue(runCatching { GpuRenderProtocol.parseResult(wire, GpuRenderFixtures.parsedPlan()) }.isFailure)
    }
    @Test fun partialResultsUnknownBackendWeakenedGuardOrWrongIdentityNeverSucceed() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("token", 91) }, { it.put("mode", "directFilm") }, { it.put("operation", "fullCameraDiffusion") },
            { it.put("fullImage", false) }, { it.put("gpuUsed", false) }, { it.put("channels", 2) },
            { it.put("sampleCount", 24) }, { it.put("sampleMaxAbs", "NaN") }, { it.put("elapsedMs", 125001) },
            { it.put("backend", "driver controlled") }, { it.put("sampleToleranceAbsolute", 0.01) }, { it.put("sampleToleranceRelative", 0.0) })
        edits.forEach { edit -> val value = GpuPrintScanFixtures.result(); edit(value); assertTrue(runCatching { GpuPrintScanProtocol.parseNativeResult(value.toString(), GpuPrintScanFixtures.parsedPlan()) }.isFailure) }
    }
    @Test fun cancellationAndNumericRejectionRemainDistinct() {
        fun error(code: String) = GpuPrintScanProtocol.parseNativeResult(JSONObject().put("ok", false).put("code", code).toString(), GpuPrintScanFixtures.parsedPlan()).first
        assertEquals(GpuRenderCode.CANCELLED, error("CANCELLED")); assertEquals(GpuRenderCode.NUMERIC_REJECTED, error("GPU_FINISH_NUMERIC_CHECK"))
        assertEquals(GpuRenderCode.NATIVE_FAILED, error("/private/photo?secret"))
    }
}
