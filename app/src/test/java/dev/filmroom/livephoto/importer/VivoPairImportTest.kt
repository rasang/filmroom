package dev.filmroom.livephoto.importer

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class VivoPairImportTest {
    private val id = "1234567890123000000000000000"
    private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun box(type: String, data: ByteArray) = int(data.size + 8) + type.toByteArray() + data
    private fun movie() = box("ftyp", "isom0000".toByteArray()) +
        box("moov", box("trak", box("mdia", box("hdlr", ByteArray(8) + "vide".toByteArray())))) +
        box("mdat", byteArrayOf(1,2,3))
    private fun jpeg() = byteArrayOf(-1,-40,-1,-64,0,11,8,0,1,0,1,1,1,17,0,
        -1,-38,0,8,1,1,0,0,63,0,1,2,3,-1,-39)
    // Independently assembled vendor record; not a call to the production writer.
    private fun tail(version: Long, pairId: String = id, jsonOverride: String? = null): ByteArray {
        val json = (jsonOverride ?: "{\"com.android.camera.livephoto\":\"$pairId\",\"version\":$version}").toByteArray()
        return "vivo".toByteArray() + json + int(json.size) + "cameralbum!".toByteArray() +
            int(47) + pairId.toByteArray() + ByteArray(4) { -1 } +
            byteArrayOf(27,42,57,72,87,102,117,-124,-109,-94,-77)
    }
    private fun videoTail(bytes: ByteArray) = box("uuid", "vivoMediaExtInfo".toByteArray() + bytes)
    private fun fixture(block: (File, File, File) -> Unit) {
        val dir = Files.createTempDirectory("vivo-import").toFile()
        try { block(dir, File(dir,"cover.jpg"), File(dir,"video.mp4")) } finally { dir.deleteRecursively() }
    }
    @Test fun allSupportedVersionPairsExtractOnlyOriginalMedia() = fixture { dir, image, video ->
        for ((imageVersion, videoVersion) in listOf(2014L to 2014L,2100L to 2100L,2104L to 2104L,2200L to 2016L)) {
            image.writeBytes(jpeg() + tail(imageVersion)); video.writeBytes(movie() + videoTail(tail(videoVersion)))
            val hint = requireNotNull(MotionPhotoExtractor.inspectPairImage(image))
            assertEquals(id, hint.id); assertEquals(imageVersion, hint.version)
            assertNull(MotionPhotoExtractor.inspect(image))
            val source = requireNotNull(MotionPhotoExtractor.inspect(image, video))
            val output = File(dir,"$imageVersion.mp4")
            MotionPhotoExtractor.extract(source,output)
            assertArrayEquals(movie(),output.readBytes())
        }
    }
    @Test fun native2100WithGainmapAndRichMetadataExtractsOriginalMedia() = fixture { dir, image, video ->
        // Synthetic HDR JPEG, no user media or identifiers in the fixture.
        val gainmap = jpeg().dropLast(2).toByteArray() + ByteArray(250000) { 1 } + byteArrayOf(-1,-39)
        val json = """{"com.android.camera.livephoto":"$id","version":2100,"optional":null,"enabled":false,"faceInfo":{"faces":[{},true,1.25,-2e3,"vivo}cameralbum!"]}}"""
        image.writeBytes(jpeg() + gainmap + ByteArray(42000) { 2 } + tail(2100,jsonOverride=json))
        video.writeBytes(movie() + videoTail(tail(2100,jsonOverride=json)))
        assertEquals(2100L, requireNotNull(MotionPhotoExtractor.inspectPairImage(image)).version)
        val source = requireNotNull(MotionPhotoExtractor.inspect(image,video))
        val output = File(dir,"original.mp4")
        MotionPhotoExtractor.extract(source,output)
        assertArrayEquals(movie(),output.readBytes())
        video.writeBytes(movie() + videoTail(tail(2104)))
        assertNull(MotionPhotoExtractor.inspect(image,video))
        video.writeBytes(movie() + videoTail(tail(2100,"ffffffffffffffffffffffffffff")))
        assertNull(MotionPhotoExtractor.inspect(image,video))
    }
    @Test fun nativeMetadataRejectsMalformedNestedValuesAndLengths() = fixture { _, image, _ ->
        val prefix = """{"com.android.camera.livephoto":"$id","version":2100,"extra":"""
        for (value in listOf("{\"a\":1,\"a\":2}", "[1,]", "01", "tru", "[".repeat(18)+"0"+"]".repeat(18))) {
            image.writeBytes(jpeg()+tail(2100,jsonOverride=prefix+value+"}"))
            assertNull(value,MotionPhotoExtractor.inspectPairImage(image))
        }
        for (version in listOf("2100.0","2.1e3","\"2100\"","null","true")) {
            image.writeBytes(jpeg()+tail(2100,jsonOverride="""{"com.android.camera.livephoto":"$id","version":$version}"""))
            assertNull(MotionPhotoExtractor.inspectPairImage(image))
        }
        val valid = tail(2100)
        for (offset in listOf(valid.size-62,valid.size-47,valid.size-15,valid.lastIndex)) {
            image.writeBytes(jpeg()+valid.copyOf().also { it[offset] = 0x7f })
            assertNull(MotionPhotoExtractor.inspectPairImage(image))
        }
    }
    @Test fun sameNameOrSameIdCannotOverrideInvalidVersionPair() = fixture { _, image, video ->
        image.writeBytes(jpeg() + tail(2014))
        for (videoVersion in listOf(2104L,2200L,2016L,9999L)) {
            video.writeBytes(movie() + videoTail(tail(videoVersion)))
            assertNull(MotionPhotoExtractor.inspect(image,video))
        }
        video.writeBytes(movie() + videoTail(tail(2014,"ffffffffffffffffffffffffffff")))
        assertNull(MotionPhotoExtractor.inspect(image,video))
    }
    @Test fun unsupportedOrForgedImageRecordsNeverBecomePairHints() = fixture { _, image, _ ->
        val valid = tail(2014)
        val duplicate = "{\"com.android.camera.livephoto\":\"$id\",\"version\":2014,\"version\":2104}"
        for (bytes in listOf(jpeg(),jpeg()+tail(2016),jpeg()+tail(9999),
            jpeg()+tail(2014,"motionphoto0001".padEnd(28,'0')),
            jpeg()+valid+byteArrayOf(0),jpeg()+byteArrayOf(0)+valid,
            jpeg()+valid.copyOf(valid.size-1),jpeg()+tail(2014,jsonOverride=duplicate))) {
            image.writeBytes(bytes); assertNull(MotionPhotoExtractor.inspectPairImage(image))
        }
    }
    @Test fun malformedVideoAndNonTerminalExtensionsAreRejected() = fixture { _, image, video ->
        image.writeBytes(jpeg()+tail(2014))
        val extension = videoTail(tail(2014))
        for (bytes in listOf(extension, movie()+extension.copyOf(extension.size-1),
            movie()+extension+box("free",byteArrayOf(1)), movie()+extension+extension, movie()+videoTail(tail(2014).also { it[it.lastIndex]=0 }))) {
            video.writeBytes(bytes); assertNull(MotionPhotoExtractor.inspect(image,video))
        }
    }
    @Test fun cancellationIsNotMisreportedAsOrdinaryImage() = fixture { _, image, _ ->
        image.writeBytes(jpeg()+tail(2014))
        assertThrows(CancellationException::class.java) { MotionPhotoExtractor.inspectPairImage(image) { throw CancellationException() } }
    }
}
