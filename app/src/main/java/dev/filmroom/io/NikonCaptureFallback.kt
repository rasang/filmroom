package dev.filmroom.io

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/** Reads only three non-encrypted Nikon type-2 fields, never copies a MakerNote into output.
 * Format references: ExifTool Nikon.pm ISOInfo (0x25), Lens (0x84), and WorldTime (0x24).
 * Fixed IFD0 -> Exif -> MakerNote traversal; no next-IFD recursion or sensor allocation. */
internal object NikonCaptureFallback {
    fun read(file:File):Map<String,String> = runCatching {
        RandomAccessFile(file,"r").use { input ->
            val length=input.length()
            fun bytes(offset:Long,count:Int,begin:Long=0,end:Long=length):ByteArray {
                require(count in 0..8192 && offset>=begin && offset<=end-count)
                return ByteArray(count).also { input.seek(offset);input.readFully(it) }
            }
            fun order(header:ByteArray):ByteOrder = when {
                header[0]==73.toByte() && header[1]==73.toByte()->ByteOrder.LITTLE_ENDIAN
                header[0]==77.toByte() && header[1]==77.toByte()->ByteOrder.BIG_ENDIAN
                else->error("Not TIFF")
            }
            fun u16(b:ByteArray,p:Int,o:ByteOrder)=ByteBuffer.wrap(b).order(o).getShort(p).toInt() and 65535
            fun u32(b:ByteArray,p:Int,o:ByteOrder)=ByteBuffer.wrap(b).order(o).getInt(p).toLong() and 0xffffffffL
            data class Tag(val type:Int,val count:Long,val data:ByteArray)
            fun entries(offset:Long,o:ByteOrder,begin:Long=0,end:Long=length):Map<Int,Tag> {
                val n=u16(bytes(offset,2,begin,end),0,o);require(n<=512)
                val table=bytes(offset+2,n*12,begin,end);val result=linkedMapOf<Int,Tag>()
                for(i in 0 until n) {
                    val p=i*12;val tag=u16(table,p,o)
                    require(!result.containsKey(tag))
                    result[tag]=Tag(u16(table,p+2,o),u32(table,p+4,o),table.copyOfRange(p+8,p+12))
                }
                return result
            }
            val header=bytes(0,8);val outer=order(header);require(u16(header,2,outer)==42)
            val root=entries(u32(header,4,outer),outer)
            val pointer=root[0x8769] ?: return@use emptyMap()
            require(pointer.type==4 && pointer.count==1L)
            val exif=entries(u32(pointer.data,0,outer),outer)
            val maker=exif[0x927c] ?: return@use emptyMap()
            require(maker.type==7 && maker.count in 18..524288)
            val start=u32(maker.data,0,outer);require(start<=length-maker.count)
            val end=start+maker.count;val signature=bytes(start,18,start,end)
            require(signature.copyOfRange(0,6).contentEquals("Nikon\u0000".toByteArray(Charsets.US_ASCII)) && signature[6]==2.toByte())
            val tiff=signature.copyOfRange(10,18);val inner=order(tiff);require(u16(tiff,2,inner)==42)
            val base=start+10;val tags=entries(base+u32(tiff,4,inner),inner,base,end)
            val result=linkedMapOf<String,String>()
            tags[0x24]?.takeIf{it.type==7 && it.count==4L}?.let { tag ->
                val minutes=ByteBuffer.wrap(tag.data).order(inner).short.toInt()
                val daylight=tag.data[2].toInt() and 255
                // Nikon stores base timezone and a separate one-hour DST flag.
                // ExifTool's official time_zone.config uses exactly this composition.
                val total=minutes+if(daylight==1)60 else 0
                if(minutes in -720..840 && daylight in 0..1 && total in -720..840) {
                    result["OffsetTimeOriginal"]=java.time.ZoneOffset.ofTotalSeconds(total*60).id.let{if(it=="Z")"+00:00" else it}
                }
            }
            tags[0x25]?.takeIf{it.type==7 && it.count==14L}?.let { tag ->
                val info=bytes(base+u32(tag.data,0,inner),14,base,end)
                val raw=info[0].toInt() and 255
                // Same documented ISOInfo conversion as ExifTool; do not apply expansion twice.
                val iso=(100.0*2.0.pow(raw/12.0-5.0)).roundToInt()
                if(raw>0 && iso in 1..65534)result["PhotographicSensitivity"]=iso.toString()
            }
            tags[0x84]?.takeIf{it.type==5 && it.count==4L}?.let { tag ->
                val lens=bytes(base+u32(tag.data,0,inner),32,base,end)
                val pairs=(0..3).map{i->u32(lens,i*8,inner) to u32(lens,i*8+4,inner)}
                if(pairs.all{(n,d)->n>0 && d>0} && pairs.take(2).all{(n,d)->n.toDouble()/d<=10000} && pairs.drop(2).all{(n,d)->n.toDouble()/d<=128}) {
                    result["LensSpecification"]=pairs.joinToString(","){(n,d)->"$n/$d"}
                }
            }
            result
        }
    }.getOrDefault(emptyMap())
}
