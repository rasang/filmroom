package dev.filmroom.io

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/** Real Kotlin PNG encode/replay, zlib decode, ICC and EXIF parsing; no fake PNG encoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PngPayloadReuseHostTest {
    private val values=floatArrayOf(0f,.18f,1f,.7f,.4f,.1f,1f,0f,0f,0f,1f,0f)
    private val source get()=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply{values.forEach{putFloat(it)}}.array()
    private val old=PhotoMetadata.fromAttributes(mapOf("Make" to "Old camera","DateTimeOriginal" to "2001:02:03 04:05:06","OffsetTimeOriginal" to "+01:00","GPSLatitudeRef" to "N","GPSLatitude" to "1/1,2/1,3/1"))
    private val fresh=PhotoMetadata.fromAttributes(mapOf("Make" to "New camera","DateTimeOriginal" to "2020:01:02 03:04:05","OffsetTimeOriginal" to "+00:00"))
    private fun profile(space:OutputColorSpace)=if(space==OutputColorSpace.SRGB)null else ApplicationProvider.getApplicationContext<Application>().assets.open("color-profiles/${space.iccAsset}").use{it.readBytes()}
    private fun encode(space:OutputColorSpace,metadata:PhotoMetadata,millis:Long,side:OutputStream?=null)=ByteArrayOutputStream().also{
        LinearRgbPng.encode(ByteArrayInputStream(source),2,2,it,colorSpace=space,iccProfile=profile(space),exifMetadata=metadata.exifBytes(2,2,space.id,millis),exportMillis=millis,pixelPayload=side)
    }.toByteArray()
    private data class Chunk(val name:String,val bytes:ByteArray)
    private fun chunks(bytes:ByteArray):List<Chunk> {
        val result=mutableListOf<Chunk>();val input=DataInputStream(ByteArrayInputStream(bytes));input.skipBytes(8)
        while(input.available()>0) {
            val n=input.readInt();assertTrue(n in 0..(128*1024))
            val type=ByteArray(4).also{input.readFully(it)};val data=ByteArray(n).also{input.readFully(it)}
            assertEquals(CRC32().apply{update(type);update(data)}.value.toInt(),input.readInt())
            result+=Chunk(type.toString(Charsets.US_ASCII),data)
        }
        return result
    }
    private fun replay(bare:ByteArray,space:OutputColorSpace=OutputColorSpace.SRGB,metadata:PhotoMetadata=fresh,millis:Long=2001L,checkActive:()->Unit={})=ByteArrayOutputStream().also {
        LinearRgbPng.replayPixelPayload(ByteArrayInputStream(bare),it,2,2,space,profile(space),metadata.exifBytes(2,2,space.id,millis),millis,checkActive)
    }.toByteArray()
    private fun decodedRgb(png:ByteArray):IntArray {
        val image=Class.forName("javax.imageio.ImageIO").getMethod("read",InputStream::class.java).invoke(null,ByteArrayInputStream(png))
        assertNotNull(image)
        val get=image.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        return IntArray(4){get.invoke(image,it%2,it/2) as Int}
    }
    @Test fun eachGamutReplaysExactIdatAndCurrentColdBytesWithNewExifTimeAndPixels() {
        for(space in OutputColorSpace.entries) {
            val bare=ByteArrayOutputStream();val original=encode(space,old,1000,bare)
            val payload=bare.toByteArray();val naked=chunks(payload)
            assertEquals(listOf("IHDR",if(space==OutputColorSpace.SRGB)"sRGB" else "iCCP","IDAT","IEND"),naked.map{it.name})
            assertFalse(naked.any{it.name in setOf("eXIf","tIME")})
            val hit=replay(payload,space);val cold=encode(space,fresh,2001)
            assertArrayEquals(cold,hit);assertFalse(original.contentEquals(hit));assertArrayEquals(decodedRgb(cold),decodedRgb(hit))
            val warm=chunks(hit)
            assertArrayEquals(naked.single{it.name=="IDAT"}.bytes,warm.single{it.name=="IDAT"}.bytes)
            val rows=InflaterInputStream(ByteArrayInputStream(warm.single{it.name=="IDAT"}.bytes)).readBytes()
            assertEquals(14,rows.size);assertEquals(0,rows[0].toInt());assertEquals(0,rows[7].toInt())
            values.forEachIndexed{i,value->assertEquals(space.encode8(value),rows[i+1+(if(i>=6)1 else 0)].toInt() and 255)}
            assertArrayEquals(fresh.exifBytes(2,2,space.id,2001),warm.single{it.name=="eXIf"}.bytes)
            assertArrayEquals(byteArrayOf(7,-78,1,1,0,0,2),warm.single{it.name=="tIME"}.bytes) // 1970-01-01 UTC
            val exif=androidx.exifinterface.media.ExifInterface(ByteArrayInputStream(hit))
            assertEquals("New camera",exif.getAttribute("Make"));assertNull(exif.getAttribute("GPSLatitude"));assertEquals("001",exif.getAttribute("SubSecTime"))
            if(space==OutputColorSpace.SRGB)assertArrayEquals(byteArrayOf(0),warm.single{it.name=="sRGB"}.bytes)
            else {
                val packed=warm.single{it.name=="iCCP"}.bytes;val nul=packed.indexOf(0)
                assertEquals(space.id,String(packed,0,nul,Charsets.US_ASCII));assertEquals(0,packed[nul+1].toInt())
                assertArrayEquals(profile(space),InflaterInputStream(ByteArrayInputStream(packed,nul+2,packed.size-nul-2)).readBytes())
            }
            assertArrayEquals(payload,bare.toByteArray())
        }
    }
    @Test fun optionalTeeFailureNeverChangesNormalOutputOrReadsTheSourceAgain() {
        val expected=encode(OutputColorSpace.SRGB,fresh,2001)
        var read=0;var writes=0
        val input=object:ByteArrayInputStream(source){override fun read(b:ByteArray,off:Int,len:Int):Int=super.read(b,off,len).also{if(it>0)read+=it}}
        val failed=object:OutputStream(){override fun write(value:Int){writes++;throw IOException("optional cache full")}}
        val output=ByteArrayOutputStream()
        LinearRgbPng.encode(input,2,2,output,exifMetadata=fresh.exifBytes(2,2,"sRGB",2001),exportMillis=2001,pixelPayload=failed)
        assertArrayEquals(expected,output.toByteArray());assertEquals(48,read);assertEquals(1,writes)
    }
    @Test fun corruptTruncatedTrailingAndMetadataBearingPayloadsAreRejected() {
        val side=ByteArrayOutputStream();val full=encode(OutputColorSpace.SRGB,old,1000,side);val bare=side.toByteArray()
        for(bad in listOf(bare.copyOf(bare.size-1),bare+byteArrayOf(1),bare.clone().also{it[20]=(it[20].toInt() xor 1).toByte()},full))
            assertThrows(IOException::class.java){replay(bad)}
        assertThrows(IOException::class.java){LinearRgbPng.replayPixelPayload(ByteArrayInputStream(bare),ByteArrayOutputStream(),3,2)}
        assertThrows(IOException::class.java){replay(bare,OutputColorSpace.DISPLAY_P3)}
    }
    @Test fun replayCancellationAndOutputFailuresPropagateWithoutClaimingCompletion() {
        val side=ByteArrayOutputStream();encode(OutputColorSpace.SRGB,old,1000,side)
        var checks=0
        assertThrows(CancellationException::class.java){replay(side.toByteArray(),checkActive={if(++checks==3)throw CancellationException()})}
        val broken=object:OutputStream(){override fun write(value:Int){throw IOException("normal output failed")}}
        assertThrows(IOException::class.java){LinearRgbPng.replayPixelPayload(ByteArrayInputStream(side.toByteArray()),broken,2,2)}
    }
    @Test fun nonFiniteInputDoesNotProduceACompleteCachePayload() {
        val bad=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array();val side=ByteArrayOutputStream()
        assertThrows(IOException::class.java){LinearRgbPng.encode(ByteArrayInputStream(bad),2,2,ByteArrayOutputStream(),pixelPayload=side)}
        assertFalse(chunks(side.toByteArray()).any{it.name=="IEND"})
    }
}
