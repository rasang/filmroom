package dev.filmroom.livephoto.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VivoSingleMimoTest {
    // Independently transcribed from MediaExtendInfo/VideoBoxFormat's framing, not our parser.
    private val golden = "000000a8757569647669766f4d65646961457874496e666f7669766f7b22636f6d2e616e64726f69642e63616d6572612e6c69766570686f746f223a226d6f74696f6e70686f746f3030303130303030303030303030303030222c2276657273696f6e223a323031347d0000004e63616d6572616c62756d210000002f6d6f74696f6e70686f746f3030303130303030303030303030303030ffffffff1b2a39485766758493a2b3".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun box(type:String,data:ByteArray)=be32(data.size+8L)+ascii(type)+data
    private fun movie()=box("ftyp",ascii("isom")+ByteArray(4))+
        box("moov",box("trak",box("mdia",box("hdlr",ByteArray(8)+ascii("vide")))))+box("mdat",byteArrayOf(1,2,3,4))
    private fun jpeg()=byteArrayOf(-1,-40,-1,-64,0,11,8,0,1,0,1,1,1,17,0,-1,-38,0,8,1,1,0,0,63,0,1,2,3,-1,-39)
    @Test fun knownSdkFraming() { assertEquals(168,golden.size);assertArrayEquals(golden,Vivo.singleExtension()) }
    @Test fun xmpIncludesExtendedVideoButExtractionPreservesOriginalMedia() {
        val dir=Files.createTempDirectory("vivo-single").toFile()
        try {
            val image=File(dir,"in.jpg").apply{writeBytes(jpeg())}; val video=File(dir,"in.mp4").apply{writeBytes(movie())}
            val cover=CoverInfo(0,0,1,1000000)
            for (existing in listOf(false,true)) {
                video.writeBytes(movie()+if(existing)golden else byteArrayOf())
                val asset=LivePhotoPackager.packageTo(PackageRequest(image,video,LivePhotoProfile.VIVO_SINGLE,cover,true),File(dir,"out$existing"))
                val bytes=asset.image.file.readBytes();val j=Jpeg.inspect(bytes)
                val x=Xmp.inspect(Jpeg.xmpPackets(bytes,j).single())
                assertEquals(movie().size+168L,x.extent)
                assertArrayEquals(movie()+golden,bytes.copyOfRange(j.end,bytes.size))
                assertFalse(x.fields.containsKey(Xmp.V to "VMotionPhotoSource"))
                assertFalse(x.fields.containsKey(Xmp.V to "VMediaKitVersion"))
                assertArrayEquals(movie(),LivePhotoVerifier.extractVideo(asset,File(dir,"extract$existing.mp4")).readBytes())
                val corrupt=bytes.copyOf();corrupt[corrupt.lastIndex]=0;asset.image.file.writeBytes(corrupt)
                assertTrue(runCatching { LivePhotoVerifier.inspect(asset.image.file,null,asset.profile,cover,null) }.isFailure)
            }
        } finally { dir.deleteRecursively() }
    }
}
