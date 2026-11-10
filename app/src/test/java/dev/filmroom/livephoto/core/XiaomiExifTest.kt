package dev.filmroom.livephoto.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class XiaomiExifTest {
    private fun box(type:String,data:ByteArray)=be32(data.size+8L)+ascii(type)+data
    private fun movie()=box("ftyp",ascii("isom")+ByteArray(4))+
        box("moov",box("trak",box("mdia",box("hdlr",ByteArray(8)+ascii("vide")))))+box("mdat",byteArrayOf(1,2,3,4))
    private fun jpeg()=byteArrayOf(-1,-40,-1,-64,0,11,8,0,1,0,1,1,1,17,0,-1,-38,0,8,1,1,0,0,63,0,1,2,3,-1,-39)
    private fun flagEntry(bytes:ByteArray):Int? {
        val segment=Jpeg.inspect(bytes).segments.single { it.marker==0xe1 && bytes.startsAt(ascii("Exif\u0000\u0000"),it.payload) }
        val t=segment.payload+6
        fun short(p:Int)=(bytes[p].toInt() and 255) or ((bytes[p+1].toInt() and 255) shl 8)
        fun directory(p:Int)=(0 until short(p)).associate { val e=p+2+it*12;short(e) to e }
        val pointer=directory(t+u32(bytes,t+4,true).toInt())[0x8769] ?: return null
        return directory(t+u32(bytes,pointer+8,true).toInt())[0x9a01]
    }
    private fun readField(bytes:ByteArray,group:Int,tag:Int,size:Int):ByteArray {
        val segment=Jpeg.inspect(bytes).segments.single { it.marker==0xe1 && bytes.startsAt(ascii("Exif\u0000\u0000"),it.payload) }
        val t=segment.payload+6
        fun short(p:Int)=(bytes[p].toInt() and 255) or ((bytes[p+1].toInt() and 255) shl 8)
        fun directory(p:Int)=(0 until short(p)).associate { val e=p+2+it*12;short(e) to e }
        val main=directory(t+u32(bytes,t+4,true).toInt())
        val entries=if(group==0)main else directory(t+u32(bytes,main.getValue(if(group==2)0x8825 else 0x8769)+8,true).toInt())
        val entry=entries.getValue(tag);val start=if(size<=4)entry+8 else t+u32(bytes,entry+8,true).toInt()
        return bytes.copyOfRange(start,start+size)
    }
    @Test fun rejectsStalePointersAndVendorTagsInCaptureMetadata() {
        for(tag in listOf(0x8769,0x9286,0x9a01,0x112,0xa002)) {
            assertTrue(runCatching { Jpeg.exif(null,captureExif=listOf(LivePhotoCaptureExif(1,tag,1,byteArrayOf(1)))) }.isFailure)
        }
        val field=LivePhotoCaptureExif(0,0x10f,2,ascii("Maker\u0000"))
        assertTrue(runCatching { Jpeg.exif(null,captureExif=listOf(field,field)) }.isFailure)
    }
    @Test fun acceptsMaximumSafeTextAndBudget() {
        val fields=listOf(0x10e,0x10f,0x110,0x13b,0x8298).map { LivePhotoCaptureExif(0,it,2,ascii("x".repeat(4096)+"\u0000")) } +
            listOf(0xa430,0xa431,0xa433,0xa434).map { LivePhotoCaptureExif(1,it,2,ascii("y".repeat(4096)+"\u0000")) }
        assertTrue(Jpeg.exif(null,captureExif=fields).size>32*1024)
        val unicode=ascii("UNICODE\u0000")+byteArrayOf(0xfe.toByte(),0xff.toByte())+"字".repeat(4096).toByteArray(Charsets.UTF_16BE)
        assertTrue(Jpeg.exif(null,captureExif=listOf(LivePhotoCaptureExif(2,27,7,unicode))).size>8202)
    }
    @Test fun packagedFlagSurvivesCleaningWithAndWithoutDatesAndDoesNotLeak() {
        val dir=Files.createTempDirectory("xiaomi-exif").toFile()
        try {
            val image=File(dir,"in.jpg").apply { writeBytes(jpeg()) }
            val video=File(dir,"in.mp4").apply { writeBytes(movie()) }
            val facts=listOf(
                LivePhotoCaptureExif(0,0x10f,2,ascii("CameraMaker\u0000")),
                LivePhotoCaptureExif(1,0x829a,5,le32(1)+le32(125)),
                LivePhotoCaptureExif(1,0x8827,3,byteArrayOf(100,0)),
                LivePhotoCaptureExif(2,1,2,ascii("N\u0000")),
                LivePhotoCaptureExif(2,2,5,le32(24)+le32(1)+le32(30)+le32(1)+le32(0)+le32(1)))
            for(dates in listOf(null,LivePhotoDates(modified="2026:11:10 12:00:00"))) {
                for(profile in LivePhotoProfile.values()) {
                    val asset=LivePhotoPackager.packageTo(PackageRequest(image,video,profile,CoverInfo(0,0,1,1000000),true,captureDates=dates,captureExif=facts),File(dir,"${profile}-${dates!=null}"))
                    val bytes=asset.image.file.readBytes()
                    val entry=flagEntry(bytes)
                    assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("CameraMaker\u0000"))
                    assertArrayEquals(le32(1)+le32(125),readField(bytes,1,0x829a,8))
                    assertArrayEquals(byteArrayOf(100,0),readField(bytes,1,0x8827,2))
                    assertArrayEquals(ascii("N\u0000"),readField(bytes,2,1,2))
                    assertArrayEquals(facts.last().data,readField(bytes,2,2,24))
                    assertArrayEquals(le32(1),readField(bytes,0,0x100,4))
                    assertArrayEquals(le32(1),readField(bytes,1,0xa002,4))
                    if(profile==LivePhotoProfile.XIAOMI_COMPAT) {
                        assertNotNull(entry);val p=entry!!
                        assertEquals(1,bytes[p+2].toInt());assertEquals(0,bytes[p+3].toInt())
                        assertEquals(1L,u32(bytes,p+4,true));assertEquals(1,bytes[p+8].toInt())
                    } else assertNull(entry)
                    if(profile==LivePhotoProfile.OPLUS) {
                        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("ASCII\u0000\u0000\u0000oplus_8388608"))
                        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("Oplus_8388608"))
                    }
                    assertArrayEquals(movie(),LivePhotoVerifier.extractVideo(asset,File(dir,"${profile}-${dates!=null}.mp4")).readBytes())
                }
            }
        } finally {dir.deleteRecursively()}
    }
    @Test fun verifierRejectsMissingWrongTypeCountAndValue() {
        val dir=Files.createTempDirectory("xiaomi-exif-invalid").toFile()
        try {
            val image=File(dir,"in.jpg").apply {writeBytes(jpeg())};val video=File(dir,"in.mp4").apply {writeBytes(movie())}
            val cover=CoverInfo(0,0,1,1000000)
            val asset=LivePhotoPackager.packageTo(PackageRequest(image,video,LivePhotoProfile.XIAOMI_COMPAT,cover,true),File(dir,"asset"))
            val original=asset.image.file.readBytes();val p=flagEntry(original)!!
            for(offset in listOf(0,2,4,8)) {
                val changed=original.copyOf();changed[p+offset]=0
                asset.image.file.writeBytes(changed)
                val error=runCatching { LivePhotoVerifier.inspect(asset.image.file,null,asset.profile,cover,null) }.exceptionOrNull()
                assertTrue("mutation at $offset was accepted",error is LivePhotoException)
            }
        } finally {dir.deleteRecursively()}
    }
}
