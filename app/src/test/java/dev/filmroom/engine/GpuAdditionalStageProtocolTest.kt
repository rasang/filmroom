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

/** Positive plans come from executed native prepare/plan, not a second handwritten budget model. */
private object GpuAdditionalStageFixtures {
    fun nativePlan(name: String, token: Long, digest: String): JSONObject {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("gpu-contracts/$name.json")) { "Missing executed native plan fixture: $name" }
        val root = stream.bufferedReader().use { JSONObject(it.readText()) }
        return root.getJSONObject("plan").put("token", token).put("manifestSha256", digest)
    }
    fun envelope(operation: String, plan: JSONObject): String = JSONObject().put("ok", true).put("schemaVersion", 1).put("operation", operation).put("plan", plan).toString()
    fun request(context: Context, p: GpuStagePlan): String {
        val dir = File(context.cacheDir, "gpu-render/gpu-stage-${p.token}").apply { mkdirs() }
        return JSONObject().put("schemaVersion", 1).put("operation", p.operation.wire).put("token", p.token).put("parentHandle", 11L)
            .put("jobDir", dir.canonicalPath).put("manifestSha256", p.digest).put("budgetBytes", p.combinedPeakBytes)
            .put("width", p.width).put("height", p.height).put("totalPixels", p.width.toLong() * p.height * 3).toString()
    }
    fun result(p: GpuStagePlan): JSONObject = JSONObject().put("ok", true).put("schemaVersion", 1).put("operation", p.operation.wire)
        .put("token", p.token).put("manifestSha256", p.digest).put("width", p.width).put("height", p.height)
        .put("gpuUsed", true).put("fullImage", true).put("channels", 3).put("deviceBytes", p.deviceBytes)
        .put("elapsedMs", 100.0).put("setupMs", 1.0).put("uploadMs", 2.0).put("guardMs", 3.0)
        .put("vendor", 20803).put("shaderF64Advertised", false).put("adapter", "/private/photo SECRET_DRIVER_TEXT")
        .put("sampleMaxAbs", 1e-6).put("sampleRms", 1e-7)
}
internal object GpuFilmExposureFixtures {
    fun plan(token: Long = 95) = GpuAdditionalStageFixtures.nativePlan("exposure", token, "d".repeat(64))
    fun envelope(plan: JSONObject = plan()) = GpuAdditionalStageFixtures.envelope("planGpuFilmExposureStage", plan)
    fun parsedPlan(token: Long = 95) = GpuFilmExposureProtocol.parsePlan(envelope(plan(token)), token)
    fun request(context: Context, token: Long = 95) = GpuAdditionalStageFixtures.request(context, parsedPlan(token))
    fun result(): JSONObject {
        val p = parsedPlan(); val pixels = p.width.toLong() * p.height
        return GpuAdditionalStageFixtures.result(p).put("chunkCount", p.chunkCount).put("backend", GpuFilmExposureProtocol.NATIVE_BACKEND)
            .put("dispatchReadbackMs", 5.0).put("dispatches", p.chunkCount)
            .put("uploadBytes", 4 * p.tableScalars + 128 + 12 * pixels + 4 * p.chunkCount + 4).put("readbackBytes", 12 * pixels + 4 * p.chunkCount)
            .put("sampleCount", GpuFilmExposureProtocol.expectedSamples(p)).put("sampleToleranceAbsolute", 2e-5).put("sampleToleranceRelative", 2e-5)
    }
}
internal object GpuFilmDirFixtures {
    fun plan(token: Long = 96) = GpuAdditionalStageFixtures.nativePlan("dir", token, "e".repeat(64))
    fun envelope(plan: JSONObject = plan()) = GpuAdditionalStageFixtures.envelope("planGpuFilmDirStage", plan)
    fun parsedPlan(token: Long = 96) = GpuFilmDirProtocol.parsePlan(envelope(plan(token)), token)
    fun request(context: Context, token: Long = 96) = GpuAdditionalStageFixtures.request(context, parsedPlan(token))
    fun result(p: GpuFilmDirPlan = parsedPlan()): JSONObject = GpuAdditionalStageFixtures.result(p)
        .put("dirActive", p.dirActive).put("chunkCount", p.chunkCount).put("passCount", p.passCount)
        .put("backend", GpuFilmDirProtocol.NATIVE_BACKEND).put("sampleScope", GpuFilmDirProtocol.SAMPLE_SCOPE)
        .put("dispatchWaitMs", 5.0).put("readbackMs", 6.0).put("dispatches", GpuFilmDirProtocol.maximumDispatches(p))
        .put("uploadBytes", 1234L).put("readbackBytes", 1234L).put("sampleCount", GpuFilmDirProtocol.expectedSamples(p))
        .put("sampleToleranceAbsolute", 0.001).put("sampleToleranceRelative", 0.001)
        .put("pointwiseToleranceAbsolute", 2e-5).put("pointwiseToleranceRelative", 2e-5)
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class GpuAdditionalStageProtocolTest {
    @Test fun executedNativeExposureAndDirPlansUseDistinctClosedRoutes() {
        val exposure = GpuFilmExposureFixtures.parsedPlan(); val dir = GpuFilmDirFixtures.parsedPlan()
        assertEquals(GpuRenderOperation.FILM_EXPOSURE, exposure.operation); assertEquals(GpuRenderOperation.FILM_DIR, dir.operation)
        assertTrue(runCatching { GpuFilmDirProtocol.parsePlan(GpuFilmExposureFixtures.envelope(), exposure.token) }.isFailure)
        assertTrue(runCatching { GpuFilmExposureProtocol.parsePlan(GpuFilmDirFixtures.envelope(), dir.token) }.isFailure)
    }
    @Test fun exposureRejectsChangedGridChunkBudgetAndSourceCopyReserve() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("tableScalars", 13) }, { it.put("chunkPixels", 0) }, { it.put("chunkCount", 0) },
            { it.put("hostBytes", 1) }, { it.put("deviceBytes", 1) }, { it.put("deviceBytes",it.getLong("deviceBytes")-8) }, { it.put("diskBytes", 1) },
            { it.put("combinedPeakBytes", GpuFilmExposureProtocol.HARD_CAP + 1) }, { it.put("mode", "directFilm") })
        edits.forEach { edit -> val p = GpuFilmExposureFixtures.plan(); edit(p); assertTrue(runCatching { GpuFilmExposureProtocol.parsePlan(GpuFilmExposureFixtures.envelope(p), 95) }.isFailure) }
    }
    @Test fun dirRejectsInvalidPassCurveCountAndDescriptorDiskAllowance() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("passCount", 1) }, { it.put("dirActive", false).put("passCount", 12) },
            { it.put("curveKnots", 1) }, { it.put("preCurveKnots", 4097) }, { it.put("chunkPixels", 0) },
            { it.put("hostBytes", 1) }, { it.put("diskBytes", 1) }, { it.put("dirActive", "true") })
        edits.forEach { edit -> val p = GpuFilmDirFixtures.plan(); edit(p); assertTrue(runCatching { GpuFilmDirProtocol.parsePlan(GpuFilmDirFixtures.envelope(p), 96) }.isFailure) }
    }
    @Test fun exposureSuccessNeedsCompleteTransfersDispatchesAndSampleCoverage() {
        val p = GpuFilmExposureFixtures.parsedPlan()
        val result = GpuFilmExposureProtocol.parseNativeResult(GpuFilmExposureFixtures.result().toString(), p)
        val wire = GpuRenderProtocol.encodeResult(result.first, result.second)
        assertEquals(result, GpuRenderProtocol.parseStageResult(wire, p))
        listOf("SECRET", "adapter", "/private", "manifestSha256").forEach { assertFalse(wire.contains(it)) }
        for((key,missing) in listOf("uploadBytes" to 4L,"readbackBytes" to 4*p.chunkCount)){
            val old=GpuFilmExposureFixtures.result();old.put(key,old.getLong(key)-missing)
            assertTrue("Missing numeric flag transfer must be rejected",runCatching{GpuFilmExposureProtocol.parseNativeResult(old.toString(),p)}.isFailure)
        }
        for (key in listOf("uploadBytes", "readbackBytes", "dispatches", "sampleCount")) {
            val r = GpuFilmExposureFixtures.result().put(key, 0)
            assertTrue(runCatching { GpuFilmExposureProtocol.parseNativeResult(r.toString(), p) }.isFailure)
        }
        assertTrue(runCatching { GpuFilmExposureProtocol.parseNativeResult(GpuFilmExposureFixtures.result().put("sampleToleranceRelative", 0.001).toString(), p) }.isFailure)
    }
    @Test fun dirGuardsAreScopedAndCoverActiveInactiveAndNoSpatialCases() {
        for ((active, passes) in listOf(false to 0, true to 0, true to 12)) {
            val p = GpuFilmDirProtocol.parsePlan(GpuFilmDirFixtures.envelope(GpuFilmDirFixtures.plan().put("dirActive", active).put("passCount", passes)), 96)
            val result = GpuFilmDirProtocol.parseNativeResult(GpuFilmDirFixtures.result(p).toString(), p)
            val wire = GpuRenderProtocol.encodeResult(result.first, result.second)
            assertEquals(result, GpuRenderProtocol.parseStageResult(wire, p))
            assertTrue(wire.contains(GpuFilmDirProtocol.SAMPLE_SCOPE)); assertFalse(wire.contains("SECRET"))
            for ((key, value) in listOf("sampleCount" to 0, "sampleScope" to "full_image_f64", "pointwiseToleranceAbsolute" to 0.001, "pointwiseToleranceRelative" to 0.0)) {
                assertTrue(runCatching { GpuFilmDirProtocol.parseNativeResult(GpuFilmDirFixtures.result(p).put(key, value).toString(), p) }.isFailure)
            }
        }
    }
    @Test fun knownNativeFailuresAreBoundedAndNeverAccidentallySucceed() {
        val prefixes = mapOf(GpuRenderOperation.CAMERA_DIFFUSION to "GPU_STAGE", GpuRenderOperation.PRINT_SCAN to "GPU_FINISH", GpuRenderOperation.FILM_OPTICS to "GPU_OPTICS", GpuRenderOperation.FILM_EXPOSURE to "GPU_EXPOSURE", GpuRenderOperation.FILM_DIR to "GPU_DIR")
        for ((operation, prefix) in prefixes) {
            assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuRenderProtocol.nativeFailure(operation, "${prefix}_NUMERIC_CHECK"))
            assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuRenderProtocol.nativeFailure(operation, "NONFINITE_${prefix.removePrefix("GPU_")}_OUTPUT"))
            assertEquals(GpuRenderCode.MEMORY_UNAVAILABLE, GpuRenderProtocol.nativeFailure(operation, "${prefix}_MEMORY_LIMIT"))
            assertEquals(GpuRenderCode.UNSUPPORTED, GpuRenderProtocol.nativeFailure(operation, "${prefix}_UNSUPPORTED"))
            assertEquals(GpuRenderCode.TIMED_OUT, GpuRenderProtocol.nativeFailure(operation, "${prefix}_TIMEOUT"))
            assertEquals(GpuRenderCode.CANCELLED, GpuRenderProtocol.nativeFailure(operation, "CANCELLED"))
            assertEquals(GpuRenderCode.NATIVE_FAILED, GpuRenderProtocol.nativeFailure(operation, "/photo SECRET"))
        }
        assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuFilmExposureProtocol.parseNativeResult("""{"ok":false,"code":"GPU_EXPOSURE_NONFINITE"}""", GpuFilmExposureFixtures.parsedPlan()).first)
        assertEquals(GpuRenderCode.NUMERIC_REJECTED, GpuFilmDirProtocol.parseNativeResult("""{"ok":false,"code":"NONFINITE_DIR_OUTPUT"}""", GpuFilmDirFixtures.parsedPlan()).first)
    }
}
