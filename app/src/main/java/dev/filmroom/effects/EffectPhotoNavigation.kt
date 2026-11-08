package dev.filmroom.effects

import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import kotlin.math.abs

/** Navigation is scoped to completed records in the same order as the export list. */
internal object EffectPhotoNavigation {
    fun neighbor(jobs:List<ExportJob>, currentId:String?, direction:Int):ExportJob? {
        if(currentId==null || direction !in listOf(-1,1))return null
        val photos=jobs.filter{it.state==ExportJobState.COMPLETED && !it.mediaUri.isNullOrBlank()}.sortedByDescending{it.createdAt}
        val index=photos.indexOfFirst{it.id==currentId}
        return if(index<0)null else photos.getOrNull(index+direction)
    }
    fun swipeDirection(dx:Float,dy:Float,minimumDistance:Float):Int = when {
        abs(dx)<minimumDistance || abs(dx)<=abs(dy)*1.5f -> 0
        dx<0 -> 1
        else -> -1
    }
}

/** An idle preview is the completed photo, never a hidden automatic replay. */
internal class EffectPreviewPlayback(autoPlay:Boolean) {
    var stopped=!autoPlay
        private set
    fun elapsed(now:Long,start:Long,duration:Long):Long = if(stopped)duration else (now-start).coerceIn(0,duration)
    fun rendered(time:Long,duration:Long){if(time>=duration)stopped=true}
    fun replay(){stopped=false}
    fun stop(){stopped=true}
}
