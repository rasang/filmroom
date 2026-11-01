package dev.filmroom.io

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class PhotoMetadataContainerTest {
    private fun le(n:Int)=ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun exif():ByteArray {
        // Root orientation 1 and Exif pointer; Exif date-time-original external ASCII.
        val b=le(82);b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        b.putShort(2);b.putShort(274).putShort(3).putInt(1).putInt(1)
        b.putShort(34665.toShort()).putShort(4).putInt(1).putInt(38);b.putInt(0)
        b.putShort(1);b.putShort(36867.toShort()).putShort(2).putInt(20).putInt(56);b.putInt(0)
        b.put("2001:02:03 04:05:06\u0000".toByteArray());return b.array()
    }
    private fun withFile(bytes:ByteArray,body:(File)->Unit){val f=File.createTempFile("metadata-test", ".image");try{f.writeBytes(bytes);body(f)}finally{f.delete()}}
    private fun chunk(out:DataOutputStream,name:String,data:ByteArray){val t=name.toByteArray();out.writeInt(data.size);out.write(t);out.write(data);out.writeInt(CRC32().apply{update(t);update(data)}.value.toInt())}
    private fun png():ByteArray=ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
        out.write(byteArrayOf(-119,80,78,71,13,10,26,10));chunk(out,"IHDR",ByteBuffer.allocate(13).putInt(1).putInt(1).put(byteArrayOf(8,2,0,0,0)).array())
        chunk(out,"iCCP",byteArrayOf(65,0,0,1,2,3,4));chunk(out,"eXIf",exif())
        val pixels=ByteArrayOutputStream();DeflaterOutputStream(pixels).use{it.write(byteArrayOf(0,18,52,86))};chunk(out,"IDAT",pixels.toByteArray());chunk(out,"IEND",byteArrayOf())
    }}.toByteArray()
    private fun chunks(bytes:ByteArray):List<Pair<String,ByteArray>> {val input=DataInputStream(ByteArrayInputStream(bytes));input.skipBytes(8);val result=mutableListOf<Pair<String,ByteArray>>();while(input.available()>0){val n=input.readInt();val type=ByteArray(4);input.readFully(type);val data=ByteArray(n);input.readFully(data);assertEquals(input.readInt(),CRC32().apply{update(type);update(data)}.value.toInt());result+=String(type) to data};return result}
    @Test fun pngPreservesIdatAndIccAndWritesSingleExifAndUtcModificationTime(){val before=png();withFile(before){f ->
        PhotoMetadataContainer.inject(f,ExportFormat.PNG,exif(),0)
        val after=chunks(f.readBytes());for(tag in listOf("IHDR","IDAT","iCCP"))assertArrayEquals(chunks(before).single{it.first==tag}.second,after.single{it.first==tag}.second)
        assertArrayEquals(byteArrayOf(0,18,52,86),InflaterInputStream(ByteArrayInputStream(after.single{it.first=="IDAT"}.second)).readBytes())
        assertArrayEquals(exif(),after.single{it.first=="eXIf"}.second);assertArrayEquals(byteArrayOf(7,-78,1,1,0,0,0),after.single{it.first=="tIME"}.second)
    }}
    @Test fun jpegPreservesIccAndEntropyAndReplacesExifOnly(){
        val old=ByteArrayOutputStream();DataOutputStream(old).use{out->out.writeShort(0xffd8);out.writeShort(0xffe1);out.writeShort(10);out.write(byteArrayOf(69,120,105,102,0,0,1,2));out.writeShort(0xffe2);out.writeShort(8);out.write(byteArrayOf(73,67,67,0,1,2));out.writeShort(0xffda);out.writeShort(2);out.write(byteArrayOf(1,2,-1,0,3,-1,-39))}
        withFile(old.toByteArray()){f->PhotoMetadataContainer.inject(f,ExportFormat.JPEG,exif());val after=f.readBytes();assertArrayEquals(old.toByteArray().copyOfRange(14,old.size()),after.copyOfRange(2+4+6+exif().size,after.size));assertArrayEquals(exif(),after.copyOfRange(12,12+exif().size))}
    }
    private fun tiff():ByteArray {
        val count=12;val data=8+2+count*12+4;val b=le(data+18)
        b.put(73).put(73).putShort(42).putInt(8).putShort(count.toShort())
        fun entry(tag:Int,type:Int,n:Int,value:Int){b.putShort(tag.toShort()).putShort(type.toShort()).putInt(n).putInt(value)}
        entry(256,4,1,1);entry(257,4,1,1);entry(258,3,3,data);entry(259,3,1,1);entry(262,3,1,2);entry(273,4,1,data+6);entry(274,3,1,1);entry(277,3,1,3);entry(278,4,1,1);entry(279,4,1,6);entry(284,3,1,1);entry(34675,7,6,data+12);b.putInt(0)
        b.putShort(16).putShort(16).putShort(16);b.putShort(0x1234).putShort(0x5678).putShort(0x7abc);b.put(byteArrayOf(73,67,67,1,2,3));return b.array()
    }
    private fun entries(bytes:ByteArray,offset:Int):Map<Int,Int>{val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val n=b.getShort(offset).toInt() and 65535;return (0 until n).associate{val e=offset+2+12*it;(b.getShort(e).toInt() and 65535) to e}}
    @Test fun tiffKeepsRgb16SamplesIccAndRelocatesCaptureDirectory(){val before=tiff();withFile(before){f->
        PhotoMetadataContainer.inject(f,ExportFormat.TIFF16,exif());val after=f.readBytes();assertArrayEquals(before.copyOfRange(8,before.size),after.copyOfRange(8,before.size))
        val b=ByteBuffer.wrap(after).order(ByteOrder.LITTLE_ENDIAN);val root=entries(after,b.getInt(4));val strip=b.getInt(root.getValue(273)+8);assertEquals(0x1234,b.getShort(strip).toInt());assertEquals(0x5678,b.getShort(strip+2).toInt());assertEquals(0x7abc,b.getShort(strip+4).toInt())
        val icc=b.getInt(root.getValue(34675)+8);assertArrayEquals(byteArrayOf(73,67,67,1,2,3),after.copyOfRange(icc,icc+6));assertEquals(1,b.getInt(root.getValue(274)+8))
        val capture=entries(after,b.getInt(root.getValue(34665)+8));val date=b.getInt(capture.getValue(36867)+8);assertEquals("2001:02:03 04:05:06\u0000",String(after,date,20))
    }}
    @Test fun malformedAndOversizedMetadataLeaveOriginalUntouched(){withFile(png()){f->val original=f.readBytes();for(exif in listOf(ByteArray(70000),exif().also{it[4]=127})){assertThrows(Exception::class.java){PhotoMetadataContainer.inject(f,ExportFormat.PNG,exif)};assertArrayEquals(original,f.readBytes())}}}
    @Test fun invalidPngCrcLeavesOriginalUntouched(){val corrupt=png().also{it[it.size-1]=0};withFile(corrupt){f->assertThrows(Exception::class.java){PhotoMetadataContainer.inject(f,ExportFormat.PNG,exif())};assertArrayEquals(corrupt,f.readBytes())}}
    private fun decode(file:File):Any=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,file)
    @Test fun independentJdkTiffDecoderStillReadsExact16BitSamples(){withFile(tiff()){f->
        PhotoMetadataContainer.inject(f,ExportFormat.TIFF16,exif())
        val image=decode(f);val raster=image.javaClass.getMethod("getRaster").invoke(image)
        val buffer=raster.javaClass.getMethod("getDataBuffer").invoke(raster)
        assertEquals(1,buffer.javaClass.getMethod("getDataType").invoke(buffer)) // TYPE_USHORT
        val pixel=Class.forName("java.awt.image.Raster").getMethod("getPixel",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,IntArray::class.java).invoke(raster,0,0,null) as IntArray
        assertArrayEquals(intArrayOf(0x1234,0x5678,0x7abc),pixel)
    }}
    @Test fun realJpegDecodeUnchanged(){
        val imageClass=Class.forName("java.awt.image.BufferedImage")
        val image=imageClass.getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).newInstance(7,5,1)
        val set=imageClass.getMethod("setRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        for(y in 0..4)for(x in 0..6)set.invoke(image,x,y,(x*37 shl 16) or (y*51 shl 8) or 101)
        val bytes=ByteArrayOutputStream()
        assertEquals(true,Class.forName("javax.imageio.ImageIO").getMethod("write",Class.forName("java.awt.image.RenderedImage"),String::class.java,OutputStream::class.java).invoke(null,image,"jpeg",bytes))
        val get=imageClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        withFile(bytes.toByteArray()){f->val before=decode(f);PhotoMetadataContainer.inject(f,ExportFormat.JPEG,exif());val after=decode(f)
            for(y in 0..4)for(x in 0..6)assertEquals(get.invoke(before,x,y),get.invoke(after,x,y))
        }
    }
}
