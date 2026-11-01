package dev.filmroom.io

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/** Container-only edits: image samples/entropy and ICC are never decoded or rewritten. */
object PhotoMetadataContainer {
    private const val MAX_EXIF = 60 * 1024 // fits one JPEG APP1, also bounds recursive TIFF work
    private val pngMagic = byteArrayOf(-119,80,78,71,13,10,26,10)
    fun inject(file: File, format: ExportFormat, tiffExif: ByteArray, exportMillis: Long? = null) {
        require(tiffExif.size in 14..MAX_EXIF) { "Metadata size limit" }
        val metadata = Tiff(tiffExif)
        metadata.validate()
        val temp = File.createTempFile("metadata-", ".part", file.parentFile)
        try {
            when (format) {
                ExportFormat.JPEG -> jpeg(file, temp, tiffExif)
                ExportFormat.PNG -> png(file, temp, tiffExif, exportMillis)
                ExportFormat.TIFF16 -> tiff(file, temp, metadata)
            }
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun jpeg(source:File, target:File, exif:ByteArray) {
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            DataOutputStream(BufferedOutputStream(target.outputStream())).use { out ->
                require(input.readUnsignedShort()==0xffd8) { "Invalid JPEG" };out.writeShort(0xffd8)
                out.writeShort(0xffe1);out.writeShort(exif.size+8);out.write(byteArrayOf(69,120,105,102,0,0));out.write(exif)
                while(true) {
                    require(input.readUnsignedByte()==255) { "Invalid JPEG marker" }
                    var marker=input.readUnsignedByte()
                    while(marker==255)marker=input.readUnsignedByte()
                    require(marker!=0 && marker!=0xd8 && marker !in 0xd0..0xd7) { "Invalid JPEG segment" }
                    if(marker==0xd9) {out.writeShort(0xffd9);require(input.read()==-1);break}
                    if(marker==1){out.writeShort(0xff01);continue}
                    val length=input.readUnsignedShort();require(length>=2)
                    val data=ByteArray(length-2);input.readFully(data)
                    val isExif=marker==0xe1 && data.size>=6 && data.copyOfRange(0,6).contentEquals(byteArrayOf(69,120,105,102,0,0))
                    if(!isExif){out.writeShort(0xff00 or marker);out.writeShort(length);out.write(data)}
                    if(marker==0xda){input.copyTo(out);break} // untouched entropy-coded data, including restart markers
                }
            }
        }
    }
    private fun png(source:File,target:File,exif:ByteArray,exportMillis:Long?) {
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            DataOutputStream(BufferedOutputStream(target.outputStream())).use { out ->
                val magic=ByteArray(8);input.readFully(magic);require(magic.contentEquals(pngMagic));out.write(magic)
                var first=true;var inserted=false;val buffer=ByteArray(64*1024)
                while(true) {
                    val length=input.readInt();require(length>=0 && length.toLong()<=source.length())
                    val type=ByteArray(4);input.readFully(type);val name=String(type,Charsets.US_ASCII)
                    require(!first || name=="IHDR" && length==13);first=false
                    if(name=="IDAT" && !inserted){writeChunk(out,"eXIf",exif);exportMillis?.let{writeChunk(out,"tIME",pngTime(it))};inserted=true}
                    val keep=name!="eXIf" && !(name=="tIME" && exportMillis!=null);if(keep){out.writeInt(length);out.write(type)}
                    val crc=CRC32();crc.update(type);var left=length
                    while(left>0){val n=minOf(left,buffer.size);input.readFully(buffer,0,n);crc.update(buffer,0,n);if(keep)out.write(buffer,0,n);left-=n}
                    val checksum=input.readInt();require(checksum==crc.value.toInt()){ "Invalid PNG CRC" };if(keep)out.writeInt(checksum)
                    if(name=="IEND"){require(length==0 && inserted && input.read()==-1);break}
                }
            }
        }
    }
    private fun pngTime(millis:Long):ByteArray {
        val t=java.time.Instant.ofEpochMilli(millis).atOffset(java.time.ZoneOffset.UTC)
        return byteArrayOf((t.year shr 8).toByte(),t.year.toByte(),t.monthValue.toByte(),t.dayOfMonth.toByte(),t.hour.toByte(),t.minute.toByte(),t.second.toByte())
    }
    private fun writeChunk(out:DataOutputStream,name:String,data:ByteArray){
        val type=name.toByteArray(Charsets.US_ASCII);out.writeInt(data.size);out.write(type);out.write(data)
        val crc=CRC32();crc.update(type);crc.update(data);out.writeInt(crc.value.toInt())
    }
    private fun tiff(source:File,target:File,metadata:Tiff) {
        // Preserve all original bytes and their offsets. Append relocated metadata and a new root IFD.
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        RandomAccessFile(target,"rw").use { out ->
            require(out.length() in 8..0xffffffffL)
            val header=ByteArray(8);out.readFully(header);require(header[0]==73.toByte() && header[1]==73.toByte() && u16(header,2)==42){"Only classic little-endian TIFF output is supported"}
            val originalOffset=u32(header,4);require(originalOffset+2<=out.length());out.seek(originalOffset)
            val count=readU16(out);require(count in 1..512 && originalOffset+2+count*12L+4<=out.length())
            val entries=linkedMapOf<Int,ByteArray>()
            repeat(count){val entry=ByteArray(12);out.readFully(entry);val tag=u16(entry,0);require(!entries.containsKey(tag));entries[tag]=entry}
            require(readU32(out)==0L){"Multiple TIFF images unsupported"}
            val append=(out.length()+1) and -2L
            val relocated=metadata.relocate(append)
            metadata.entries(metadata.root).forEach { offset ->
                val tag=u16(metadata.bytes,offset)
                // Original TIFF image layout/color interpretation always wins. EXIF structural pointers
                // are freshly relocated, never copied from an input camera's container.
                if(tag !in structural)entries[tag]=relocated.copyOfRange(offset,offset+12)
            }
            val rootOffset=append+relocated.size+(relocated.size and 1)
            require(rootOffset+2+entries.size*12L+4<=0xffffffffL)
            out.seek(append);out.write(relocated);out.seek(rootOffset);writeU16(out,entries.size)
            entries.toSortedMap().values.forEach { out.write(it) };writeU32(out,0)
            out.seek(4);writeU32(out,rootOffset)
        }
    }
    private val structural=setOf(254,255,256,257,258,259,262,266,273,277,278,279,284,288,289,292,293,317,320,322,323,324,325,330,338,339,347,513,514,529,530,531,532,34675)
    private class Tiff(val bytes:ByteArray) {
        val root:Int
        init { require(bytes.size>=8 && bytes[0]==73.toByte() && bytes[1]==73.toByte() && u16(bytes,2)==42);root=offset(u32(bytes,4)) }
        private fun offset(n:Long):Int {require(n>=8 && n<bytes.size);return n.toInt()}
        fun entries(ifd:Int):List<Int>{require(ifd+2<=bytes.size);val count=u16(bytes,ifd);require(count<=256 && ifd+2+count*12L+4<=bytes.size);require(u32(bytes,ifd+2+count*12)==0L);return (0 until count).map{ifd+2+it*12}}
        fun validate(){walk(null,0)}
        fun relocate(base:Long):ByteArray=bytes.copyOf().also { walk(it,base) }
        private fun walk(result:ByteArray?,base:Long){
            val seen=mutableSetOf<Int>()
            fun visit(ifd:Int,depth:Int){
                require(depth<=2 && seen.add(ifd));val tags=mutableSetOf<Int>()
                for(e in entries(ifd)){
                    val tag=u16(bytes,e);require(tags.add(tag));val type=u16(bytes,e+2);val unit=when(type){1,2,6,7->1;3,8->2;4,9,11->4;5,10,12->8;else->error("Unsupported EXIF type")}
                    val size=u32(bytes,e+4)*unit;require(size<=MAX_EXIF)
                    if(tag==34665 || tag==34853 || tag==40965){require(type==4 && u32(bytes,e+4)==1L);val ptr=offset(u32(bytes,e+8));visit(ptr,depth+1);if(result!=null)put32(result,e+8,ptr+base)}
                    else if(size>4){val ptr=offset(u32(bytes,e+8));require(ptr+size<=bytes.size);if(result!=null)put32(result,e+8,ptr+base)}
                }
            }
            visit(root,0)
        }
    }
    private fun u16(b:ByteArray,p:Int)=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    private fun u32(b:ByteArray,p:Int)=(0..3).fold(0L){v,i->v or ((b[p+i].toLong() and 255) shl (8*i))}
    private fun put32(b:ByteArray,p:Int,v:Long){require(v in 0..0xffffffffL);repeat(4){b[p+it]=(v shr (8*it)).toByte()}}
    private fun readU16(f:RandomAccessFile)=f.readUnsignedByte() or (f.readUnsignedByte() shl 8)
    private fun readU32(f:RandomAccessFile)=(0..3).fold(0L){v,i->v or (f.readUnsignedByte().toLong() shl (8*i))}
    private fun writeU16(f:RandomAccessFile,v:Int){f.write(v and 255);f.write(v shr 8)}
    private fun writeU32(f:RandomAccessFile,v:Long){repeat(4){f.write((v shr (8*it)).toInt() and 255)}}
}
