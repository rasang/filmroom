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

internal object GpuRenderFixtures {
    fun plan(): JSONObject {
        val n = 4096L; val radius = 890L; val k = radius * 2 + 1; val core = n - 2 * radius
        val device = 3 * n * n * 8 + k * k * 4 + n * 8 + minOf(8 * GpuRenderProtocol.MIB, core * core * 8) + 8 * GpuRenderProtocol.MIB + 65536
        return JSONObject().put("schemaVersion", 1).put("operation", "fullCameraDiffusion").put("token", 91L)
            .put("manifestSha256", "a".repeat(64)).put("width", 6000).put("height", 4000).put("radius", radius)
            .put("fftSide", n).put("core", core).put("tileCount", 18).put("deviceBytes", device)
            .put("hostBytes", 56 * GpuRenderProtocol.MIB).put("driverAllowanceBytes", 64 * GpuRenderProtocol.MIB)
            .put("helperAllowanceBytes", 64 * GpuRenderProtocol.MIB).put("combinedPeakBytes", device + 184 * GpuRenderProtocol.MIB)
            .put("diskBytes", 6L * 6000 * 4000 * 8 + 3 * k * k * 8 + 65536)
    }
    fun envelope(plan: JSONObject = plan()) = JSONObject().put("ok", true).put("schemaVersion", 1)
        .put("operation", "planGpuOpticalStage").put("plan", plan).toString()
    fun parsedPlan() = GpuRenderProtocol.parsePlan(envelope(), 91)
    fun request(context: Context): String {
        val dir = File(context.cacheDir, "gpu-render/gpu-stage-91").apply { mkdirs() }
        return JSONObject().put("schemaVersion", 1).put("token", 91L).put("parentHandle", 11L)
            .put("jobDir", dir.canonicalPath).put("manifestSha256", "a".repeat(64))
            .put("budgetBytes", parsedPlan().combinedPeakBytes).put("width", 6000).put("height", 4000)
            .put("totalPixels", 72_000_000L).toString()
    }
    fun result(): JSONObject = JSONObject().put("ok", true).put("schemaVersion", 1).put("operation", "fullCameraDiffusion")
        .put("token", 91L).put("manifestSha256", "a".repeat(64)).put("gpuUsed", true).put("fullImage", true)
        .put("width", 6000).put("height", 4000).put("channels", 3).put("tileCount", 18)
        .put("elapsedMs", 1234.5).put("backend", "Vulkan f32 FFT and scatter")
        .put("adapter", "/private/photo SECRET_DRIVER_TEXT").put("vendor", 20803).put("shaderF64Advertised", false)
        .put("uploadBytes", 180_000_000L).put("readbackBytes", 240_000_000L).put("dispatches", 1000)
        .put("deviceBytes", parsedPlan().deviceBytes).put("sampleCount", 18).put("sampleMaxAbs", 1e-6)
        .put("sampleRms", 1e-7).put("sampleToleranceRelative", 2e-5).put("sampleToleranceAbsolute", 2e-5)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class GpuRenderProtocolTest {
    @Test fun exactFullStagePlanPreservesImageKernelAndAdditionalPeak() {
        val p = GpuRenderFixtures.parsedPlan()
        assertEquals(4096, p.fftSide); assertEquals(2316, p.core); assertEquals(18L, p.tileCount)
        assertEquals(p.deviceBytes + 184 * GpuRenderProtocol.MIB, p.combinedPeakBytes)
        assertTrue(p.combinedPeakBytes > 512 * GpuRenderProtocol.MIB)
        assertTrue(p.combinedPeakBytes < GpuRenderProtocol.HARD_CAP)
    }
    @Test fun rejectsIncorrectPeakGeometryCapsAndUnboundedFields() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("hostBytes", 32 * GpuRenderProtocol.MIB) }, { it.put("deviceBytes", 1) },
            { it.put("combinedPeakBytes", Long.MAX_VALUE) }, { it.put("tileCount", 65) },
            { it.put("radius", 2048) }, { it.put("core", 2048) }, { it.put("fftSide", 2048) },
            { it.put("diskBytes", 0) }, { it.put("token", "91") }, { it.put("sourcePath", "/photo") })
        edits.forEach { edit -> val p = GpuRenderFixtures.plan(); edit(p); assertTrue(runCatching { GpuRenderProtocol.parsePlan(GpuRenderFixtures.envelope(p), 91) }.isFailure) }
        assertTrue(runCatching { GpuRenderProtocol.objectOf(" ".repeat(16385)) }.isFailure)
    }
    @Test fun preservesOnlyBoundedThreeChannelSampleMetricsWithoutDriverTextOrIdentifiers() {
        val p = GpuRenderFixtures.parsedPlan()
        val result = GpuRenderProtocol.parseNativeResult(GpuRenderFixtures.result().toString(), p)
        assertEquals(GpuRenderCode.OK, result.first)
        val wire = GpuRenderProtocol.encodeResult(result.first, result.second)
        listOf("adapter", "SECRET", "/private", "token", "manifestSha256", "a".repeat(64)).forEach { assertFalse(wire.contains(it)) }
        assertEquals(result, GpuRenderProtocol.parseResult(wire, p))
    }
    @Test fun partialOutputsWrongTokenNonfiniteOrWeakenedSampleToleranceCannotSucceed() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("channels", 2) }, { it.put("token", 92) }, { it.put("fullImage", false) },
            { it.put("gpuUsed", false) }, { it.put("sampleCount", 17) }, { it.put("sampleMaxAbs", "NaN") },
            { it.put("sampleToleranceRelative", 0.01) }, { it.put("sampleToleranceAbsolute", 0.0) },
            { it.put("backend", "arbitrary driver") }, { it.put("elapsedMs", 125001) })
        edits.forEach { edit -> val value = GpuRenderFixtures.result(); edit(value); assertTrue(runCatching { GpuRenderProtocol.parseNativeResult(value.toString(), GpuRenderFixtures.parsedPlan()) }.isFailure) }
    }
    @Test fun numericalRejectionAndCancellationAreDistinctSafeStatuses() {
        fun error(code: String) = GpuRenderProtocol.parseNativeResult(JSONObject().put("ok", false).put("code", code).toString(), GpuRenderFixtures.parsedPlan()).first
        assertEquals(GpuRenderCode.NUMERIC_REJECTED, error("GPU_STAGE_NUMERIC_CHECK"))
        assertEquals(GpuRenderCode.CANCELLED, error("CANCELLED"))
        assertEquals(GpuRenderCode.NATIVE_FAILED, error("/private/photo?secret"))
    }
}
