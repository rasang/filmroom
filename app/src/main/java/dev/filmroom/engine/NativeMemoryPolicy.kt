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
    const val QUICK_PREVIEW_EDGE=512
    /** Separate GPU-only whole-photo refinement; never changes import/export limits. */
    const val REFINED_PREVIEW_EDGE=1536
    // Preserve the existing settled-work admission gate. Native planning then
    // independently rejects any1536 geometry/effects that do not fit this budget.
    const val REFINED_MIN_BUDGET_MIB=416
    // 768² costs 384 MiB native + 15.75 MiB direct buffers/result. Keep margin.
    fun previewEdge(budgetMiB:Int,settled:Boolean=false):Int {
        require(budgetMiB in 128..512)
        return if(settled && budgetMiB>=416)DETAIL_PREVIEW_EDGE else QUICK_PREVIEW_EDGE
    }
    /** Never lower the requested quality or raise the budget. The actual aspect ratio
     * may fit even when a square 512 preview would not. Cache candidates and direct
     * buffers remain separately charged by their existing admission owners. */
    fun previewFitsBudget(width:Int,height:Int,budgetMiB:Int):Boolean {
        require(width in 1..DETAIL_PREVIEW_EDGE && height in 1..DETAIL_PREVIEW_EDGE)
        require(budgetMiB in 128..512)
        // Must match native MEMORY_FIXED_ESTIMATE_BYTES + MEMORY_ESTIMATE_BYTES_PER_PIXEL.
        return 96L*MIB+width.toLong()*height*512L<=budgetMiB.toLong()*MIB
    }
}
