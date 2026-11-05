package dev.filmroom.engine

import android.content.Context
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hand-built hostile transport fixtures, never evidence of native execution, admission or accuracy. */
internal object GpuStreamingFixtures {
    private const val MIB = GpuRenderProtocol.MIB
    fun plan(operation: GpuRenderOperation, token: Long = 501, width: Int = 1025, height: Int = 1024): JSONObject {
        val n = width.toLong() * height
        val p = JSONObject().put("schemaVersion", 1).put("operation", operation.wire).put("token", token)
            .put("manifestSha256", "5".repeat(64)).put("width", width).put("height", height)
            .put("layout", GpuStreamingProtocol.layout(operation)).put("helperAllowanceBytes", 64 * MIB)
        var device = 32 * MIB + 65536; var host = 64 * MIB; var driver = 104 * MIB
        var disk = 68 * n + 65536; var passes = 3L
        when (operation) {
            GpuRenderOperation.HIGHLIGHT_STREAM_V1 -> {
                passes = 2; disk = 48 * n + 2 * MIB
                p.put("batchBytes", 4 * MIB).put("pipelineCount", 5).put("residentDeviceBytes", 28 * MIB + 65536)
                    .put("uploadStagingBytes", 4 * MIB).put("sourceSnapshotBytes", 24 * n).put("outputBytes", 24 * n)
                    .put("metadataDiskBytes", 2 * MIB)
            }
            GpuRenderOperation.FILM_OPTICS_STREAM_V1 -> { device = 40 * MIB + 65536; p.put("batchBytes", 4 * MIB) }
            GpuRenderOperation.FILM_GRAIN_STREAM_V1 -> {
                device += 4800 + 544L * 16384; host += 9600; driver = 128 * MIB; disk += 2 * MIB; passes = 12
                p.put("grainActive", true).put("sublayers", true).put("layerCount", 3).put("chunkPixels", 16384)
                    .put("chunkCount", (n + 16383) / 16384).put("rngMode", "coordinateSplitMix64")
                    .put("distributionArithmetic", "guardedSoftwareBinary64").put("microActive", false)
                    .put("finalBlurActive", true).put("amountBlendActive", false)
            }
            GpuRenderOperation.FILM_DIR_STREAM_V1, GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1 -> {
                val log = operation == GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1
                val pipelines = if (log) 6 else 5
                if (log) host += MIB
                driver = (64 + pipelines * 8) * MIB; disk = 68 * n + 2 * MIB; passes = 12
                p.put("dirActive", true).put("curveKnots", 6).put("preCurveKnots", 6)
                    .put("chunkPixels", 65536).put("chunkCount", (n + 65535) / 65536).put("pipelineCount", pipelines)
            }
            GpuRenderOperation.SCAN_TAIL_STREAM_V1, GpuRenderOperation.FINISH_TAIL_STREAM_V1 -> {
                val fused = operation == GpuRenderOperation.FINISH_TAIL_STREAM_V1
                val tables = if (fused) 4096L else 0L
                if (fused) { device += 8 * (64 + tables); host += 16 * tables + 8 * MIB + 256 }
                driver = 360 * MIB; disk = 48 * n + 8 * tables + 3 * MIB; passes = 6
                p.put("mode", if (fused) "directFilm" else "standaloneScan").put("tableScalars", tables)
                    .put("externalGlare", false).put("glareActive", false)
                    .put("sampleCount", passes * (width.toLong() + height) + (if (fused) 6 else 3) * GpuStreamingProtocol.pointSamples(n, 32768))
            }
            else -> error("Not streaming")
        }
        return p.put("passCount", passes).put("deviceBytes", device).put("hostBytes", host)
            .put("driverAllowanceBytes", driver).put("combinedPeakBytes", device + host + driver + 64 * MIB).put("diskBytes", disk)
    }
    /** A valid original camera plan that fits fixed=128 MiB, but not fixed=137 MiB. */
    fun nearCapCamera(token: Long = 901): JSONObject {
        val side = 4096L; val radius = 1900L; val core = side - 2 * radius; val kernel = 2 * radius + 1
        val device = 3 * side * side * 8 + kernel * kernel * 4 + side * 8 + core * core * 8 + 8 * MIB + 65536
        return JSONObject().put("schemaVersion", 1).put("operation", "fullCameraDiffusion").put("token", token)
            .put("manifestSha256", "a".repeat(64)).put("width", 128).put("height", 128)
            .put("radius", radius).put("fftSide", side).put("core", core).put("tileCount", 3)
            .put("deviceBytes", device).put("hostBytes", 56 * MIB).put("driverAllowanceBytes", 64 * MIB)
            .put("helperAllowanceBytes", 64 * MIB).put("combinedPeakBytes", device + 184 * MIB)
            .put("diskBytes", 48L * 128 * 128 + 3 * kernel * kernel * 8 + 65536)
    }
    fun envelope(p: JSONObject): String = JSONObject().put("ok", true).put("schemaVersion", 1)
        .put("operation", "planGpuStreamingStage").put("plan", p).toString()
    fun parsed(operation: GpuRenderOperation, token: Long = 501, width: Int = 1025, height: Int = 1024) =
        GpuStreamingProtocol.parsePlan(envelope(plan(operation, token, width, height)), token, operation)
    fun request(context: Context, operation: GpuRenderOperation, token: Long = 501): String {
        val p = parsed(operation, token)
        val dir = File(context.cacheDir, "gpu-render/gpu-stage-$token").apply { mkdirs() }
        return JSONObject().put("schemaVersion", 1).put("operation", operation.wire).put("token", token)
            .put("parentHandle", 11L).put("jobDir", dir.canonicalPath).put("manifestSha256", p.digest)
            .put("budgetBytes", p.combinedPeakBytes).put("width", p.width).put("height", p.height)
            .put("totalPixels", p.width.toLong() * p.height * 3).toString()
    }
    fun result(p: GpuStreamingPlan): JSONObject {
        val v = JSONObject().put("ok", true).put("schemaVersion", 1).put("operation", p.operation.wire)
            .put("token", p.token).put("manifestSha256", p.digest).put("width", p.width).put("height", p.height).put("channels", 3)
            .put("gpuUsed", true).put("fullImage", true).put("fullResolution", true).put("layout", p.layout)
            .put("backend", GpuStreamingProtocol.backend(p.operation)).put("deviceBytes", p.deviceBytes).put("combinedPeakBytes", p.combinedPeakBytes)
            .put("passCount", p.passCount).put("sampleCount", GpuStreamingProtocol.expectedSamples(p)).put("sampleMaxAbs", 1e-7).put("sampleRms", 1e-8)
            .put("elapsedMs", 10.0).put("vendor", 20803).put("uploadBytes", 1234L).put("readbackBytes", 1234L).put("dispatches", 100L)
            .put("nativeStageSealed", false).put("contextCount", 1).put("sampleToleranceAbsolute", 0.001).put("sampleToleranceRelative", 0.001)
            .put("sampleScope", "per_axis_f64_on_gpu_f32_input")
        val n = p.width.toLong() * p.height
        when (val d = p.details) {
            is GpuStreamingDetails.Highlight -> {
                val batches = (n + 1048575) / 1048576; val chunks = (n + 65535) / 65536
                val dispatches = 4 + 4 * batches + 9 * chunks
                v.put("highlightPeak", -2.0).put("passes", 2).put("chunkCapacitySamples", 1048576)
                    .put("sampleScope", "original bounded f64 boost witnesses on GPU f32 inputs and full-image GPU peak; complete scalar finite/hash validation")
                    .put("sampleToleranceAbsolute", 0.002).put("sampleToleranceRelative", 0.002)
                    .put("dispatches", dispatches).put("uploadBytes", 24 * n + 160 * dispatches).put("readbackBytes", 12 * n + 80 + 4 * batches)
            }
            is GpuStreamingDetails.Optics -> Unit
            is GpuStreamingDetails.Dir -> {
                v.put("dirActive", d.active).put("chunkCount", d.chunkCount).put("pointwiseToleranceAbsolute", 2e-5).put("pointwiseToleranceRelative", 2e-5)
                    .put("sampleScope", GpuFilmDirProtocol.SAMPLE_SCOPE)
                if (p.operation == GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1)
                    v.put("logSampleCount", (if (d.active) 12 else 9) * GpuStreamingProtocol.pointSamples(n, 65536)).put("logSampleMaxAbs", 1e-7).put("logSampleRms", 1e-8)
            }
            is GpuStreamingDetails.Finish -> v.put("sampleScope", "full_line_f64_tail_f32_and_optional_prefix_f64_witnesses")
                .put("prefixSampleCount", if (d.mode == GpuStreamingFinishMode.STANDALONE) 0 else 3 * GpuStreamingProtocol.pointSamples(n, 32768))
                .put("glareGenerationGpu", false).put("glareRandomWords", 0L).put("glareChunks", 0L).put("glarePredicateScope", "disabled").put("glareAmbiguityBound", 0.0)
            is GpuStreamingDetails.Grain -> {
                v.put("grainActive", d.active).put("sublayers", d.sublayers).put("layerCount", d.layerCount).put("chunkCount", d.chunkCount)
                    .put("rngMode", "coordinateSplitMix64").put("distributionArithmetic", "guardedSoftwareBinary64")
                    .put("microActive", d.microActive).put("finalBlurActive", d.finalBlurActive).put("amountBlendActive", d.amountBlendActive)
                    .put("samplerSampleCount", 3280L).put("exactRngWordsChecked", 98304L).put("softwareArithmeticChecks", 1280L).put("numericFlagReads", 1L)
                    .put("samplerMaxAbs", 0.0).put("samplerRms", 0.0).put("stochasticScope", GpuFilmGrainProtocol.STOCHASTIC_SCOPE)
                    .put("microstructureArithmetic", GpuFilmGrainProtocol.MICROSTRUCTURE)
                for (key in listOf("poissonCountMismatches", "binomialCountMismatches", "randomWordCountMismatches", "samplerTupleMismatches")) v.put(key, 0L)
            }
        }
        return v
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GpuStreamingProtocolTest {
    @Test fun fiftyMegapixelAdmissionIsBoundedAndDistinctFromResidentBudgets() {
        for (op in GpuStreamingProtocol.operations) {
            val small = GpuStreamingFixtures.parsed(op)
            val large = GpuStreamingFixtures.parsed(op, width = 8192, height = 6144)
            assertEquals(small.deviceBytes, large.deviceBytes)
            assertEquals(small.combinedPeakBytes, large.combinedPeakBytes)
            assertTrue(large.diskBytes > small.diskBytes)
            for (other in GpuRenderOperation.entries.filter { it != op })
                assertTrue(runCatching { GpuRenderProtocol.parseStagePlan(GpuStreamingFixtures.envelope(GpuStreamingFixtures.plan(op)), 501, other) }.isFailure)
        }
    }
    @Test fun exactFieldsPrimitiveTypesIdentitiesAndEveryBudgetComponentFailClosed() {
        for (op in GpuStreamingProtocol.operations) {
            val original = GpuStreamingFixtures.plan(op)
            val edits = listOf<(JSONObject) -> Unit>(
                { it.put("layout", "fullLineFileBackedV2") }, { it.put("extra", true) }, { it.put("token", 502) },
                { it.put("manifestSha256", "invalid") }, { it.put("schemaVersion", 2) }, { it.put("width", 40001) },
                { it.put("height", 0) }, { it.put("width", "1025") }, { it.put("passCount", true) })
            for (edit in edits) {
                val changed = JSONObject(original.toString()); edit(changed)
                assertTrue(runCatching { GpuStreamingProtocol.parsePlan(GpuStreamingFixtures.envelope(changed), 501, op) }.isFailure)
            }
            for (key in listOf("deviceBytes", "hostBytes", "driverAllowanceBytes", "helperAllowanceBytes", "combinedPeakBytes", "diskBytes"))
                for (value in listOf<Any>(original.getLong(key) - 1, original.getLong(key) + 1, "1", 1.0, Long.MAX_VALUE)) {
                    val changed = JSONObject(original.toString()).put(key, value)
                    assertTrue(runCatching { GpuStreamingProtocol.parsePlan(GpuStreamingFixtures.envelope(changed), 501, op) }.isFailure)
                }
        }
    }
    @Test fun reportsKeepIdentityAndNeverClaimNativeStageSeal() {
        for (op in GpuStreamingProtocol.operations) {
            val p = GpuStreamingFixtures.parsed(op)
            val (code, summary) = GpuStreamingProtocol.parseNativeResult(GpuStreamingFixtures.result(p).toString(), p)
            assertEquals(GpuRenderCode.OK, code); assertNotNull(summary)
            assertEquals(code, GpuStreamingProtocol.parseResult(GpuRenderProtocol.encodeResult(code, summary), p).first)
            for ((key, value) in listOf("nativeStageSealed" to true, "contextCount" to 2, "token" to 502,
                "manifestSha256" to "6".repeat(64), "backend" to "resident", "fullResolution" to false,
                "sampleCount" to 0L, "sampleToleranceAbsolute" to 0.003, "vendor" to "20803", "extra" to 1)) {
                val changed = GpuStreamingFixtures.result(p).put(key, value)
                assertTrue("$op: $key", runCatching { GpuStreamingProtocol.parseNativeResult(changed.toString(), p) }.isFailure)
            }
        }
    }
    @Test fun operationSpecificModesCannotBorrowOtherSchemasOrAllowances() {
        val cases = listOf(
            Triple(GpuRenderOperation.FILM_GRAIN_STREAM_V1, "rngMode", "sequentialMt19937"),
            Triple(GpuRenderOperation.FILM_GRAIN_STREAM_V1, "chunkPixels", 256),
            Triple(GpuRenderOperation.FILM_DIR_STREAM_V1, "pipelineCount", 2),
            Triple(GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1, "chunkPixels", 1),
            Triple(GpuRenderOperation.SCAN_TAIL_STREAM_V1, "tableScalars", 1),
            Triple(GpuRenderOperation.FINISH_TAIL_STREAM_V1, "mode", "printExposureOnly"),
            Triple(GpuRenderOperation.FINISH_TAIL_STREAM_V1, "externalGlare", true),
            Triple(GpuRenderOperation.FINISH_TAIL_STREAM_V1, "tableScalars", Long.MAX_VALUE))
        for ((op, key, value) in cases) assertTrue(runCatching {
            GpuStreamingProtocol.parsePlan(GpuStreamingFixtures.envelope(GpuStreamingFixtures.plan(op).put(key, value)), 501, op)
        }.isFailure)
    }
    @Test fun originalSwitchesBoundAllStreamingAllowedOperations() {
        assertTrue(GpuExportRouter.operations(false, false, false, false, false, false).isEmpty())
        assertEquals(setOf(GpuRenderOperation.FILM_OPTICS_STREAM_V1), GpuExportRouter.operations(false, false, true, false, false, false).intersect(GpuStreamingProtocol.operations))
        assertEquals(setOf(GpuRenderOperation.FILM_DIR_STREAM_V1), GpuExportRouter.operations(false, false, false, false, true, false).intersect(GpuStreamingProtocol.operations))
        for (mask in 0 until 64) {
            val allowed = GpuExportRouter.operations(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0,
                mask and 8 != 0, mask and 16 != 0, mask and 32 != 0)
            assertEquals(mask and 32 != 0, GpuRenderOperation.HIGHLIGHT_STREAM_V1 in allowed)
        }
        assertFalse(GpuRenderOperation.FINISH_TAIL_STREAM_V1 in GpuExportRouter.operations(false, false, false, false, false, true))
        assertEquals(GpuStreamingProtocol.operations, GpuExportRouter.operations(true, true, true, true, true, true).intersect(GpuStreamingProtocol.operations))
    }
    @Test fun fixedReserveIncreaseKeepsPrepaidCapAndRejectsFreshOverflow() {
        val budget = GpuScienceSessionBudget(768 * GpuRenderProtocol.MIB, 128 * GpuRenderProtocol.MIB, 64)
        val log = GpuStreamingFixtures.parsed(GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1)
        val next = requireNotNull(budget.forValidatedStage(log))
        assertEquals(129 * GpuRenderProtocol.MIB, next.fixedBytes)
        assertEquals(budget.prepaidBytes, next.prepaidBytes); assertEquals(budget.pipelineLimit, next.pipelineLimit)
        val grain = requireNotNull(budget.forValidatedStage(GpuStreamingFixtures.parsed(GpuRenderOperation.FILM_GRAIN_STREAM_V1)))
        assertEquals(129 * GpuRenderProtocol.MIB, grain.fixedBytes)
        assertNull(budget.forValidatedStage(log.copy(hostBytes = Long.MAX_VALUE)))
        assertNull(budget.copy(prepaidBytes = 256 * GpuRenderProtocol.MIB).forValidatedStage(log))
    }
    @Test fun fixedReserveCanShrinkOnlyWhenNeededForAValidatedFreshFit() {
        val original = GpuScienceSessionBudget(768 * GpuRenderProtocol.MIB, 137 * GpuRenderProtocol.MIB, 64)
        val camera = GpuRenderProtocol.parsePlan(GpuRenderFixtures.envelope(GpuStreamingFixtures.nearCapCamera()), 901)
        val next = requireNotNull(original.forValidatedStage(camera))
        assertEquals(128 * GpuRenderProtocol.MIB, next.fixedBytes)
        assertEquals(137 * GpuRenderProtocol.MIB, original.fixedBytes)
        assertEquals(original.prepaidBytes, next.prepaidBytes)
        val small = GpuStreamingFixtures.parsed(GpuRenderOperation.FILM_OPTICS_STREAM_V1)
        assertEquals(original, original.forValidatedStage(small))
        assertNull(original.copy(prepaidBytes = 700 * GpuRenderProtocol.MIB).forValidatedStage(camera))
    }

    @Test fun duplicateIdentityAndIntegralFloatingPointTokensAreNotCoerced() {
        val op = GpuRenderOperation.FILM_DIR_STREAM_V1
        val raw = GpuStreamingFixtures.envelope(GpuStreamingFixtures.plan(op))
        for (changed in listOf(raw.replace("\"token\":501", "\"token\":501,\"token\":501"),
            raw.replace("\"height\":1024", "\"height\":1024.0"),
            raw.replace("\"token\":501", "\"token\":501.0"), raw + "true")) {
            assertTrue(runCatching { GpuStreamingProtocol.parsePlan(changed, 501, op) }.isFailure)
        }
        val p = GpuStreamingFixtures.parsed(op)
        val result = GpuStreamingFixtures.result(p).toString().replace("\"token\":501", "\"token\":501,\"token\":501")
        assertTrue(runCatching { GpuStreamingProtocol.parseNativeResult(result, p) }.isFailure)
        assertEquals(GpuRenderCode.CANCELLED, GpuStreamingProtocol.parseNativeResult("""{"ok":false,"schemaVersion":1,"code":"CANCELLED"}""", p).first)
        assertTrue(runCatching { GpuStreamingProtocol.parseNativeResult("""{"ok":false,"code":"CANCELLED"}""", p) }.isFailure)
    }

    @Test fun knownClosedNativeAdmissionIsUnsupportedAndUnknownFailuresRemainFailures() {
        for (op in GpuStreamingProtocol.operations) {
            for (code in listOf("GPU_OPTICS_STREAM_PENDING", "GPU_GRAIN_STREAM_PENDING", "GPU_DIR_STREAM_PENDING", "GPU_FINISH_STREAM_PENDING"))
                assertEquals(GpuRenderCode.UNSUPPORTED, GpuStreamingProtocol.nativeFailure(op, code))
            assertEquals(GpuRenderCode.NATIVE_FAILED, GpuStreamingProtocol.nativeFailure(op, "UNKNOWN_STREAM_PENDING"))
        }
    }

    @Test fun highlightBudgetAndGeometryRemainCheckedAndFixedReserveDoesNotGrow() {
        val op = GpuRenderOperation.HIGHLIGHT_STREAM_V1
        for ((width, height) in listOf(1 to 1, 1025 to 1025, 8192 to 6144, 40000 to 40000)) {
            val plan = GpuStreamingFixtures.parsed(op, width = width, height = height)
            val d = plan.details as GpuStreamingDetails.Highlight
            val n = width.toLong() * height
            assertEquals(33_619_968L, plan.deviceBytes); assertEquals(276_889_600L, plan.combinedPeakBytes)
            assertEquals(48 * n + 2_097_152L, plan.diskBytes)
            assertEquals(24 * n, d.sourceSnapshotBytes); assertEquals(24 * n, d.outputBytes)
            val budget = GpuScienceSessionBudget(768 * GpuRenderProtocol.MIB, 128 * GpuRenderProtocol.MIB, 64)
            assertEquals(budget, budget.forValidatedStage(plan))
            assertEquals(GpuRenderCode.OK, GpuStreamingProtocol.parseNativeResult(GpuStreamingFixtures.result(plan).toString(), plan).first)
        }
        val original = GpuStreamingFixtures.plan(op)
        for (key in listOf("batchBytes", "pipelineCount", "residentDeviceBytes", "uploadStagingBytes",
            "sourceSnapshotBytes", "outputBytes", "metadataDiskBytes", "passCount")) {
            for (value in listOf<Any>(original.getLong(key) - 1, original.getLong(key) + 1, Long.MAX_VALUE, "2", true, JSONObject.NULL)) {
                val bad = JSONObject(original.toString()).put(key, value)
                assertTrue("$key=$value", runCatching { GpuStreamingProtocol.parsePlan(GpuStreamingFixtures.envelope(bad), 501, op) }.isFailure)
            }
            val missing = JSONObject(original.toString()).apply { remove(key) }
            assertTrue(runCatching { GpuStreamingProtocol.parsePlan(GpuStreamingFixtures.envelope(missing), 501, op) }.isFailure)
            // JSONObject can serialize an integral Double without its decimal point. Exercise the wire token directly.
            val decimal = GpuStreamingFixtures.envelope(original).replace("\"$key\":${original.getLong(key)}", "\"$key\":${original.getLong(key)}.0")
            assertTrue(runCatching { GpuStreamingProtocol.parsePlan(decimal, 501, op) }.isFailure)
        }
        for ((key, value) in listOf("width" to Long.MAX_VALUE, "height" to 40001, "width" to 0,
            "manifestSha256" to "A".repeat(64), "manifestSha256" to "0".repeat(63), "sampleWidth" to 1)) {
            assertTrue(runCatching { GpuStreamingProtocol.parsePlan(GpuStreamingFixtures.envelope(JSONObject(original.toString()).put(key, value)), 501, op) }.isFailure)
        }
        val raw = GpuStreamingFixtures.envelope(original)
        assertTrue(runCatching { GpuStreamingProtocol.parsePlan(raw.replace("\"pipelineCount\":5", "\"pipelineCount\":5,\"pipelineCount\":5"), 501, op) }.isFailure)
        val parsed = GpuStreamingFixtures.parsed(op)
        assertTrue(runCatching { GpuStreamingProtocol.validatePlan(parsed.copy(details = (parsed.details as GpuStreamingDetails.Highlight).copy(sourceSnapshotBytes = Long.MAX_VALUE))) }.isFailure)
    }

    @Test fun highlightSummaryPreservesNegativePeakAndExactTwoPassWork() {
        val op = GpuRenderOperation.HIGHLIGHT_STREAM_V1
        // These cover the original 64 Ki-sample witness and 4 MiB production boundaries.
        for ((width, height) in listOf(1 to 1, 256 to 256, 257 to 256, 1024 to 1024, 1025 to 1024, 40000 to 40000)) {
            val p = GpuStreamingFixtures.parsed(op, width = width, height = height)
            val native = GpuStreamingFixtures.result(p)
            val safe = requireNotNull(GpuStreamingProtocol.parseNativeResult(native.toString(), p).second)
            assertEquals(-2.0, safe.json().getDouble("highlightPeak"), 0.0)
            assertFalse(safe.json().has("ok"))
            for (key in listOf("passes", "chunkCapacitySamples", "dispatches", "uploadBytes", "readbackBytes", "sampleCount")) {
                for (value in listOf<Any>(native.getLong(key) - 1, native.getLong(key) + 1, 1.0, "1", true, JSONObject.NULL)) {
                    assertTrue("$key=$value", runCatching { GpuStreamingProtocol.parseNativeResult(JSONObject(native.toString()).put(key, value).toString(), p) }.isFailure)
                }
            }
            for ((key, value) in listOf("sampleScope" to GpuGlobalProtocol.PLANE_SCOPE, "sampleToleranceAbsolute" to 0.001,
                "sampleToleranceRelative" to 0.001, "highlightPeak" to "1", "highlightPeak" to JSONObject.NULL,
                "highlightPeak" to 1e300, "highlightPeak" to 1.000001e15, "highlightPeak" to -1.000001e15, "hostWork" to "ignored?", "adapter" to "private")) {
                assertTrue(runCatching { GpuStreamingProtocol.parseNativeResult(JSONObject(native.toString()).put(key, value).toString(), p) }.isFailure)
            }
        }
    }

    @Test fun highlightFailuresStayClosedAndNeverContainSuccessSummary() {
        val op = GpuRenderOperation.HIGHLIGHT_STREAM_V1
        val plan = GpuStreamingFixtures.parsed(op)
        val expected = mapOf("GPU_GLOBAL_STREAM_PENDING" to GpuRenderCode.UNSUPPORTED,
            "GPU_GLOBAL_STREAM_MEMORY_LIMIT" to GpuRenderCode.MEMORY_UNAVAILABLE,
            "INSUFFICIENT_GPU_GLOBAL_STREAM_MEMORY" to GpuRenderCode.MEMORY_UNAVAILABLE,
            "GPU_GLOBAL_NUMERIC_CHECK" to GpuRenderCode.NUMERIC_REJECTED,
            "NONFINITE_GLOBAL_INPUT" to GpuRenderCode.NUMERIC_REJECTED,
            "GPU_GLOBAL_STREAM_REPORT" to GpuRenderCode.INVALID_RESULT,
            "INVALID_GLOBAL_STREAM_SOURCE_DIGEST" to GpuRenderCode.INVALID_RESULT,
            "GPU_OPTICS_TIMEOUT" to GpuRenderCode.TIMED_OUT,
            "INVALID_GLOBAL_STREAM_UNKNOWN" to GpuRenderCode.NATIVE_FAILED,
            "GPU_GLOBAL_STREAM_PENDING_UNKNOWN" to GpuRenderCode.NATIVE_FAILED)
        for ((nativeCode, code) in expected) {
            val raw = JSONObject().put("ok", false).put("schemaVersion", 1).put("code", nativeCode)
            val result = GpuStreamingProtocol.parseNativeResult(raw.toString(), plan)
            assertEquals(code, result.first); assertNull(result.second)
            assertTrue(runCatching { GpuStreamingProtocol.parseNativeResult(raw.put("summary", GpuStreamingFixtures.result(plan)).toString(), plan) }.isFailure)
        }
    }

    @Test fun finishPrefixSampleCountsStayExactForStandaloneAndFusedReports() {
        for (op in listOf(GpuRenderOperation.SCAN_TAIL_STREAM_V1, GpuRenderOperation.FINISH_TAIL_STREAM_V1)) {
            val p = GpuStreamingFixtures.parsed(op)
            val expected = if (op == GpuRenderOperation.SCAN_TAIL_STREAM_V1) 0L
                else 3L * GpuStreamingProtocol.pointSamples(p.width.toLong() * p.height, 32768)
            // Both integer JVM representations encode legitimate JSON integer tokens.
            for (value in listOf<Any>(expected.toInt(), expected)) {
                val raw = GpuStreamingFixtures.result(p).put("prefixSampleCount", value).toString()
                val (code, summary) = GpuStreamingProtocol.parseNativeResult(raw, p)
                assertEquals(GpuRenderCode.OK, code)
                assertEquals(expected, requireNotNull(summary).json().getLong("prefixSampleCount"))
                assertEquals(GpuRenderCode.OK, GpuStreamingProtocol.parseResult(GpuRenderProtocol.encodeResult(code, summary), p).first)
            }
            for (value in listOf<Any>(expected - 1, expected + 1, expected.toString(), true, JSONObject.NULL)) {
                assertTrue(runCatching { GpuStreamingProtocol.parseNativeResult(GpuStreamingFixtures.result(p).put("prefixSampleCount", value).toString(), p) }.isFailure)
            }
            val raw = GpuStreamingFixtures.result(p).put("prefixSampleCount", expected).toString()
            val decimal = raw.replace("\"prefixSampleCount\":$expected", "\"prefixSampleCount\":$expected.0")
            assertNotEquals(raw, decimal)
            assertTrue(runCatching { GpuStreamingProtocol.parseNativeResult(decimal, p) }.isFailure)
        }
    }

}
