package dev.filmroom.border

/** Bounded float32 tiles for a rotated scanline. Never materializes or quantizes the source image. */
internal class FinalImageTileCache(private val width:Int,private val height:Int,private val read:(Int,Int,Int,Int,FloatArray)->Unit) {
    companion object {
        const val EDGE=32
        private const val CAPACITY=1152
        // Includes float payloads, array/map bookkeeping, and one load scratch row.
        const val MAX_BYTES=16L*1024*1024
        private fun capacity(w:Int,h:Int)=minOf(CAPACITY,2*((w+EDGE-1)/EDGE+(h+EDGE-1)/EDGE+4))
        fun workingBytes(w:Int,h:Int)=minOf(MAX_BYTES,capacity(w,h)*(EDGE*EDGE*12L+128L)+64L*1024)
    }
    private val capacity=capacity(width,height)
    private val tiles=object:LinkedHashMap<Long,FloatArray>(capacity,0.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Long,FloatArray>?)=size>capacity
    }
    private var lastKey=-1L
    private var last:FloatArray?=null
    private fun tile(x:Int,y:Int):FloatArray {
        val tx=x/EDGE;val ty=y/EDGE;val key=(ty.toLong() shl 32) or tx.toLong()
        if(lastKey==key)return last!!
        val result=tiles[key] ?: FloatArray(EDGE*EDGE*3).also{
            read(tx*EDGE,ty*EDGE,minOf(EDGE,width-tx*EDGE),minOf(EDGE,height-ty*EDGE),it)
            tiles[key]=it
        }
        lastKey=key;last=result;return result
    }
    fun sample(x:Double,y:Double,out:FloatArray,at:Int) {
        val x0=x.toInt();val y0=y.toInt();val x1=minOf(x0+1,width-1);val y1=minOf(y0+1,height-1)
        val fx=x-x0;val fy=y-y0
        val a=tile(x0,y0);val b=tile(x1,y0);val c=tile(x0,y1);val d=tile(x1,y1)
        val ai=((y0%EDGE)*EDGE+x0%EDGE)*3;val bi=((y0%EDGE)*EDGE+x1%EDGE)*3
        val ci=((y1%EDGE)*EDGE+x0%EDGE)*3;val di=((y1%EDGE)*EDGE+x1%EDGE)*3
        for(k in 0..2){
            val top=a[ai+k]*(1-fx)+b[bi+k]*fx;val bottom=c[ci+k]*(1-fx)+d[di+k]*fx
            out[at+k]=(top*(1-fy)+bottom*fy).toFloat()
        }
    }
}
