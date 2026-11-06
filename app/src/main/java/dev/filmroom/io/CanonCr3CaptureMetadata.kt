package dev.filmroom.io

import dev.filmroom.io.formats.PhotoHeaders
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read only allowlisted capture fields from Canon's independent CMT TIFFs.
 * Never follows TIFF links or copies MakerNotes, thumbnails, sensor data or source geometry.
 * This is separate from native RAW admission and cannot authorize image decoding. */
internal object CanonCr3CaptureMetadata {
    data class Field(val type:Int,val count:Int,val bytes:ByteArray,val order:ByteOrder)
    data class Result(val fields:Map<Pair<Int,Int>,Field>,val xmp:ByteArray?)
    private val descriptor=byteArrayOf(0x85.toByte(),0xc0.toByte(),0xb6.toByte(),0x87.toByte(),0x82.toByte(),0x0f,0x11,0xe0.toByte(),0x81.toByte(),0x11,0xf4.toByte(),0xce.toByte(),0x46,0x2b,0x6a,0x48)
    private val xpacket=byteArrayOf(0xbe.toByte(),0x7a,0xcf.toByte(),0xcb.toByte(),0x97.toByte(),0xa9.toByte(),0x42,0xe8.toByte(),0x9c.toByte(),0x71,0x99.toByte(),0x94.toByte(),0x91.toByte(),0xe3.toByte(),0xaf.toByte(),0xac.toByte())
    private data class Box(val type:String,val start:Long,val end:Long,val uuid:ByteArray?)
    fun read(file:File,wanted:Set<Pair<Int,Int>>):Result?=RandomAccessFile(file,"r").use { input ->
        val length=input.length()
        fun bytes(at:Long,n:Int,begin:Long=0,end:Long=length):ByteArray {
            require(n in 0..128*1024 && at>=begin && at<=end-n)
            return ByteArray(n).also { input.seek(at);input.readFully(it) }
        }
        if(length<12 || !PhotoHeaders.isCanonCr3(bytes(0,12)))return@use null
        require(length<=512L*1024*1024)
        fun u16(b:ByteArray,p:Int,o:ByteOrder)=ByteBuffer.wrap(b).order(o).getShort(p).toInt() and 65535
        fun u32(b:ByteArray,p:Int,o:ByteOrder)=ByteBuffer.wrap(b).order(o).getInt(p).toLong() and 0xffffffffL
        var boxCount=0
        fun boxes(begin:Long,end:Long):List<Box> {
            var at=begin;val result=mutableListOf<Box>()
            while(at<end) {
                require(++boxCount<=512)
                val h=bytes(at,8,begin,end);var size=u32(h,0,ByteOrder.BIG_ENDIAN);var header=8
                if(size==1L) { size=ByteBuffer.wrap(bytes(at+8,8,begin,end)).long;header=16 }
                require(size>=header && size<=end-at)
                val type=h.copyOfRange(4,8).toString(Charsets.US_ASCII)
                val uuid=if(type=="uuid")bytes(at+header,16,at,at+size) else null
                val start=at+header+if(uuid!=null)16 else 0
                result+=Box(type,start,at+size,uuid);at+=size
            }
            return result
        }
        val top=boxes(0,length)
        require(top.count{it.type=="ftyp"}==1 && top.count{it.type=="moov"}==1)
        val moov=top.single{it.type=="moov"}
        val desc=boxes(moov.start,moov.end).single{it.uuid?.contentEquals(descriptor)==true}
        val cmt=boxes(desc.start,desc.end)
        val fields=linkedMapOf<Pair<Int,Int>,Field>();var total=0
        for((group,type) in listOf(0 to "CMT1",1 to "CMT2",2 to "CMT4")) {
            val box=cmt.single{it.type==type};require(box.end-box.start in 8..1024*1024)
            val h=bytes(box.start,8,box.start,box.end)
            val order=when {h[0]==73.toByte() && h[1]==73.toByte()->ByteOrder.LITTLE_ENDIAN;h[0]==77.toByte() && h[1]==77.toByte()->ByteOrder.BIG_ENDIAN;else->error("TIFF byte order")}
            require(u16(h,2,order)==42)
            val offset=u32(h,4,order);require(offset>=8)
            val at=box.start+offset;val count=u16(bytes(at,2,box.start,box.end),0,order);require(count<=512)
            val table=bytes(at+2,count*12+4,box.start,box.end);val seen=mutableSetOf<Int>()
            for(i in 0 until count) {
                val p=i*12;val tag=u16(table,p,order);require(seen.add(tag))
                if((group to tag) !in wanted)continue
                val t=u16(table,p+2,order);val n=u32(table,p+4,order)
                val unit=when(t){1,2,7->1;3->2;4->4;5,10->8;else->error("Capture field type")}
                require(n in 1..8192 && n*unit<=8192)
                val size=(n*unit).toInt();total+=size;require(total<=48*1024)
                val data=if(size<=4)table.copyOfRange(p+8,p+8+size)else bytes(box.start+u32(table,p+8,order),size,box.start,box.end)
                fields[group to tag]=Field(t,n.toInt(),data,order)
            }
        }
        val packets=top.filter{it.uuid?.contentEquals(xpacket)==true};require(packets.size<=1)
        val xmp=packets.singleOrNull()?.takeIf{it.end-it.start<=128*1024}?.let{bytes(it.start,(it.end-it.start).toInt())}
        Result(fields,xmp)
    }
}
