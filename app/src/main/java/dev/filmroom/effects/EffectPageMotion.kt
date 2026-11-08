package dev.filmroom.effects

import kotlin.math.abs

/** Bounded one-neighbour paging: no full-resolution preload and no empty-page travel. */
internal object EffectPageMotion {
    fun dragOffset(distance:Float,width:Float,hasNeighbour:Boolean):Float {
        val bounded=distance.coerceIn(-width.coerceAtLeast(1f),width.coerceAtLeast(1f))
        return if(hasNeighbour)bounded else (bounded*0.16f).coerceIn(-32f,32f)
    }
    /** Includes input + output + crop + GL headroom for both sides. Low-memory skips speculation. */
    fun prefetchEdge(availableBytes:Long):Int=when {
        availableBytes>=96L*1024*1024->960
        availableBytes>=48L*1024*1024->640
        availableBytes>=24L*1024*1024->384
        else->0
    }
    fun shouldCommit(distance:Float,width:Float,minimum:Float)=abs(distance)>=maxOf(minimum,width*0.22f)
    /** Reserve space for both decoded inputs and one crop, in addition to the current page and GL textures. */
    fun decodeEdge(availableBytes:Long):Int=when {
        availableBytes>=80L*1024*1024->1200
        availableBytes>=40L*1024*1024->960
        else->640
    }
}
