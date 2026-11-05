package dev.filmroom.diagnostics

import android.util.JsonReader
import android.util.JsonToken
import dev.filmroom.engine.GpuRenderOperation
import java.io.StringReader

internal data class NativeGpuLifecycleSample(val token: Long, val operation: GpuRenderOperation?, val native: NativeGpuLifecycle)

/** Strict size, field, integer and enum validation. Raw input is never retained. */
internal object NativeGpuLifecycleParser {
    const val MAX_BYTES = 16 * 1024
    const val MAX_EVENTS = 32
    private val rootKeys = setOf("schemaVersion", "token", "stageCode", "sequence", "phaseCode", "stateCode",
        "elapsedMs", "totalElapsedMs", "terminalCode", "diagnosticCode", "activityCode", "stepCode", "failureCode", "events")
    private val eventKeys = setOf("sequence", "phaseCode", "stateCode", "elapsedMs", "totalElapsedMs", "activityCode", "stepCode")
    private val stages = listOf(null, GpuRenderOperation.CAMERA_DIFFUSION, GpuRenderOperation.AUTO_EXPOSURE,
        GpuRenderOperation.HIGHLIGHT_BOOST, GpuRenderOperation.FILM_EXPOSURE, GpuRenderOperation.FILM_OPTICS,
        GpuRenderOperation.FILM_DIR, GpuRenderOperation.EXPOSURE_LOG, GpuRenderOperation.FILM_GRAIN,
        GpuRenderOperation.PRINT_EXPOSURE, GpuRenderOperation.PRINT_DIFFUSION, GpuRenderOperation.PRINT_SCAN,
        GpuRenderOperation.SCAN_TAIL, GpuRenderOperation.FINISH_TAIL, GpuRenderOperation.EXPOSURE_LOG_DIR,
        GpuRenderOperation.FILM_OPTICS_STREAM_V1, GpuRenderOperation.FILM_GRAIN_STREAM_V1,
        GpuRenderOperation.FILM_DIR_STREAM_V1, GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1,
        GpuRenderOperation.SCAN_TAIL_STREAM_V1, GpuRenderOperation.FINISH_TAIL_STREAM_V1, GpuRenderOperation.HIGHLIGHT_STREAM_V1)
    fun parse(raw: String): NativeGpuLifecycleSample? = runCatching {
        if (raw.length > MAX_BYTES || raw.any { it.code > 127 }) return null
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            val values = LinkedHashMap<String, Long>()
            var sawEvents = false
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(key in rootKeys)
                if (key == "events") {
                    require(!sawEvents); sawEvents = true
                    var count = 0
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(++count <= MAX_EVENTS)
                        val event = LinkedHashMap<String, Long>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName(); require(name in eventKeys && name !in event)
                            event[name] = integer(reader)
                        }
                        reader.endObject(); require(event.keys == eventKeys || event.keys == eventKeys - "stepCode")
                        validatePhase(event)
                    }
                    reader.endArray()
                } else {
                    require(key !in values); values[key] = integer(reader)
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && sawEvents && values.keys.containsAll(rootKeys - setOf("events", "stepCode", "failureCode")))
            require(values.getValue("schemaVersion") == 1L && values.getValue("token") > 0)
            validatePhase(values)
            val stage = values.getValue("stageCode"); require(stage in 0L until stages.size.toLong())
            val terminal = values.getValue("terminalCode"); require(terminal in 0..4)
            val diagnostic = values.getValue("diagnosticCode"); require(diagnostic in 0..1)
            val failure = values["failureCode"] ?: 0; require(failure in 0..NativeGpuFailureLegend.MAX_CODE)
            NativeGpuLifecycleSample(values.getValue("token"), stages[stage.toInt()], NativeGpuLifecycle(
                values.getValue("sequence"), NativeGpuPhase.entries[values.getValue("phaseCode").toInt()],
                NativeGpuPhaseState.entries[values.getValue("stateCode").toInt()], values.getValue("elapsedMs"),
                values.getValue("totalElapsedMs"), NativeGpuTerminal.entries[terminal.toInt()],
                NativeGpuDiagnosticCode.entries[diagnostic.toInt()], NativeGpuActivity.entries[values.getValue("activityCode").toInt()], NativeGpuStep.entries[(values["stepCode"] ?: 0).toInt()], failure.toInt()))
        }
    }.getOrNull()
    private fun integer(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        val value = reader.nextString()
        require(value.matches(Regex("0|[1-9][0-9]{0,18}")))
        return value.toLong().also { require(it >= 0) }
    }
    private fun validatePhase(values: Map<String, Long>) {
        require(values.getValue("phaseCode") in 0..9 && values.getValue("stateCode") in 0..3)
        require(values.getValue("elapsedMs") in 0..MAX_LIFECYCLE_DURATION_MS && values.getValue("totalElapsedMs") in 0..MAX_LIFECYCLE_DURATION_MS)
        require(values.getValue("activityCode") in 0..1 && (values["stepCode"] ?: 0) in 0..5)
    }
}
