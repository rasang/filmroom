package dev.filmroom.diagnostics

import dev.filmroom.engine.GpuProbeSummary
import dev.filmroom.engine.GpuProbeStatus

/** Share-safe flat schema. Correlation tokens, hashes, paths and driver prose are never included. */
data class GpuProbeTelemetry(val status:GpuProbeStatus,val numbers:Map<String,Double> = emptyMap(),val flags:Map<String,Boolean> = emptyMap()) {
    internal fun typedFields():Map<String,Any> = linkedMapOf<String,Any>("gpuProbeStatus" to status.name).apply {
        numbers.forEach{(k,v)->if(k in limits && v.isFinite() && v in 0.0..limits.getValue(k))put(k,v)}
        flags.forEach{(k,v)->if(k in flagNames)put(k,v)}
    }
    companion object {
        private val limits=mapOf(
            "gpuProbeOriginalWidth" to 40000.0,"gpuProbeOriginalHeight" to 40000.0,"gpuProbeFftSide" to 2048.0,"gpuProbeRadius" to 1023.0,
            "gpuProbeRoiWidth" to 256.0,"gpuProbeRoiHeight" to 256.0,"gpuProbeCombinedPeakBytes" to 335544320.0,
            "gpuProbePreparationMs" to 604800000.0,"gpuProbeInitializationMs" to 604800000.0,"gpuProbeComparisonMs" to 604800000.0,
            "gpuProbeTotalMs" to 604800000.0,"gpuProbeStageTotalMs" to 604800000.0,
            "gpuProbeMaxAbs" to 1e300,"gpuProbeRms" to 1e300,"gpuProbeReferencePeak" to 1e300,"gpuProbeMaxAbsOverPeak" to 1e300,"gpuProbeChangedSamples" to 65536.0)
        private val flagNames=setOf("gpuProbeBitExact","gpuProbeF64Advertised","gpuProbeDiagnosticOnly","gpuProbeOutputReplaced","gpuProbeFullRenderMeasured")
        fun fromSummary(summary:GpuProbeSummary):GpuProbeTelemetry {
            val m=summary.measurements
            val numbers=if(m==null)emptyMap()else mapOf(
                "gpuProbeOriginalWidth" to m.originalWidth.toDouble(),"gpuProbeOriginalHeight" to m.originalHeight.toDouble(),"gpuProbeFftSide" to m.fftSide.toDouble(),"gpuProbeRadius" to m.radius.toDouble(),
                "gpuProbeRoiWidth" to m.roiWidth.toDouble(),"gpuProbeRoiHeight" to m.roiHeight.toDouble(),"gpuProbeCombinedPeakBytes" to m.combinedPeakBytes.toDouble(),
                "gpuProbePreparationMs" to m.preparationMs,"gpuProbeInitializationMs" to m.initializationMs,"gpuProbeComparisonMs" to m.comparisonMs,"gpuProbeTotalMs" to m.totalMs,"gpuProbeStageTotalMs" to m.stageTotalMs,
                "gpuProbeMaxAbs" to m.maxAbs,"gpuProbeRms" to m.rms,"gpuProbeReferencePeak" to m.referencePeak,"gpuProbeMaxAbsOverPeak" to m.maxAbsOverPeak,"gpuProbeChangedSamples" to m.changedSamples.toDouble())
            return GpuProbeTelemetry(summary.status,numbers,buildMap{put("gpuProbeDiagnosticOnly",true);put("gpuProbeOutputReplaced",false);put("gpuProbeFullRenderMeasured",false);m?.let{put("gpuProbeBitExact",it.bitExact);put("gpuProbeF64Advertised",it.f64Advertised)}})
        }
        fun fromFields(fields:Map<String,String>):GpuProbeTelemetry? {
            val status=fields["gpuProbeStatus"]?.let{runCatching{GpuProbeStatus.valueOf(it)}.getOrNull()} ?: return null
            return GpuProbeTelemetry(status,limits.mapNotNull{(k,max)->fields[k]?.toDoubleOrNull()?.takeIf{it.isFinite()&&it in 0.0..max}?.let{k to it}}.toMap(),flagNames.mapNotNull{k->fields[k]?.toBooleanStrictOrNull()?.let{k to it}}.toMap())
        }
    }
}
