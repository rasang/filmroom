package dev.filmroom.engine

import dev.filmroom.engine.GpuScienceFields.bool
import dev.filmroom.engine.GpuScienceFields.exact
import dev.filmroom.engine.GpuScienceFields.long
import dev.filmroom.engine.GpuScienceFields.number
import org.json.JSONObject
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader

/** Versioned allocation domains. No resident plan, opcode or science parameter is accepted here. */
internal sealed interface GpuStreamingDetails {
    data class Optics(val batchBytes: Long) : GpuStreamingDetails
    data class Highlight(val batchBytes: Long, val pipelineCount: Int, val residentDeviceBytes: Long,
        val uploadStagingBytes: Long, val sourceSnapshotBytes: Long, val outputBytes: Long,
        val metadataDiskBytes: Long) : GpuStreamingDetails
    data class Dir(val active: Boolean, val curveKnots: Int, val preCurveKnots: Int,
        val chunkPixels: Int, val chunkCount: Long, val pipelineCount: Int) : GpuStreamingDetails
    data class Grain(val active: Boolean, val sublayers: Boolean, val layerCount: Int,
        val chunkPixels: Int, val chunkCount: Long, val microActive: Boolean,
        val finalBlurActive: Boolean, val amountBlendActive: Boolean) : GpuStreamingDetails
    data class Finish(val mode: GpuStreamingFinishMode, val tableScalars: Long,
        val externalGlare: Boolean, val glareActive: Boolean, val sampleCount: Long) : GpuStreamingDetails
}
internal enum class GpuStreamingFinishMode(val wire: String) {
    STANDALONE("standaloneScan"), DIRECT_FILM("directFilm"),
    PRINT_FROM_DENSITY("printFromDensity"), PRINT_FROM_RAW("printFromRaw");
    companion object { fun fromWire(value: Any?) = entries.singleOrNull { it.wire == value } ?: error("Invalid streaming finish mode") }
}
internal data class GpuStreamingPlan(
    override val operation: GpuRenderOperation, override val token: Long, override val digest: String,
    override val width: Int, override val height: Int, val layout: String, val passCount: Long,
    val details: GpuStreamingDetails,
    override val deviceBytes: Long, override val hostBytes: Long, override val driverAllowanceBytes: Long,
    override val helperAllowanceBytes: Long, override val combinedPeakBytes: Long, override val diskBytes: Long,
) : GpuStagePlan

