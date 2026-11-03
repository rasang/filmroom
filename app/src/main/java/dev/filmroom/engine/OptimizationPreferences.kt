package dev.filmroom.engine

import android.content.Context
import org.json.JSONObject

/** Private test-build controls. Exact flags are execution-only; experimental GPU mode is separately included in native checkpoint identity. */
data class OptimizationSnapshot(val exportMask:Int=0,val cpuComparison:Boolean=false,val previewBrightnessReuse:Boolean=false,val gpuResourceReuse:Boolean=false,val gpuDiffusionExperimental:Boolean=false,val gpuScannerExperimental:Boolean=false,val gpuOpticalExperimental:Boolean=false,val gpuExposureExperimental:Boolean=false,val gpuDirExperimental:Boolean=false,val gpuScienceExperimental:Boolean=false,val gpuRenderingEnabled:Boolean=false) {
    init { require(exportMask in 0..127) }
    fun allScientificStagesEnabled()=gpuDiffusionExperimental && gpuScannerExperimental && gpuOpticalExperimental && gpuExposureExperimental && gpuDirExperimental && gpuScienceExperimental
    fun withGpuRendering(enabled:Boolean)=copy(gpuRenderingEnabled=enabled,cpuComparison=if(enabled)false else cpuComparison,
        gpuDiffusionExperimental=enabled,gpuScannerExperimental=enabled,gpuOpticalExperimental=enabled,gpuExposureExperimental=enabled,gpuDirExperimental=enabled,gpuScienceExperimental=enabled)
    fun experimentalDiffusionEnabled(perfBuild:Boolean,mode:String,capturingCpuReference:Boolean=false):Boolean =
        perfBuild && mode in setOf("export","preview") && gpuDiffusionExperimental && !cpuComparison && !capturingCpuReference
    fun experimentalScannerEnabled(perfBuild:Boolean,mode:String,capturingCpuReference:Boolean=false):Boolean =
        perfBuild && mode in setOf("export","preview") && gpuScannerExperimental && !cpuComparison && !capturingCpuReference
    fun experimentalOpticalEnabled(perfBuild:Boolean,mode:String,capturingCpuReference:Boolean=false):Boolean =
        perfBuild && mode in setOf("export","preview") && gpuOpticalExperimental && !cpuComparison && !capturingCpuReference
    fun experimentalExposureEnabled(perfBuild:Boolean,mode:String,capturingCpuReference:Boolean=false):Boolean =
        perfBuild && mode in setOf("export","preview") && gpuExposureExperimental && !cpuComparison && !capturingCpuReference
    fun experimentalDirEnabled(perfBuild:Boolean,mode:String,capturingCpuReference:Boolean=false):Boolean =
        perfBuild && mode in setOf("export","preview") && gpuDirExperimental && !cpuComparison && !capturingCpuReference
    fun experimentalScienceEnabled(perfBuild:Boolean,mode:String,capturingCpuReference:Boolean=false):Boolean =
        perfBuild && mode in setOf("export","preview") && gpuScienceExperimental && !cpuComparison && !capturingCpuReference
    fun applyTo(parameters:JSONObject,perfBuild:Boolean=dev.filmroom.BuildConfig.PERF_BUILD):JSONObject = parameters.apply {
        put("exportOptimizationMask",exportMask)
        put("previewBrightnessReuse",previewBrightnessReuse)
        put("gpuResourceReuse",gpuResourceReuse && !cpuComparison)
        if(cpuComparison || perfBuild)put("useGpu",false)
        if(perfBuild)put("gpuResourceReuse",false)
    }
}
class OptimizationPreferences(context:Context) {
    private val store=context.applicationContext.getSharedPreferences("exact-optimization-test",Context.MODE_PRIVATE)
    fun snapshot():OptimizationSnapshot = synchronized(mutationLock) {
        val values=store.all // one immutable preference read, followed by one atomic normalization
        val mask=(values["exportMask"] as? Int)?.takeIf{it in 0..127} ?: 0
        val legacy=OptimizationSnapshot(mask,values["cpuComparison"] as? Boolean ?: false,values["previewBrightnessReuse"] as? Boolean ?: false,values["gpuResourceReuse"] as? Boolean ?: false,values["gpuDiffusionExperimental"] as? Boolean ?: false,values["gpuScannerExperimental"] as? Boolean ?: false,values["gpuOpticalExperimental"] as? Boolean ?: false,values["gpuExposureExperimental"] as? Boolean ?: false,values["gpuDirExperimental"] as? Boolean ?: false,values["gpuScienceExperimental"] as? Boolean ?: false)
        if(!dev.filmroom.BuildConfig.PERF_BUILD)return@synchronized legacy
        // A partially enabled old experiment never silently upgrades to the full pipeline.
        val enabled=(if(values.containsKey("gpuRenderingEnabled"))values["gpuRenderingEnabled"]==true else legacy.allScientificStagesEnabled()) && !legacy.cpuComparison
        val normalized=legacy.withGpuRendering(enabled)
        if(values["gpuRenderingEnabled"]!=enabled || gpuKeys.any{values[it]!=enabled})write(normalized)
        normalized
    }
    fun setGpuRendering(enabled:Boolean)=synchronized(mutationLock){
        if(dev.filmroom.BuildConfig.PERF_BUILD)write(snapshot().withGpuRendering(enabled))
    }
    fun observeChoices(onChange:()->Unit):AutoCloseable {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key in setOf("gpuRenderingEnabled","exportMask","cpuComparison","previewBrightnessReuse","gpuResourceReuse","gpuDiffusionExperimental","gpuScannerExperimental","gpuOpticalExperimental","gpuExposureExperimental","gpuDirExperimental","gpuScienceExperimental"))onChange()}
        store.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable{store.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    /** Internal compatibility entry; user-facing changes use the single master toggle. */
    fun save(value:OptimizationSnapshot)=synchronized(mutationLock){
        write(if(dev.filmroom.BuildConfig.PERF_BUILD)value.withGpuRendering((value.gpuRenderingEnabled || value.allScientificStagesEnabled()) && !value.cpuComparison) else value)
    }
    private fun write(value:OptimizationSnapshot) {
        val edit=store.edit().putInt("exportMask",value.exportMask).putBoolean("cpuComparison",value.cpuComparison).putBoolean("previewBrightnessReuse",value.previewBrightnessReuse).putBoolean("gpuResourceReuse",value.gpuResourceReuse)
        for((key,enabled) in listOf("gpuDiffusionExperimental" to value.gpuDiffusionExperimental,"gpuScannerExperimental" to value.gpuScannerExperimental,"gpuOpticalExperimental" to value.gpuOpticalExperimental,"gpuExposureExperimental" to value.gpuExposureExperimental,"gpuDirExperimental" to value.gpuDirExperimental,"gpuScienceExperimental" to value.gpuScienceExperimental))edit.putBoolean(key,enabled)
        if(dev.filmroom.BuildConfig.PERF_BUILD)edit.putBoolean("gpuRenderingEnabled",value.gpuRenderingEnabled)
        edit.apply()
    }
    private companion object {
        val mutationLock=Any()
        val gpuKeys=listOf("gpuDiffusionExperimental","gpuScannerExperimental","gpuOpticalExperimental","gpuExposureExperimental","gpuDirExperimental","gpuScienceExperimental")
    }
    fun record(operation:String,selection:OptimizationSnapshot,result:JSONObject,wallMs:Double) {
        // Privacy-safe counters only. No filename, source samples, metadata or parameter contents.
        val timing=JSONObject().put("operation",operation).put("exportOptimizationMask",selection.exportMask)
            .put("gpuResourceReuse",selection.gpuResourceReuse).put("cpuComparison",selection.cpuComparison).put("wallMs",wallMs)
            .put("previewBrightnessReuse",selection.previewBrightnessReuse).put("previewReuseHit",result.optBoolean("previewReuseHit")).put("previewCacheBytes",result.optLong("previewCacheBytes"))
            .put("nativeMs",result.optDouble("elapsedMs",0.0)).put("ok",result.optBoolean("ok"))
        val metrics=result.optJSONObject("metrics") ?: result
        val safeGpuFields=dev.filmroom.diagnostics.PerformanceInfo.fromNative(result,dev.filmroom.diagnostics.PerformanceInfo()).typedFields()
        val fullGpu=safeGpuFields.filterKeys{it.startsWith("gpuDiffusion")}
        if(fullGpu.isNotEmpty())timing.put("gpuDiffusion",JSONObject(fullGpu))
        val scannerGpu=safeGpuFields.filterKeys{it.startsWith("gpuScanner")}
        if(scannerGpu.isNotEmpty())timing.put("gpuScanner",JSONObject(scannerGpu))
        val opticalGpu=safeGpuFields.filterKeys{it.startsWith("gpuOptical")}
        if(opticalGpu.isNotEmpty())timing.put("gpuOptical",JSONObject(opticalGpu))
        val exposureGpu=safeGpuFields.filterKeys{it.startsWith("gpuExposure")}
        if(exposureGpu.isNotEmpty())timing.put("gpuExposure",JSONObject(exposureGpu))
        val dirGpu=safeGpuFields.filterKeys{it.startsWith("gpuDir")}
        if(dirGpu.isNotEmpty())timing.put("gpuDir",JSONObject(dirGpu))
        val scienceGpu=safeGpuFields.filterKeys{key->dev.filmroom.diagnostics.ScienceStage.entries.any{it.fieldKey==key}}
        if(scienceGpu.isNotEmpty())timing.put("gpuScience",JSONObject(scienceGpu))
        fun number(from:JSONObject,to:JSONObject,key:String){val value=from.optDouble(key,Double.NaN);if(value.isFinite() && value>=0)to.put(key,value)}
        for(key in listOf("checkpointCommitMs","checkpointIdentityMs","checkpointResumeStage","setupMs","wallElapsedMs"))number(metrics,timing,key)
        metrics.optJSONObject("gpuProbeCapture")?.let{capture->number(capture,timing,"captureMs")}
        val allowedStages=setOf("film_exposure","highlight_boost","camera_diffusion","camera_blur","film_scatter_halation","film_density_dir","grain","print_diffusion","scan_glare","printing_scanning","scan_sharpen","scan_blur")
        val stages=org.json.JSONArray()
        metrics.optJSONArray("stages")?.let{source->for(i in 0 until minOf(source.length(),32)){
            val stage=source.optJSONObject(i) ?: continue
            val name=stage.optString("stage")
            if(name in allowedStages){val item=JSONObject().put("stage",name);number(stage,item,"elapsedMs");stages.put(item)}
        }}
        timing.put("stages",stages)
        metrics.optJSONObject("exportExecution")?.let{execution->
            val saved=JSONObject()
            for(key in listOf("workerCap","activePoolWorkers","parallelParticleChunks","serialParticleChunks","interactiveSerialChunks","interactiveWaits","interactiveWaitMs","admissionWaitMs"))number(execution,saved,key)
            timing.put("exportExecution",saved)
        }
        (metrics.optJSONObject("gpu") ?: result.optJSONObject("gpu"))?.let{gpu->
            val saved=JSONObject()
            for(key in listOf("resourceReuseBatches","resourceReuseExactChecks","gpuBatches","cpuFallbacks"))number(gpu,saved,key)
            val reason=gpu.optString("resourceReuseReason")
            val safeReasons=setOf("Requested; no reused GPU batches committed","Disabled by configuration","Allocation reuse used; exact baseline GPU comparison passed","Allocation reuse exact comparison failed; original GPU allocation path retained")
            saved.put("resourceReuseReason",if(reason in safeReasons)reason else "Unavailable")
            timing.put("gpu",saved)
        }
        store.edit().putString("lastTiming",timing.toString()).putString("${operation}Timing",timing.toString()).apply()
    }
    fun lastTiming():String=listOf("preview","detail","export").mapNotNull{operation->
        (store.all["${operation}Timing"] as? String)?.let{raw->runCatching{
            val value=JSONObject(raw)
            fun ms(key:String)=String.format(java.util.Locale.ROOT,"%.1f",value.optDouble(key,0.0))
            buildString{
                append(when(operation){"preview"->"预览 / Preview";"detail"->"缩放细节 / Zoom detail";else->"导出 / Export"})
                append(" · mask=${value.optInt("exportOptimizationMask")} · CPU=${value.optBoolean("cpuComparison")}\n")
                append("Wall ${ms("wallMs")} ms · native ${ms("nativeMs")} ms")
                if(value.has("captureMs"))append("\nDiagnostic capture ${ms("captureMs")} ms (excluded from native CPU timing)")
                if(operation=="preview")append("\nCache hit=${value.optBoolean("previewReuseHit")} · ${value.optLong("previewCacheBytes")/1024} KiB")
                if(value.has("checkpointResumeStage"))append("\nResume stage=${value.optInt("checkpointResumeStage")} · identity ${ms("checkpointIdentityMs")} ms · commit ${ms("checkpointCommitMs")} ms")
                value.optJSONObject("exportExecution")?.let{append("\nWorkers=${it.optInt("activePoolWorkers")}/${it.optInt("workerCap")}")}
                value.optJSONArray("stages")?.let{stages->for(i in 0 until stages.length()){val stage=stages.getJSONObject(i);append("\n${stage.getString("stage")}: ${String.format(java.util.Locale.ROOT,"%.1f",stage.optDouble("elapsedMs"))} ms")}}
                value.optJSONObject("gpu")?.let{append("\nDensity-curve GPU reused=${it.optLong("resourceReuseBatches")}, exact checks=${it.optLong("resourceReuseExactChecks")}\n${it.optString("resourceReuseReason")}")}
                value.optJSONObject("gpuScience")?.let{science->
                    append("\n科学计算实际后端 / Actual scientific stage backends:")
                    for(stage in dev.filmroom.diagnostics.ScienceStage.entries) {
                        val raw=science.opt(stage.fieldKey) as? String ?: continue
                        val status=dev.filmroom.diagnostics.SciencePerformanceInfo.decode(stage,raw) ?: continue
                        append("\n${stage.label}: ${status.backend(stage)}")
                        status.fallback?.takeIf{it!=dev.filmroom.diagnostics.FullGpuFallback.NONE}?.let{append(" · ${it.id}")}
                        if(status.resumed==true)append(" · resumed checkpoint")
                        status.prepare?.let{append(" · ${it.id}")}
                        status.numbers[dev.filmroom.diagnostics.ScienceNumber.STAGE_MS]?.let{append(" · ${String.format(java.util.Locale.ROOT,"%.1f",it)} ms")}
                    }
                }
                value.optJSONObject("gpuExposure")?.let{g->
                    append("\n胶片曝光实际后端: ${g.optString("gpuExposureBackend","unknown")}")
                    if(g.has("gpuExposureFallback"))append(" · ${g.optString("gpuExposureFallback")}")
                    if(g.has("gpuExposurePrepareErrorCode"))append("\n${g.optString("gpuExposurePrepareErrorCode")}")
                    if(g.optBoolean("gpuExposureResumed"))append(" · resumed checkpoint")
                    for(key in listOf("gpuExposureHelperTotalMs","gpuExposureStageMs","gpuExposureSetupMs"))if(g.has(key))append("\n$key: ${String.format(java.util.Locale.ROOT,"%.1f",g.optDouble(key))} ms")
                }
                value.optJSONObject("gpuDir")?.let{g->
                    append("\n胶片显影/DIR实际后端: ${g.optString("gpuDirBackend","unknown")}")
                    if(g.has("gpuDirFallback"))append(" · ${g.optString("gpuDirFallback")}")
                    if(g.has("gpuDirPrepareErrorCode"))append("\n${g.optString("gpuDirPrepareErrorCode")}")
                    if(g.optBoolean("gpuDirResumed"))append(" · resumed checkpoint")
                    for(key in listOf("gpuDirHelperTotalMs","gpuDirStageMs","gpuDirSetupMs"))if(g.has(key))append("\n$key: ${String.format(java.util.Locale.ROOT,"%.1f",g.optDouble(key))} ms")
                }
                value.optJSONObject("gpuOptical")?.let{g->
                    append("\n镜头模糊/散射/光晕实际后端: ${g.optString("gpuOpticalBackend","unknown")}")
                    if(g.has("gpuOpticalFallback"))append(" · ${g.optString("gpuOpticalFallback")}")
                    if(g.has("gpuOpticalPrepareErrorCode"))append("\n${g.optString("gpuOpticalPrepareErrorCode")}")
                    if(g.optBoolean("gpuOpticalResumed"))append(" · resumed checkpoint")
                    for(key in listOf("gpuOpticalHelperTotalMs","gpuOpticalStageMs","gpuOpticalSetupMs"))if(g.has(key))append("\n$key: ${String.format(java.util.Locale.ROOT,"%.1f",g.optDouble(key))} ms")
                }
                value.optJSONObject("gpuScanner")?.let{g->
                    append("\n打印扫描实际后端 / Actual print-scan backend: ${g.optString("gpuScannerBackend","unknown")}")
                    if(g.optString("gpuScannerFallback")=="included_in_resident")append(" · included in resident print/scanner tail")
                    else if(g.optString("gpuScannerBackend")=="gpu_pchip_f32_v1")append(" · GPU PCHIP chain + CPU color/spatial finish")
                    if(g.has("gpuScannerFallback"))append(" · ${g.optString("gpuScannerFallback")}")
                    if(g.has("gpuScannerPrepareErrorCode"))append("\n${g.optString("gpuScannerPrepareErrorCode")}")
                    if(g.optBoolean("gpuScannerResumed"))append(" · resumed checkpoint")
                    for(key in listOf("gpuScannerHelperTotalMs","gpuScannerStageMs","gpuScannerSetupMs"))if(g.has(key))append("\n$key: ${String.format(java.util.Locale.ROOT,"%.1f",g.optDouble(key))} ms")
                }
                value.optJSONObject("gpuDiffusion")?.let{g->
                    append("\n镜头柔光实际后端 / Actual diffusion backend: ${g.optString("gpuDiffusionBackend","unknown")}")
                    if(g.has("gpuDiffusionFallback"))append(" · ${g.optString("gpuDiffusionFallback")}")
                    if(g.has("gpuDiffusionPrepareErrorCode"))append("\n${g.optString("gpuDiffusionPrepareErrorCode")}")
                    if(g.optBoolean("gpuDiffusionResumed"))append(" · resumed checkpoint")
                    for(key in listOf("gpuDiffusionHelperTotalMs","gpuDiffusionStageMs","gpuDiffusionSetupMs"))if(g.has(key))append("\n$key: ${String.format(java.util.Locale.ROOT,"%.1f",g.getDouble(key))} ms")
                    if(g.has("gpuDiffusionTiles"))append("\nTiles=${g.getDouble("gpuDiffusionTiles").toInt()}")
                }
            }
        }.getOrNull()}
    }.joinToString("\n\n").ifEmpty{"尚无测试记录 / No test recorded"}
}

/** Native GPU worker is process-wide. Its matching passive lease is deliberately process-wide too. */
internal object GpuReuseAdmission {
    private var lease:AutoCloseable?=null
    @Synchronized fun admit(context:Context):Boolean {
        if(lease!=null)return true
        return try {lease=NativeWorkReservations.retain(2L*1024*1024,{NativeWorkReservations.snapshot(context)});true}
        catch(_:NativeWorkUnavailable){false}
    }
}

/** Isolate the experimental camera stage: density interpolation stays on its CPU baseline. */
internal fun applyFullGpuDiffusionPolicy(parameters:JSONObject,requested:Boolean) {
    if(requested)parameters.put("gpuDiffusionExperimental",true).put("useGpu",false).put("gpuResourceReuse",false)
    else parameters.remove("gpuDiffusionExperimental")
}
