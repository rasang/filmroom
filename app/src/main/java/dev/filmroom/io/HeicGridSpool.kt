package dev.filmroom.io

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Writes each complete original tile once. No full-size Bitmap or float array is created.
 * The caller exclusively owns a newly-created target and removes it on any failure.
 * Grid geometry is validated before construction; no missing/failed tile can be published.
 */
internal object HeicGridSpool {
    suspend fun write(plan:HeicGridInput.Plan,backend:HeicBackend,target:File,job:CoroutineContext,
        beforeTile:(Long)->Unit = {}) {
        check(target.isFile && target.length()==0L)
        val info=plan.info
        val expected=LinearRgb.byteCount(info.displayWidth,info.displayHeight)
        var written=0L
        RandomAccessFile(target,"rw").use { output ->
            output.setLength(expected)
            repeat(plan.tileCount) { index ->
                job.ensureActive();beforeTile(written)
                plan.materialize(index) {job.ensureActive()}.use { tile ->
                    val cw=tile.validWidth;val ch=tile.validHeight
                    check(cw>0 && ch>0 && cw.toLong()*ch<=HeicGridInput.MAX_TILE_PIXELS)
                    val rotation=info.rotationDegreesClockwise
                    val dw=if(rotation%180==0)cw else ch
                    val dh=if(rotation%180==0)ch else cw
                    val dx=when(rotation){0->tile.x;90->info.encodedHeight-tile.y-ch;180->info.encodedWidth-tile.x-cw;270->tile.y;else->error("Invalid admitted rotation")}
                    val dy=when(rotation){0->tile.y;90->tile.x;180->info.encodedHeight-tile.y-ch;270->info.encodedWidth-tile.x-cw;else->error("Invalid admitted rotation")}
                    check(dx>=0 && dy>=0 && dx.toLong()+dw<=info.displayWidth && dy.toLong()+dh<=info.displayHeight)
                    val buffer=ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(dw,dh),12)).order(ByteOrder.LITTLE_ENDIAN)
                    job[dev.filmroom.diagnostics.ImportObservation]?.memoryStage=dev.filmroom.diagnostics.ImportMemoryStage.HEIC_PLATFORM
                    backend.decode(tile.file,tile.info,job).use { pixels ->
                        if(pixels.width!=tile.info.displayWidth || pixels.height!=tile.info.displayHeight)
                            throw IOException("HEIF tile decoder changed dimensions")
                        val colors=IntArray(pixels.width)
                        repeat(ch) { y ->
                            job.ensureActive();pixels.readRow(y,colors)
                            repeat(cw) { x ->
                                val tx=when(rotation){0->x;90->ch-1-y;180->cw-1-x;else->y}
                                val ty=when(rotation){0->y;90->x;180->ch-1-y;else->cw-1-x}
                                buffer.position((ty*dw+tx)*12)
                                val color=colors[x];val alpha=color ushr 24
                                for(channel in 2 downTo 0) {
                                    val value=(((color ushr (channel*8)) and 255)*alpha+255*(255-alpha)+127)/255
                                    buffer.putFloat(LinearRgb.srgb8ToLinear[value])
                                }
                            }
                        }
                    }
                    // Release this tile's platform Bitmap before writing/decoding another tile.
                    job[dev.filmroom.diagnostics.ImportObservation]?.memoryStage=dev.filmroom.diagnostics.ImportMemoryStage.HEIC_SPOOL
                    val bytes=buffer.array();val rowBytes=dw*12
                    repeat(dh) { y ->
                        job.ensureActive()
                        val offset=((dy+y).toLong()*info.displayWidth+dx)*12
                        check(offset>=0 && offset+rowBytes<=expected)
                        output.seek(offset);output.write(bytes,y*rowBytes,rowBytes);written+=rowBytes
                    }
                }
            }
            job.ensureActive()
            check(written==expected)
            output.fd.sync()
        }
        if(target.length()!=expected)throw IOException("Incomplete HEIF grid source")
    }
}