internal object GpuStreamingProtocol {
    private const val MIB = GpuRenderProtocol.MIB
    private const val BATCH = 4 * MIB
    private const val MAX_DISK = 128L * 1024 * MIB
    val operations = setOf(GpuRenderOperation.FILM_OPTICS_STREAM_V1, GpuRenderOperation.FILM_GRAIN_STREAM_V1,
        GpuRenderOperation.FILM_DIR_STREAM_V1, GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1,
        GpuRenderOperation.SCAN_TAIL_STREAM_V1, GpuRenderOperation.FINISH_TAIL_STREAM_V1, GpuRenderOperation.HIGHLIGHT_STREAM_V1)
    private const val HIGHLIGHT_CHUNK = 1048576L
    private const val HIGHLIGHT_SCOPE = "original bounded f64 boost witnesses on GPU f32 inputs and full-image GPU peak; complete scalar finite/hash validation"
    private val highlightKeys = setOf("batchBytes", "pipelineCount", "residentDeviceBytes", "uploadStagingBytes",
        "sourceSnapshotBytes", "outputBytes", "metadataDiskBytes")
    private val dirKeys = setOf("dirActive", "curveKnots", "preCurveKnots", "chunkPixels", "chunkCount", "pipelineCount")
    private val grainKeys = setOf("grainActive", "sublayers", "layerCount", "chunkPixels", "chunkCount", "rngMode",
        "distributionArithmetic", "microActive", "finalBlurActive", "amountBlendActive")
    private val finishKeys = setOf("mode", "tableScalars", "externalGlare", "glareActive", "sampleCount")
    fun layout(operation: GpuRenderOperation): String = when (operation) {
        GpuRenderOperation.HIGHLIGHT_STREAM_V1 -> "twoPassFileBackedHighlightV1"
        GpuRenderOperation.FILM_OPTICS_STREAM_V1 -> "fullLineFileBackedV1"
        GpuRenderOperation.FILM_GRAIN_STREAM_V1 -> "fullLineCoordinateGrainV1"
        GpuRenderOperation.FILM_DIR_STREAM_V1, GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1 -> "fullLineDirV1"
        GpuRenderOperation.SCAN_TAIL_STREAM_V1, GpuRenderOperation.FINISH_TAIL_STREAM_V1 -> "fullLineFileBackedFinishV1"
        else -> error("Not a streaming operation")
    }
    fun backend(operation: GpuRenderOperation): String = when (operation) {
        GpuRenderOperation.HIGHLIGHT_STREAM_V1 -> "gpu_global_highlight_stream_f32_v1"
        GpuRenderOperation.FILM_OPTICS_STREAM_V1 -> "gpu_spatial_stream_f32_v1"
        GpuRenderOperation.FILM_GRAIN_STREAM_V1 -> "gpu_grain_stream_guarded64_spatial_f32_v1"
        GpuRenderOperation.FILM_DIR_STREAM_V1 -> "gpu_dir_stream_f32_v1"
        GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1 -> "gpu_exposure_log_dir_stream_f32_v1"
        GpuRenderOperation.SCAN_TAIL_STREAM_V1 -> "gpu_scan_tail_stream_f32_v1"
        GpuRenderOperation.FINISH_TAIL_STREAM_V1 -> "gpu_finish_tail_stream_f32_v1"
        else -> error("Not a streaming operation")
    }
    /** JSONObject may overwrite duplicate keys on Android. Reject them before decoding. */
    private fun strictObject(raw: String, cap: Int = GpuRenderProtocol.MAX_JSON): JSONObject {
        require(raw.length <= cap && raw.toByteArray(Charsets.UTF_8).size <= cap)
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            fun objectValue(depth: Int) {
                require(depth <= 2)
                val seen = mutableSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    require(seen.add(reader.nextName()))
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> objectValue(depth + 1)
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> error("Invalid streaming field type")
                    }
                }
                reader.endObject()
            }
            objectValue(1)
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return JSONObject(raw)
    }
    fun parsePlan(raw: String, token: Long, operation: GpuRenderOperation): GpuStreamingPlan {
        require(operation in operations)
        strictObject(raw)
        val extra = when (operation) {
            GpuRenderOperation.HIGHLIGHT_STREAM_V1 -> highlightKeys
            GpuRenderOperation.FILM_OPTICS_STREAM_V1 -> setOf("batchBytes")
            GpuRenderOperation.FILM_GRAIN_STREAM_V1 -> grainKeys
            GpuRenderOperation.FILM_DIR_STREAM_V1, GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1 -> dirKeys
            else -> finishKeys
        }
        val p = GpuScienceFields.plan(raw, token, operation, "planGpuStreamingStage", extra + setOf("layout", "passCount"))
        require(p.get("layout") == layout(operation))
        val details = when (operation) {
            GpuRenderOperation.HIGHLIGHT_STREAM_V1 -> GpuStreamingDetails.Highlight(
                p.long("batchBytes", BATCH, BATCH), p.long("pipelineCount", 5, 5).toInt(),
                p.long("residentDeviceBytes", 1, GpuRenderProtocol.HARD_CAP), p.long("uploadStagingBytes", BATCH, BATCH),
                p.long("sourceSnapshotBytes", 1, MAX_DISK), p.long("outputBytes", 1, MAX_DISK), p.long("metadataDiskBytes", 2 * MIB, 2 * MIB))
            GpuRenderOperation.FILM_OPTICS_STREAM_V1 -> GpuStreamingDetails.Optics(p.long("batchBytes", BATCH, BATCH))
            GpuRenderOperation.FILM_GRAIN_STREAM_V1 -> {
                require(p.get("rngMode") == GpuGrainRngMode.COORDINATE.wire && p.get("distributionArithmetic") == GpuFilmGrainProtocol.DISTRIBUTION)
                GpuStreamingDetails.Grain(p.bool("grainActive"), p.bool("sublayers"), p.long("layerCount", 0, 64).toInt(),
                    p.long("chunkPixels", 16384, 16384).toInt(), p.long("chunkCount", 1, 1_600_000_000),
                    p.bool("microActive"), p.bool("finalBlurActive"), p.bool("amountBlendActive"))
            }
            GpuRenderOperation.FILM_DIR_STREAM_V1, GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1 ->
                GpuStreamingDetails.Dir(p.bool("dirActive"), p.long("curveKnots", 2, 4096).toInt(),
                    p.long("preCurveKnots", 2, 4096).toInt(), p.long("chunkPixels", 65536, 65536).toInt(),
                    p.long("chunkCount", 1, 1_600_000_000), p.long("pipelineCount", 2, 6).toInt())
            else -> GpuStreamingDetails.Finish(GpuStreamingFinishMode.fromWire(p.get("mode")),
                p.long("tableScalars", 0, 0xffffffffL - 64), p.bool("externalGlare"), p.bool("glareActive"), p.long("sampleCount"))
        }
        return GpuStreamingPlan(operation, token, p.get("manifestSha256") as String,
            p.long("width", 1, 40000).toInt(), p.long("height", 1, 40000).toInt(), layout(operation), p.long("passCount", 0, 30), details,
            p.long("deviceBytes", 1, GpuRenderProtocol.HARD_CAP), p.long("hostBytes", 1, GpuRenderProtocol.HARD_CAP),
            p.long("driverAllowanceBytes", 1, GpuRenderProtocol.HARD_CAP), p.long("helperAllowanceBytes", 1, GpuRenderProtocol.HARD_CAP),
            p.long("combinedPeakBytes", 1, GpuRenderProtocol.HARD_CAP), p.long("diskBytes", 1, MAX_DISK)).also(::validatePlan)
    }
    fun pointSamples(pixels: Long, chunk: Long): Long {
        require(pixels > 0 && chunk > 0)
        val count = (pixels + chunk - 1) / chunk
        return 8 * (count - 1) + minOf(8L, pixels - (count - 1) * chunk)
    }
    fun expectedSamples(p: GpuStreamingPlan): Long {
        if (p.details is GpuStreamingDetails.Highlight) return Math.addExact(1L,
            Math.multiplyExact(3L, pointSamples(Math.multiplyExact(p.width.toLong(), p.height.toLong()), 65536)))
        val spatial = p.passCount * (p.width.toLong() + p.height)
        val n = p.width.toLong() * p.height
        return spatial + when (val d = p.details) {
            is GpuStreamingDetails.Dir -> (if (d.active) 6 else 3) * pointSamples(n, 65536)
            is GpuStreamingDetails.Finish -> (if (d.mode == GpuStreamingFinishMode.STANDALONE) 3 else 6) * pointSamples(n, 32768)
            else -> 0
        }
    }
    fun validatePlan(p: GpuStreamingPlan) {
        require(p.operation in operations && p.layout == layout(p.operation) && p.token > 0)
        require(p.width in 1..40000 && p.height in 1..40000 && p.digest.matches(Regex("[0-9a-f]{64}")))
        val n = Math.multiplyExact(p.width.toLong(), p.height.toLong())
        val device: Long; val host: Long; val driver: Long; val disk: Long
        when (val d = p.details) {
            is GpuStreamingDetails.Highlight -> {
                require(p.operation == GpuRenderOperation.HIGHLIGHT_STREAM_V1 && p.passCount == 2L)
                require(d.batchBytes == BATCH && d.pipelineCount == 5 && d.uploadStagingBytes == BATCH)
                val resident = Math.addExact(Math.multiplyExact(7L, BATCH), 65536L)
                val planeSet = Math.multiplyExact(24L, n)
                require(d.residentDeviceBytes == resident && d.sourceSnapshotBytes == planeSet && d.outputBytes == planeSet)
                require(d.metadataDiskBytes == 2 * MIB)
                device = Math.addExact(resident, BATCH); host = 64 * MIB; driver = (64 + 8L * d.pipelineCount) * MIB
                disk = Math.addExact(Math.addExact(planeSet, planeSet), d.metadataDiskBytes)
            }
            is GpuStreamingDetails.Optics -> {
                require(p.operation == GpuRenderOperation.FILM_OPTICS_STREAM_V1 && p.passCount in 0..30 && d.batchBytes == BATCH)
                // op7 can stage three complete input planes before its submit.
                device = 40 * MIB + 65536; host = 64 * MIB; driver = 104 * MIB; disk = 68 * n + 65536
            }
            is GpuStreamingDetails.Grain -> {
                require(p.operation == GpuRenderOperation.FILM_GRAIN_STREAM_V1 && p.passCount in 0..15)
                require(d.chunkPixels == 16384 && d.chunkCount == (n + 16383) / 16384)
                require(!d.microActive || (d.active && d.sublayers))
                require(!d.finalBlurActive || (d.active && p.passCount >= 3))
                require(!d.amountBlendActive || d.active)
                val finalPasses = if (d.finalBlurActive) 3L else 0L
                require(if (d.sublayers) p.passCount in finalPasses..(3L * d.layerCount + (if (d.microActive) 3 else 0) + finalPasses)
                    else !d.microActive && p.passCount == finalPasses)
                require(if (!d.active) d.layerCount == 0 && p.passCount == 0L && !d.microActive && !d.finalBlurActive && !d.amountBlendActive
                    else if (d.sublayers) d.layerCount == 3 else d.layerCount in 1..64)
                device = 32 * MIB + 65536 + 4800 + 544L * 16384; host = 64 * MIB + 9600
                driver = 128 * MIB; disk = 68 * n + 65536 + 2 * MIB
            }
            is GpuStreamingDetails.Dir -> {
                val log = p.operation == GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1
                require(log || p.operation == GpuRenderOperation.FILM_DIR_STREAM_V1)
                require(p.passCount in setOf(0L, 12L) && (d.active || p.passCount == 0L))
                require(d.curveKnots in 2..4096 && d.preCurveKnots in 2..4096)
                require(d.chunkPixels == 65536 && d.chunkCount == (n + 65535) / 65536)
                val count = d.pipelineCount - if (log) 1 else 0
                require(if (p.passCount == 0L) count == 2 else count in 3..5)
                device = 32 * MIB + 65536; host = (if (log) 65 else 64) * MIB
                driver = (64 + 8L * d.pipelineCount) * MIB; disk = 68 * n + 2 * MIB
            }
            is GpuStreamingDetails.Finish -> {
                val fused = p.operation == GpuRenderOperation.FINISH_TAIL_STREAM_V1
                require(fused || p.operation == GpuRenderOperation.SCAN_TAIL_STREAM_V1)
                require(p.passCount in 0..7 && d.tableScalars in 0..(0xffffffffL - 64))
                require(if (fused) d.mode != GpuStreamingFinishMode.STANDALONE && d.tableScalars > 0 && !d.externalGlare
                    else d.mode == GpuStreamingFinishMode.STANDALONE && d.tableScalars == 0L)
                require(!d.externalGlare || d.glareActive)
                val tableBytes = if (fused) Math.multiplyExact(4L, Math.addExact(d.tableScalars, 64L)) else 0L
                device = Math.addExact(32 * MIB + 65536, Math.multiplyExact(2L, tableBytes))
                host = Math.addExact(64 * MIB, Math.addExact(Math.multiplyExact(16L, d.tableScalars), if (fused) 8 * MIB + 256 else 0L))
                driver = (if (d.glareActive || p.passCount > 0) 360 else 104) * MIB
                disk = Math.addExact((48 + if (d.externalGlare) 8 else 0) * n, Math.addExact(8 * d.tableScalars, 3 * MIB))
                require(d.sampleCount == expectedSamples(p))
            }
        }
        require(p.deviceBytes == device && p.hostBytes == host && p.driverAllowanceBytes == driver && p.helperAllowanceBytes == 64 * MIB)
        val combined = Math.addExact(Math.addExact(device, host), Math.addExact(driver, 64 * MIB))
        require(p.combinedPeakBytes == combined && combined <= GpuRenderProtocol.HARD_CAP && p.diskBytes == disk && disk in 1..MAX_DISK)
    }

    private val summaryBase = setOf("schemaVersion", "operation", "token", "manifestSha256", "width", "height", "channels",
        "gpuUsed", "fullImage", "fullResolution", "layout", "backend", "deviceBytes", "combinedPeakBytes", "passCount",
        "sampleCount", "sampleMaxAbs", "sampleRms", "elapsedMs", "vendor", "uploadBytes", "readbackBytes", "dispatches",
        "nativeStageSealed", "contextCount", "sampleToleranceAbsolute", "sampleToleranceRelative", "sampleScope")
    private val dirSummaryKeys = setOf("dirActive", "chunkCount", "pointwiseToleranceAbsolute", "pointwiseToleranceRelative")
    private val logSummaryKeys = setOf("logSampleCount", "logSampleMaxAbs", "logSampleRms")
    private val finishSummaryKeys = setOf("prefixSampleCount", "glareGenerationGpu", "glareRandomWords", "glareChunks", "glarePredicateScope", "glareAmbiguityBound")
    private val censusKeys = setOf("poissonCountMismatches", "binomialCountMismatches", "randomWordCountMismatches", "samplerTupleMismatches")
    private val grainSummaryKeys = grainKeys - "chunkPixels" + censusKeys + setOf("samplerSampleCount", "exactRngWordsChecked",
        "softwareArithmeticChecks", "numericFlagReads", "samplerMaxAbs", "samplerRms", "stochasticScope", "microstructureArithmetic")
    private fun summaryKeys(p: GpuStreamingPlan): Set<String> = summaryBase + when (p.details) {
        is GpuStreamingDetails.Highlight -> setOf("highlightPeak", "passes", "chunkCapacitySamples")
        is GpuStreamingDetails.Optics -> emptySet()
        is GpuStreamingDetails.Grain -> grainSummaryKeys
        is GpuStreamingDetails.Dir -> dirSummaryKeys + if (p.operation == GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1) logSummaryKeys else emptySet()
        is GpuStreamingDetails.Finish -> finishSummaryKeys
    }
    private fun sampleScope(p: GpuStreamingPlan): String = when (p.details) {
        is GpuStreamingDetails.Highlight -> HIGHLIGHT_SCOPE
        is GpuStreamingDetails.Optics, is GpuStreamingDetails.Grain -> GpuFilmOpticsProtocol.SAMPLE_SCOPE
        is GpuStreamingDetails.Dir -> GpuFilmDirProtocol.SAMPLE_SCOPE
        is GpuStreamingDetails.Finish -> "full_line_f64_tail_f32_and_optional_prefix_f64_witnesses"
    }
    fun parseNativeResult(raw: String, p: GpuStreamingPlan): Pair<GpuRenderCode, GpuScienceSummary?> {
        validatePlan(p)
        val v = strictObject(raw)
        if (!v.bool("ok")) {
            v.exact(setOf("ok", "schemaVersion", "code"))
            require(v.long("schemaVersion") == 1L)
            return nativeFailure(p.operation, v.get("code")) to null
        }
        v.exact(summaryKeys(p) + "ok")
        return GpuRenderCode.OK to parseSummary(v, p)
    }
    private fun parseSummary(v: JSONObject, p: GpuStreamingPlan): GpuScienceSummary {
        require(v.long("schemaVersion") == 1L && v.get("operation") == p.operation.wire)
        require(v.long("token", 1) == p.token && v.get("manifestSha256") == p.digest)
        require(v.get("layout") == p.layout && v.get("backend") == backend(p.operation))
        require(v.long("width") == p.width.toLong() && v.long("height") == p.height.toLong() && v.long("channels") == 3L)
        require(v.bool("gpuUsed") && v.bool("fullImage") && v.bool("fullResolution"))
        // This is graph evidence only. The authenticated session controller obtains its own native seal.
        require(!v.bool("nativeStageSealed") && v.long("contextCount") == 1L)
        require(v.long("deviceBytes") == p.deviceBytes && v.long("combinedPeakBytes") == p.combinedPeakBytes)
        require(v.long("passCount") == p.passCount && v.long("sampleCount") == expectedSamples(p))
        require(v.get("sampleScope") == sampleScope(p))
        val tolerance = if (p.details is GpuStreamingDetails.Highlight) 0.002 else 0.001
        require(v.number("sampleToleranceAbsolute") == tolerance && v.number("sampleToleranceRelative") == tolerance)
        v.number("sampleMaxAbs"); v.number("sampleRms"); v.number("elapsedMs", max = 125000.0)
        v.long("vendor", 0, 0xffffffffL)
        val n = p.width.toLong() * p.height
        // Dimension-derived upper bounds admit repeated bounded reads, not arbitrary unchecked counters.
        val layers = (p.details as? GpuStreamingDetails.Grain)?.layerCount?.toLong() ?: 0L
        val byteLimit = 1024L * n * (layers + 64L) + 64 * MIB
        val dispatchLimit = 10000L + ((n + 16383) / 16384) * (256L * layers + 256L) + 4 * p.passCount * (p.width.toLong() + p.height)
        v.long("uploadBytes", 0, byteLimit); v.long("readbackBytes", 0, byteLimit)
        v.long("dispatches", 1, dispatchLimit)
        when (val d = p.details) {
            is GpuStreamingDetails.Highlight -> {
                require(v.long("passes") == 2L && v.long("chunkCapacitySamples") == HIGHLIGHT_CHUNK)
                v.number("highlightPeak", -1e15, 1e15)
                // Both passes use the full 4 MiB batch; the last 64 Ki-sample IO chunk alone may be short.
                val batches = Math.addExact(n, HIGHLIGHT_CHUNK - 1) / HIGHLIGHT_CHUNK
                val ioChunks = Math.addExact(n, 65535L) / 65536L
                val dispatches = Math.addExact(4L, Math.addExact(Math.multiplyExact(4L, batches), Math.multiplyExact(9L, ioChunks)))
                val upload = Math.addExact(Math.multiplyExact(24L, n), Math.multiplyExact(160L, dispatches))
                val readback = Math.addExact(Math.multiplyExact(12L, n), Math.addExact(80L, Math.multiplyExact(4L, batches)))
                require(v.long("dispatches") == dispatches && v.long("uploadBytes") == upload && v.long("readbackBytes") == readback)
            }
            is GpuStreamingDetails.Optics -> Unit
            is GpuStreamingDetails.Dir -> {
                require(v.bool("dirActive") == d.active && v.long("chunkCount") == d.chunkCount)
                require(v.number("pointwiseToleranceAbsolute") == 2e-5 && v.number("pointwiseToleranceRelative") == 2e-5)
                if (p.operation == GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1) {
                    require(v.long("logSampleCount") == (if (d.active) 12 else 9) * pointSamples(n, 65536))
                    v.number("logSampleMaxAbs"); v.number("logSampleRms")
                }
            }
            is GpuStreamingDetails.Finish -> {
                val generated = d.glareActive && !d.externalGlare
                require(v.bool("glareGenerationGpu") == generated)
                val chunks = v.long("glareChunks", if (generated) (n + 16383) / 16384 else 0, if (generated) n else 0)
                v.long("glareRandomWords", if (generated) n else 0, if (generated) 95 * n else 0)
                require(v.get("glarePredicateScope") == when { d.externalGlare -> "external_original_field"; generated -> "chacha12_exact_fast_guarded_q48"; else -> "disabled" })
                require(v.number("glareAmbiguityBound") == if (chunks > 0) 1.0 / 4294967296.0 else 0.0)
                val expectedPrefixSamples: Long = if (d.mode == GpuStreamingFinishMode.STANDALONE) 0L else 3L * pointSamples(n, 32768)
                require(v.long("prefixSampleCount") == expectedPrefixSamples)
            }
            is GpuStreamingDetails.Grain -> {
                require(v.bool("grainActive") == d.active && v.bool("sublayers") == d.sublayers && v.long("layerCount") == d.layerCount.toLong())
                require(v.long("chunkCount") == d.chunkCount && v.get("rngMode") == GpuGrainRngMode.COORDINATE.wire)
                require(v.get("distributionArithmetic") == GpuFilmGrainProtocol.DISTRIBUTION)
                require(v.bool("microActive") == d.microActive && v.bool("finalBlurActive") == d.finalBlurActive && v.bool("amountBlendActive") == d.amountBlendActive)
                require(v.get("stochasticScope") == GpuFilmGrainProtocol.STOCHASTIC_SCOPE && v.get("microstructureArithmetic") == GpuFilmGrainProtocol.MICROSTRUCTURE)
                v.long("samplerSampleCount", 3280, 3280); v.long("exactRngWordsChecked", 98304, 98304); v.long("softwareArithmeticChecks", 1280, 1280)
                for (key in censusKeys) v.long(key, 0, 0)
                v.long("numericFlagReads", 1, dispatchLimit)
                v.number("samplerMaxAbs", max = 1e-4); v.number("samplerRms", max = 1e-4)
            }
        }
        return GpuScienceSummary.validated(summaryKeys(p).associateWith { v.get(it) })
    }
    fun parseResult(raw: String, p: GpuStreamingPlan): Pair<GpuRenderCode, GpuScienceSummary?> {
        validatePlan(p)
        val v = strictObject(raw, GpuRenderProtocol.MAX_IPC)
        require(v.long("schemaVersion") == 1L)
        val code = GpuRenderCode.valueOf(v.get("code") as? String ?: error("Invalid code"))
        v.exact(if (code == GpuRenderCode.OK) setOf("schemaVersion", "code", "summary") else setOf("schemaVersion", "code"))
        if (code != GpuRenderCode.OK) return code to null
        val summary = v.getJSONObject("summary"); summary.exact(summaryKeys(p))
        return code to parseSummary(summary, p)
    }
    fun nativeFailure(operation: GpuRenderOperation, code: Any?): GpuRenderCode {
        require(operation in operations)
        if (code == "CANCELLED") return GpuRenderCode.CANCELLED
        if (operation == GpuRenderOperation.HIGHLIGHT_STREAM_V1) when (code) {
            "GPU_GLOBAL_STREAM_PENDING" -> return GpuRenderCode.UNSUPPORTED
            "GPU_GLOBAL_STREAM_MEMORY_LIMIT", "INSUFFICIENT_GPU_GLOBAL_STREAM_MEMORY" -> return GpuRenderCode.MEMORY_UNAVAILABLE
            "GPU_GLOBAL_STREAM_REPORT", "INVALID_GLOBAL_STREAM_PLAN", "INVALID_GLOBAL_STREAM_CHUNK", "INVALID_GLOBAL_STREAM_OWNER",
            "INVALID_GLOBAL_STREAM_SOURCE_IDENTITY", "INVALID_GLOBAL_STREAM_MANIFEST", "INVALID_GLOBAL_STREAM_IDENTITY",
            "INVALID_GLOBAL_STREAM_SOURCE_DIGEST", "INVALID_GLOBAL_STREAM_SOURCE_RANGE", "INVALID_GLOBAL_STREAM_SOURCE",
            "INVALID_GLOBAL_STREAM_OUTPUT" -> return GpuRenderCode.INVALID_RESULT
            "GPU_OPTICS_TIMEOUT" -> return GpuRenderCode.TIMED_OUT
            "GPU_OPTICS_MEMORY_LIMIT" -> return GpuRenderCode.MEMORY_UNAVAILABLE
        }
        val resident = when (operation) {
            GpuRenderOperation.HIGHLIGHT_STREAM_V1 -> GpuRenderOperation.HIGHLIGHT_BOOST
            GpuRenderOperation.FILM_OPTICS_STREAM_V1 -> GpuRenderOperation.FILM_OPTICS
            GpuRenderOperation.FILM_GRAIN_STREAM_V1 -> GpuRenderOperation.FILM_GRAIN
            GpuRenderOperation.FILM_DIR_STREAM_V1 -> GpuRenderOperation.FILM_DIR
            GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1 -> GpuRenderOperation.EXPOSURE_LOG_DIR
            GpuRenderOperation.SCAN_TAIL_STREAM_V1 -> GpuRenderOperation.SCAN_TAIL
            GpuRenderOperation.FINISH_TAIL_STREAM_V1 -> GpuRenderOperation.FINISH_TAIL
            else -> error("Not a streaming operation")
        }
        return when (code) {
            "GPU_STREAM_NOT_ADMITTED", "GPU_STREAM_UNSUPPORTED", "GPU_STREAM_LINE_LIMIT",
            "GPU_OPTICS_STREAM_PENDING", "GPU_GRAIN_STREAM_PENDING", "GPU_DIR_STREAM_PENDING", "GPU_FINISH_STREAM_PENDING" -> GpuRenderCode.UNSUPPORTED
            "GPU_STREAM_MEMORY_LIMIT", "GPU_DIR_STREAM_MEMORY_LIMIT", "GPU_FINISH_STREAM_MEMORY_LIMIT" -> GpuRenderCode.MEMORY_UNAVAILABLE
            "GPU_STREAM_TIMEOUT" -> GpuRenderCode.TIMED_OUT
            "GPU_STREAM_NUMERIC_CHECK", "NONFINITE_STREAM_INPUT", "NONFINITE_STREAM_OUTPUT" -> GpuRenderCode.NUMERIC_REJECTED
            else -> GpuRenderProtocol.nativeFailure(resident, code)
        }
    }
}
