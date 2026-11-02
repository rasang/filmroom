package dev.filmroom.roi

/** A session chooses its budgeted output strategy once. Panning never upgrades or flips it. */
internal class DetailOutputScheduler(provider:TileProvider,regionOutput:Boolean,onChanged:()->Unit):AutoCloseable {
    private val region=if(regionOutput)RegionScheduler(provider,onChanged=onChanged) else null
    private val cells=if(regionOutput)null else TileScheduler(provider,onChanged=onChanged)
    private var demand:PixelRect?=null
    val key get()=region?.key ?: cells?.key
    val cacheBytes get()=region?.cacheBytes ?: cells?.cacheBytes ?: 0L
    val viewportRevision get()=region?.viewportRevision ?: cells?.viewportRevision ?: 0L
    val runningCount get()=region?.runningCount ?: cells?.runningCount ?: 0
    val lastError get()=region?.lastError ?: cells?.lastError
    val lastFailure get()=region?.lastFailure ?: cells?.lastFailure
    val hasDemand get()=demand!=null
    val ready get()=region?.ready ?: (cells?.let{it.drawableTiles().size==it.admittedTiles().size && it.admittedTiles().isNotEmpty()} ?: false)
    fun update(key:RenderKey,rect:PixelRect?){
        demand=rect
        if(region!=null)region.update(key,rect) else cells!!.update(key,rect?.coveringCells(key) ?: emptyList())
    }
    fun setPaused(value:Boolean){region?.setPaused(value);cells?.setPaused(value)}
    fun drawableTiles():List<TileResult> = region?.drawableTiles() ?: cells?.drawableTiles() ?: emptyList()
    fun forEachDrawable(draw:(TileResult)->Unit){region?.forEachDrawable(draw);cells?.forEachDrawable(draw)}
    override fun close(){region?.close();cells?.close();demand=null}
}
internal fun PixelRect.coveringCells(key:RenderKey):List<TileId> {
    if(width<=0 || height<=0)return emptyList()
    val cells=mutableListOf<TileId>()
    for(y in top/256..(bottom-1)/256)for(x in left/256..(right-1)/256)cells+=TileId(x,y)
    return cells.sortedBy{val r=it.rect(key);val dx=(r.left+r.right-left-right).toLong();val dy=(r.top+r.bottom-top-bottom).toLong();dx*dx+dy*dy}
}
