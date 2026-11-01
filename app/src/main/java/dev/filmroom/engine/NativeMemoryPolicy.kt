package dev.filmroom.engine

/** Conservative admission estimate, NOT an allocator hard limit or device-performance promise. */
object NativeMemoryPolicy {
    const val MIB=1024L*1024
    fun chooseBudgetMiB(availableBytes:Long,thresholdBytes:Long,lowMemory:Boolean):Int? {
        if(lowMemory||availableBytes<=thresholdBytes||thresholdBytes<0)return null
        val budget=((availableBytes-thresholdBytes)/4/MIB).coerceAtMost(512)
        return if(budget>=128)budget.toInt()else null
    }
    /** Preview-only reduction. Original-size exports are NEVER silently resized. */
    const val DETAIL_PREVIEW_EDGE=768
    // 768² costs 384 MiB native + 15.75 MiB direct buffers/result. Keep margin.
    fun previewEdge(budgetMiB:Int,settled:Boolean=false):Int {
        require(budgetMiB in 128..512)
        return when { settled && budgetMiB>=416->DETAIL_PREVIEW_EDGE; budgetMiB>=224->512; else->256 }
    }
}
