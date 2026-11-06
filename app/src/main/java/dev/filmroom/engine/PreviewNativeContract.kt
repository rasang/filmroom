package dev.filmroom.engine

import org.json.JSONObject

/** ABI negotiation is explicit. A stale library is unavailable, never a cosmetic CPU substitute. */
internal object PreviewNativeContract {
    fun supportsStageCache(capabilities:JSONObject):Boolean =
        capabilities.opt("previewStageCacheAbi")==3 &&
            capabilities.opt("previewStageCandidateBytesPerPixel").let { it is Number && (it is Int || it is Long) && it.toLong()==48L }
    fun supportsRefinedBitmap(capabilities:JSONObject):Boolean = supportsBitmap(capabilities) &&
        capabilities.opt("refinedPreviewAbi")==1 &&
        capabilities.opt("refinedPreviewMaxEdge")==NativeMemoryPolicy.REFINED_PREVIEW_EDGE
    fun supportsBitmap(capabilities:JSONObject):Boolean {
        val revision=when(val raw=capabilities.opt("nativeAbiRevision")){is Int->raw.toLong();is Long->raw;else->return false}
        return revision>=2 && capabilities.opt("bitmapPreview")==true
    }
    /** Capability negotiation alone is not proof that the requested bounded path ran. */
    fun refinedOutputMatches(result:JSONObject,width:Int,height:Int,budgetMiB:Int,externalBytes:Long):Boolean = runCatching {
        if(!outputMatches(result,width,height) || width !in 1..NativeMemoryPolicy.REFINED_PREVIEW_EDGE ||
            height !in 1..NativeMemoryPolicy.REFINED_PREVIEW_EDGE || budgetMiB !in 128..512 || externalBytes<0)return@runCatching false
        fun count(source:JSONObject,key:String):Long?=when(val value=source.opt(key)){is Int->value.toLong();is Long->value;else->null}?.takeIf{it>=0}
        val plan=result.optJSONObject("refinedPlan") ?: return@runCatching false
        val gpu=result.optJSONObject("gpu") ?: return@runCatching false
        val coreWidth=count(plan,"coreWidth") ?: return@runCatching false
        val coreHeight=count(plan,"coreHeight") ?: return@runCatching false
        val halo=count(plan,"halo") ?: return@runCatching false
        val expandedWidth=count(plan,"expandedWidth") ?: return@runCatching false
        val expandedHeight=count(plan,"expandedHeight") ?: return@runCatching false
        if(coreWidth !in 1L..width.toLong() || coreHeight !in 1L..height.toLong() || halo>65536L ||
            expandedWidth!=minOf(width.toLong(),coreWidth+2*halo) || expandedHeight!=minOf(height.toLong(),coreHeight+2*halo))return@runCatching false
        val full=Math.multiplyExact(width.toLong()*height,60L)
        val fields=count(plan,"globalFieldReserveBytes") ?: return@runCatching false
        val estimate=Math.addExact(Math.addExact(96L*NativeMemoryPolicy.MIB,externalBytes),
            Math.addExact(Math.addExact(full,fields),Math.multiplyExact(expandedWidth*expandedHeight,256L)))
        count(plan,"abi")==1L && count(plan,"externalBytes")==externalBytes && count(plan,"fullFrameReserveBytes")==full &&
            count(plan,"estimatedPeakBytes")==estimate && estimate<=budgetMiB.toLong()*NativeMemoryPolicy.MIB &&
            count(result,"estimatedPeakNativeBytes")==estimate && count(result,"memoryBudgetMiB")==budgetMiB.toLong() &&
            count(result,"maxPixels")==NativeMemoryPolicy.REFINED_PREVIEW_EDGE.toLong()*NativeMemoryPolicy.REFINED_PREVIEW_EDGE &&
            gpu.opt("backend")=="wgpu" && gpu.opt("requested")==true && gpu.opt("active")==true &&
            gpu.opt("numericGuardPassed")==true && count(gpu,"cpuFallbacks")==0L && (count(gpu,"gpuBatches") ?: 0)>0
    }.getOrDefault(false)

    fun outputMatches(result:JSONObject,width:Int,height:Int):Boolean =
        result.opt("ok")==true && result.opt("outputValid")==true &&
            result.opt("width")==width && result.opt("height")==height &&
            result.opt("outputEncoding")=="srgb-rgba8"
}
