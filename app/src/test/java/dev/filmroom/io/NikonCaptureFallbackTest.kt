package dev.filmroom.io

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class NikonCaptureFallbackTest {
    private fun fixture(order:ByteOrder):ByteArray {
        val b=ByteBuffer.allocate(384).order(order)
        fun header(at:Int) {b.put(at,(if(order==ByteOrder.LITTLE_ENDIAN)73 else 77).toByte());b.put(at+1,b.get(at));b.putShort(at+2,42);b.putInt(at+4,8)}
        fun entry(at:Int,tag:Int,type:Int,count:Int,value:Int){b.putShort(at,tag.toShort());b.putShort(at+2,type.toShort());b.putInt(at+4,count);b.putInt(at+8,value)}
        header(0);b.putShort(8,1);entry(10,0x8769,4,1,64)
        b.putShort(64,1);entry(66,0x927c,7,256,128)
        "Nikon\u0000".toByteArray().copyInto(b.array(),128);b.put(134,2);b.put(135,16)
        header(138);b.putShort(146,2);entry(148,0x25,7,14,128);entry(160,0x84,5,4,160)
        b.put(266,48)
        listOf(240 to 10,700 to 10,28 to 10,28 to 10).forEachIndexed{i,(n,d)->b.putInt(298+i*8,n);b.putInt(302+i*8,d)}
        return b.array()
    }
    private fun read(data:ByteArray):Map<String,String> {
        val file=File.createTempFile("nikon-capture-", ".tiff")
        return try {file.writeBytes(data);NikonCaptureFallback.read(file)} finally {file.delete()}
    }
    @Test fun littleAndBigEndianTypeTwoRecoverIsoAndLensSpecificationsWithoutInventingLensModel() {
        for(order in listOf(ByteOrder.LITTLE_ENDIAN,ByteOrder.BIG_ENDIAN)) {
            val fields=read(fixture(order))
            assertEquals("50",fields["PhotographicSensitivity"])
            assertEquals("240/10,700/10,28/10,28/10",fields["LensSpecification"])
            assertEquals(setOf("PhotographicSensitivity","LensSpecification"),fields.keys)
        }
    }
    @Test fun badHeadersTruncatedDirectoriesAndOutOfMakerOffsetsAreRejected() {
        val good=fixture(ByteOrder.LITTLE_ENDIAN)
        for(data in listOf(good.copyOf(300),good.copyOf().apply{this[134]=1},good.copyOf().apply{this[128]=0},good.copyOf().apply{ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(146,513)},good.copyOf().apply{ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(156,-1)},good.copyOf().apply{ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(160,0x25)},good.copyOf().apply{ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(70,524289)})) {
            assertTrue(read(data).isEmpty())
        }
    }
    @Test fun productionReadPreservesValidStandardIsoAndLensOverPrivateFallback() {
        val data=fixture(ByteOrder.LITTLE_ENDIAN).copyOf(512)
        val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(8,2)
        b.putShort(22,0x10f);b.putShort(24,2);b.putInt(26,18);b.putInt(30,400)
        "NIKON CORPORATION\u0000".toByteArray().copyInto(data,400)
        b.putShort(64,3)
        b.putShort(78,0x8827.toShort());b.putShort(80,3);b.putInt(82,1);b.putInt(86,640)
        b.putShort(90,0xa432.toShort());b.putShort(92,5);b.putInt(94,4);b.putInt(98,440)
        listOf(20,20,2,2).forEachIndexed{i,n->b.putInt(440+i*8,n);b.putInt(444+i*8,1)}
        val file=File.createTempFile("nikon-standard-priority-", ".nef")
        try {
            file.writeBytes(data)
            assertEquals("50",NikonCaptureFallback.read(file)["PhotographicSensitivity"])
            val fields=org.json.JSONObject(PhotoMetadata.read(file).toJson()).getJSONObject("attributes")
            assertEquals("640",fields.getString("PhotographicSensitivity"))
            assertEquals("20/1,20/1,2/1,2/1",fields.getString("LensSpecification"))
        } finally {file.delete()}
    }
    @Test fun worldTimeUsesRecordedBaseZoneAndDstNotCurrentDeviceZone() {
        for(order in listOf(ByteOrder.LITTLE_ENDIAN,ByteOrder.BIG_ENDIAN)) {
            val data=fixture(order);val b=ByteBuffer.wrap(data).order(order)
            b.putShort(146,3);b.putShort(172,0x24);b.putShort(174,7);b.putInt(176,4)
            b.putShort(180,60);b.put(182,1)
            assertEquals("+02:00",read(data)["OffsetTimeOriginal"])
            b.put(182,0);assertEquals("+01:00",read(data)["OffsetTimeOriginal"])
            b.put(182,2);assertFalse(read(data).containsKey("OffsetTimeOriginal"))
            b.put(182,1);b.putShort(180,32767);assertFalse(read(data).containsKey("OffsetTimeOriginal"))
        }
    }
    @Test fun wrongIsoTypeIsNotInterpretedAsBytes() {
        val data=fixture(ByteOrder.LITTLE_ENDIAN)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(150,3)
        val result=read(data);assertFalse(result.containsKey("PhotographicSensitivity"))
        assertTrue(result.containsKey("LensSpecification"))
    }
    @Test fun undefinedIsoAndZeroLensDenominatorAreNotInvented() {
        val data=fixture(ByteOrder.LITTLE_ENDIAN)
        data[266]=0;ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(302,0)
        assertTrue(read(data).isEmpty())
    }
}
