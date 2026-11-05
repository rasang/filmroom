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

/** Synthetic adversarial protocol inputs, NOT evidence of native execution or GPU accuracy. */
internal object GpuExposureLogDirFixtures {
    fun plan(token: Long = 97): JSONObject {
        val pixels = 17L * 11
        val device = 20 * pixels + 8 * GpuRenderProtocol.MIB + 65536
        return JSONObject().put("schemaVersion", 1).put("operation", "fullExposureLogDir").put("token", token)
            .put("manifestSha256", "f".repeat(64)).put("width", 17).put("height", 11)
            .put("dirActive", true).put("curveKnots", 6).put("preCurveKnots", 6)
            .put("chunkPixels", pixels).put("chunkCount", 1).put("passCount", 12)
            .put("deviceBytes", device).put("hostBytes", 17 * GpuRenderProtocol.MIB)
            .put("driverAllowanceBytes", 112 * GpuRenderProtocol.MIB).put("helperAllowanceBytes", 64 * GpuRenderProtocol.MIB)
            .put("combinedPeakBytes", device + 193 * GpuRenderProtocol.MIB).put("diskBytes", 48 * pixels + 2 * GpuRenderProtocol.MIB)
    }
    fun envelope(p: JSONObject = plan()): String = JSONObject().put("ok", true).put("schemaVersion", 1)
        .put("operation", "planGpuExposureLogDirStage").put("plan", p).toString()
    fun parsedPlan(token: Long = 97) = GpuExposureLogDirProtocol.parsePlan(envelope(plan(token)), token)
    fun request(context: Context, token: Long = 97): String {
        val p = parsedPlan(token)
        val directory = File(context.cacheDir, "gpu-render/gpu-stage-$token").apply { mkdirs() }
        return JSONObject().put("schemaVersion", 1).put("operation", p.operation.wire).put("token", token)
            .put("parentHandle", 11L).put("jobDir", directory.canonicalPath).put("manifestSha256", p.digest)
            .put("budgetBytes", p.combinedPeakBytes).put("width", p.width).put("height", p.height)
            .put("totalPixels", p.width.toLong() * p.height * 3).toString()
    }
    fun result(p: GpuExposureLogDirPlan = parsedPlan()): JSONObject = GpuExposureLogDirSummary(
        p.width, p.height, p.dirActive, p.chunkCount, p.passCount, 10.0, 1.0, 1.0, 2.0, 1.0, 1.0,
        20803, false, 100, 100, 1, p.deviceBytes, GpuExposureLogDirProtocol.expectedSamples(p), 1e-6, 1e-7,
        GpuExposureLogDirProtocol.expectedLogSamples(p), 1e-6, 1e-7,
    ).json().put("ok", true).put("token", p.token).put("manifestSha256", p.digest)
        .put("backend", GpuExposureLogDirProtocol.NATIVE_BACKEND)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class GpuExposureLogDirProtocolTest {
    @Test fun closedPlanRequiresCompoundIdentityAndExactBudgets() {
        val p = GpuExposureLogDirFixtures.parsedPlan()
        assertEquals(GpuRenderOperation.EXPOSURE_LOG_DIR, p.operation)
        val edits = listOf<(JSONObject) -> Unit>(
            { it.put("operation", "fullFilmDirDensity") }, { it.put("token", 96) },
            { it.put("manifestSha256", "bad") }, { it.put("extra", true) },
            { it.remove("dirActive") }, { it.put("hostBytes", p.hostBytes - 1) },
            { it.put("driverAllowanceBytes", p.driverAllowanceBytes - 1) },
            { it.put("deviceBytes", p.deviceBytes - 1) }, { it.put("diskBytes", p.diskBytes - 1) },
            { it.put("combinedPeakBytes", p.combinedPeakBytes - 1) }, { it.put("chunkCount", 2) },
        )
        for (edit in edits) {
            val raw = GpuExposureLogDirFixtures.plan(); edit(raw)
            assertTrue(runCatching { GpuExposureLogDirProtocol.parsePlan(GpuExposureLogDirFixtures.envelope(raw), 97) }.isFailure)
        }
        assertTrue(runCatching { GpuFilmDirProtocol.parsePlan(GpuExposureLogDirFixtures.envelope(), 97) }.isFailure)
    }
    @Test fun resultRequiresExactCountsIdentityAndFiniteErrors() {
        val p = GpuExposureLogDirFixtures.parsedPlan()
        assertEquals(96L, GpuExposureLogDirProtocol.expectedLogSamples(p))
        assertEquals(GpuRenderCode.OK, GpuExposureLogDirProtocol.parseNativeResult(GpuExposureLogDirFixtures.result(p).toString(), p).first)
        for ((key, value) in listOf("operation" to "fullFilmDirDensity", "token" to 96,
            "manifestSha256" to "e".repeat(64), "sampleCount" to 1, "logSampleCount" to 95,
            "logSampleMaxAbs" to -1, "logSampleRms" to "NaN", "sampleRms" to "Infinity")) {
            assertTrue(key, runCatching { GpuExposureLogDirProtocol.parseNativeResult(GpuExposureLogDirFixtures.result(p).put(key, value).toString(), p) }.isFailure)
        }
        val inactive = p.copy(dirActive = false, passCount = 0)
        assertEquals(72L, GpuExposureLogDirProtocol.expectedLogSamples(inactive))
        val twoChunks = p.copy(width = 257, height = 256, chunkPixels = 65536, chunkCount = 2)
        assertEquals(192L, GpuExposureLogDirProtocol.expectedLogSamples(twoChunks))
    }
    @Test fun sanitizedIpcIsClosedAndDoesNotExposeNativeText() {
        val p = GpuExposureLogDirFixtures.parsedPlan()
        val native = GpuExposureLogDirFixtures.result(p).put("adapter", "SECRET /private/input")
        val summary = GpuExposureLogDirProtocol.parseNativeResult(native.toString(), p).second!!
        val ipc = JSONObject().put("schemaVersion", 1).put("code", "OK").put("summary", summary.json())
        assertFalse(ipc.toString().contains("SECRET"))
        assertEquals(GpuRenderCode.OK, GpuExposureLogDirProtocol.parseResult(ipc.toString(), p).first)
        ipc.getJSONObject("summary").put("extra", 1)
        assertTrue(runCatching { GpuExposureLogDirProtocol.parseResult(ipc.toString(), p) }.isFailure)
        ipc.getJSONObject("summary").remove("extra")
        ipc.getJSONObject("summary").remove("logSampleRms")
        assertTrue(runCatching { GpuExposureLogDirProtocol.parseResult(ipc.toString(), p) }.isFailure)
    }
    @Test fun componentFailuresStayInsideClosedFailureVocabulary() {
        assertEquals(GpuRenderCode.MEMORY_UNAVAILABLE, GpuRenderProtocol.nativeFailure(GpuRenderOperation.EXPOSURE_LOG_DIR, "GPU_LOG_DIR_MEMORY_LIMIT"))
        assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuRenderProtocol.nativeFailure(GpuRenderOperation.EXPOSURE_LOG_DIR, "NONFINITE_LOG_DIR_OUTPUT"))
        assertEquals(GpuRenderCode.UNSUPPORTED, GpuRenderProtocol.nativeFailure(GpuRenderOperation.EXPOSURE_LOG_DIR, "GPU_LOG_DIR_UNSUPPORTED"))
        assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuRenderProtocol.nativeFailure(GpuRenderOperation.EXPOSURE_LOG_DIR, "GPU_GLOBAL_NUMERIC_CHECK"))
        assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuRenderProtocol.nativeFailure(GpuRenderOperation.EXPOSURE_LOG_DIR, "GPU_DIR_NUMERIC_CHECK"))
        assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuRenderProtocol.nativeFailure(GpuRenderOperation.EXPOSURE_LOG_DIR, "GPU_OPTICS_NUMERIC_CHECK"))
        assertEquals(GpuRenderCode.NATIVE_FAILED, GpuRenderProtocol.nativeFailure(GpuRenderOperation.EXPOSURE_LOG_DIR, "SECRET"))
    }
}
