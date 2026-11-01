package dev.filmroom.ui

import dev.filmroom.roi.DetailFailure
import dev.filmroom.roi.DetailPhase
import dev.filmroom.roi.DetailStatus

/** Main-confined presentation policy. Never exposes exception messages or source keys. */
internal class DetailFeedback(savedKeys:List<String> = emptyList()) {
    private val seen=LinkedHashSet<String>().apply{addAll(savedKeys.takeLast(64))}
    fun savedKeys():ArrayList<String> = ArrayList(seen)
    fun showLoading(status:DetailStatus,sourceKey:String?,enabled:Boolean,foreground:Boolean):Boolean =
        enabled && foreground && sourceKey!=null && status.phase in setOf(DetailPhase.Preparing,DetailPhase.Refining)
    fun takeFailureToast(status:DetailStatus,sourceKey:String?,enabled:Boolean,foreground:Boolean):String? {
        if(!enabled || !foreground || sourceKey==null || status.phase!=DetailPhase.Fallback)return null
        val reason=status.failure ?: DetailFailure.RenderFailed
        if(!seen.add("$sourceKey\u0000${reason.name}"))return null
        while(seen.size>64)seen.remove(seen.first())
        return when(reason){
            DetailFailure.Unsupported->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_detail_unsupported)
            DetailFailure.MemoryBudget->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_detail_memory)
            DetailFailure.SourceRead,DetailFailure.SourceValidation->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_detail_read)
            else->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_detail_fallback)
        }
    }
}
