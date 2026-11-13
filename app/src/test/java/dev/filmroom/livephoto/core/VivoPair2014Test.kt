package dev.filmroom.livephoto.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VivoPair2014Test {
    private val id = "1234567890123000000000000000"
    private val profile = LivePhotoProfile.VIVO_PAIR_2014
    private fun box(type: String, data: ByteArray) = be32(data.size + 8L) + ascii(type) + data
    private fun movie() = box("ftyp", ascii("isom") + ByteArray(4)) +
        box("moov", box("trak", box("mdia", box("hdlr", ByteArray(8) + ascii("vide"))))) + box("mdat", byteArrayOf(1,2,3,4))
    private fun jpeg() = byteArrayOf(0xff.toByte(),0xd8.toByte()) +
        byteArrayOf(0xff.toByte(),0xc0.toByte(),0,11,8,0,1,0,1,1,1,0x11,0) +
        byteArrayOf(0xff.toByte(),0xda.toByte(),0,8,1,1,0,0,63,0,1,2,3,0xff.toByte(),0xd9.toByte())
    private fun withFixture(block: (File, File, File) -> Unit) {
        val dir = Files.createTempDirectory("vivo2014").toFile()
        try { val image=File(dir,"in.jpg").apply { writeBytes(jpeg()) }; val video=File(dir,"in.mp4").apply { writeBytes(movie()) }; block(dir,image,video) }
        finally { dir.deleteRecursively() }
    }
    @Test fun goldenTailAndUuid() {
        val json = "{\"com.android.camera.livephoto\":\"$id\",\"version\":2014}".toByteArray()
        val golden = ascii("vivo") + json + be32(json.size.toLong()) + ascii("cameralbum!") +
            byteArrayOf(0,0,0,47) + ascii(id) + ByteArray(4) { -1 } +
            byteArrayOf(0x1b,0x2a,0x39,0x48,0x57,0x66,0x75,0x84.toByte(),0x93.toByte(),0xa2.toByte(),0xb3.toByte())
        for (video in listOf(false,true)) {
            val tail = Vivo.tail(Vivo.metadata(id,profile,video,87))
            assertArrayEquals(golden,tail)
            assertEquals(setOf(Vivo.ID_KEY,"version"),Vivo.parse(tail).fields.keys)
        }
        val uuid = Vivo.uuid(golden)
        assertEquals(uuid.size.toLong(),u32(uuid,0))
        assertEquals("uuidvivoMediaExtInfo",String(uuid.copyOfRange(4,24),Charsets.US_ASCII))
        assertArrayEquals(golden,uuid.copyOfRange(24,uuid.size))
    }
    @Test fun nonFirstCoverRoundTripAndRepeatedPackagingDoesNotGrowVideo() = withFixture { dir,image,video ->
        val cover = CoverInfo(2_900_000,87,90,3_000_000)
        val request = PackageRequest(image,video,profile,cover,true,id)
        val first = LivePhotoPackager.packageTo(request,File(dir,"first"))
        assertEquals("jpeg",first.image.file.extension)
        assertEquals(first.image.file.nameWithoutExtension,first.companionVideo!!.file.nameWithoutExtension)
        assertEquals(FileDigests.sha256(video),first.report.videoSha256)
        assertEquals(2014L,Vivo.parse(first.image.file.readBytes().copyOfRange(Jpeg.inspect(first.image.file.readBytes()).end,first.image.file.length().toInt())).fields["version"])
        val second = LivePhotoPackager.packageTo(request.copy(coverJpeg=first.image.file,videoMp4=first.companionVideo.file),File(dir,"second"))
        assertArrayEquals(first.image.file.readBytes(),second.image.file.readBytes())
        assertArrayEquals(first.companionVideo.file.readBytes(),second.companionVideo!!.file.readBytes())
        assertTrue(LivePhotoVerifier.verify(second).warnings.any { it.contains("非首帧") })
        assertArrayEquals(movie(),LivePhotoVerifier.extractVideo(second,File(dir,"extracted.mp4")).readBytes())
    }
    @Test fun everyWriterProfileStillPackagesAndExtractsUnchangedMedia() = withFixture { dir,image,video ->
        for (candidate in LivePhotoProfile.values()) {
            val asset=LivePhotoPackager.packageTo(PackageRequest(image,video,candidate,CoverInfo(0,0,90,3000000),true,id),File(dir,candidate.name))
            assertEquals(FileDigests.sha256(video),LivePhotoVerifier.verify(asset).videoSha256)
            assertArrayEquals(movie(),LivePhotoVerifier.extractVideo(asset,File(dir,"${candidate.name}.mp4")).readBytes())
            assertFalse(asset.report.galleryConfirmed)
        }
    }
    @Test fun existingProfilesKeepTheirSchemas() {
        assertEquals(2104L,Vivo.metadata(id,LivePhotoProfile.VIVO_PAIR_2104,true,87)["version"])
        assertEquals(87L,Vivo.metadata(id,LivePhotoProfile.VIVO_PAIR_2104,true,87)["com.android.camera.imageTime"])
        assertEquals(2200L,Vivo.metadata(id,LivePhotoProfile.VIVO_PAIR_LEGACY,false,0)["version"])
        assertEquals(2016L,Vivo.metadata(id,LivePhotoProfile.VIVO_PAIR_LEGACY,true,0)["version"])
    }
    @Test fun cancellationDuringPackagingRemovesOwnedDirectoryAndPreservesInputs() = withFixture { dir,image,video ->
        val out=File(dir,"cancel"); var calls=0
        assertTrue(runCatching { LivePhotoPackager.packageTo(PackageRequest(image,video,profile,CoverInfo(0,0,1,1000000),true,id),out) { if (++calls==5) error("cancel") } }.isFailure)
        assertFalse(out.exists()); assertArrayEquals(movie(),video.readBytes()); assertArrayEquals(jpeg(),image.readBytes())
    }
    private class Sink(private val failAt: Int): MediaSink {
        val rows=linkedMapOf<String,Triple<String,String,String>>()
        var operations=0
        private fun step() { if (++operations==failAt) error("injected IO failure") }
        override fun insertPending(displayName:String,mimeType:String,relativePath:String):String {
            step(); val id="row-${rows.size}"; rows[id]=Triple(displayName,mimeType,relativePath); return id
        }
        override fun copyAndVerify(id:String,source:File,expectedSha256:String,checkActive:()->Unit) {
            checkActive(); step(); assertEquals(expectedSha256,FileDigests.sha256(source))
        }
        override fun publish(id:String) { step() }
        override fun deleteOwned(id:String):Boolean { rows.remove(id); return true }
        override fun existsOwned(id:String)=rows.containsKey(id)
        override fun findOwned(displayName:String,mimeType:String,relativePath:String)=rows.filterValues { it==Triple(displayName,mimeType,relativePath) }.keys.toList()
    }
    @Test fun pairPublicationRollsBackBothRowsOnEveryInsertWriteAndPublishFailure() = withFixture { dir,image,video ->
        val asset=LivePhotoPackager.packageTo(PackageRequest(image,video,profile,CoverInfo(0,0,1,1000000),true,id),File(dir,"asset"))
        for (failure in 1..6) {
            val sink=Sink(failure); val publisher=PublicationCoordinator(File(dir,"journal-$failure"),sink)
            assertTrue(runCatching { publisher.publish(asset) }.isFailure)
            assertTrue(sink.rows.isEmpty()); assertTrue(publisher.recoverInterrupted().failures.isEmpty())
        }
        val sink=Sink(0); val publisher=PublicationCoordinator(File(dir,"journal-ok"),sink)
        val result=publisher.publish(asset)
        assertEquals(2,result.ids.size); assertFalse(result.galleryConfirmed)
        assertEquals(result,publisher.publish(asset)); assertEquals(6,sink.operations)
    }
    @Test fun preparedQueueBindingIsDurableBeforeAnyMediaBecomesVisible() = withFixture { dir,image,video ->
        val asset=LivePhotoPackager.packageTo(PackageRequest(image,video,profile,CoverInfo(0,0,1,1000000),true,id),File(dir,"prepared-asset"))
        val sink=Sink(0);val publisher=PublicationCoordinator(File(dir,"prepared-journal"),sink)
        var prepared:List<String>?=null
        val saved=publisher.publish(asset,onPrepared={rows->
            assertEquals(4,sink.operations) // two inserts/copies; neither publish yet
            prepared=rows
        })
        assertEquals(saved.ids,prepared)
        val badSink=Sink(0);val bad=PublicationCoordinator(File(dir,"failed-binding"),badSink)
        assertTrue(runCatching{bad.publish(asset,onPrepared={error("queue journal failed")})}.isFailure)
        assertTrue(badSink.rows.isEmpty())
        assertEquals(4,badSink.operations)
    }
    @Test fun malformedOrNonTerminalOldMetadataIsRejected() = withFixture { dir,image,video ->
        val request=PackageRequest(image,video,profile,CoverInfo(0,0,1,1000000),true,id)
        video.appendBytes(Vivo.uuid(Vivo.tail(Vivo.metadata(id,profile,true,0))) + box("free",byteArrayOf(1)))
        assertTrue(runCatching { LivePhotoPackager.packageTo(request,File(dir,"bad-video")) }.isFailure)
        video.writeBytes(movie()); image.appendBytes(ascii("unrecognized appended payload"))
        assertTrue(runCatching { LivePhotoPackager.packageTo(request,File(dir,"bad-cover")) }.isFailure)
    }
    @Test fun scansRunOnlyAfterPairCommitInImageVideoOrderAndDoNotRepublishOnFailure() = withFixture { dir,image,video ->
        val asset=LivePhotoPackager.packageTo(PackageRequest(image,video,profile,CoverInfo(0,0,1,1000000),true,id),File(dir,"scan-asset"))
        val sink=Sink(0); val publisher=PublicationCoordinator(File(dir,"scan-journal"),sink)
        val published=publisher.publish(asset)
        val seen=mutableListOf<String>()
        val scans=PairScanNotification.notify(asset,published) { mediaId,mime ->
            assertEquals(6,sink.operations); assertEquals(1,publisher.recoverInterrupted().retainedCommitted)
            seen+=mime
            if (mime=="image/jpeg") throw SecurityException("blocked")
            MediaScanResult(mediaId,MediaScanStatus.TIMED_OUT)
        }
        assertEquals(listOf("image/jpeg","video/mp4"),seen)
        assertEquals(listOf(MediaScanStatus.REQUEST_FAILED,MediaScanStatus.TIMED_OUT),scans.map { it.status })
        assertEquals(2,sink.rows.size); assertFalse(published.galleryConfirmed)
        assertEquals(published,publisher.publish(asset)); assertEquals(6,sink.operations)
    }
    @Test fun scannerAcknowledgementIsNotGalleryConfirmationAndOtherProfilesDoNotScan() = withFixture { dir,image,video ->
        val asset=LivePhotoPackager.packageTo(PackageRequest(image,video,profile,CoverInfo(0,0,1,1000000),true,id),File(dir,"scan-good"))
        val published=PublicationCoordinator(File(dir,"scan-good-journal"),Sink(0)).publish(asset)
        val scans=PairScanNotification.notify(asset,published) { mediaId,_ -> MediaScanResult(mediaId,MediaScanStatus.SCANNED) }
        assertEquals(2,scans.size); assertFalse(published.galleryConfirmed)
        assertTrue(PairScanNotification.notify(asset.copy(profile=LivePhotoProfile.VIVO_PAIR_2104),published) { _,_ -> error("must not scan") }.isEmpty())
    }
    @Test fun generatedIdsHaveDecimalSyntaxAndDoNotCollide() {
        val ids=(1..1000).map { randomDecimalBindingId() }
        assertEquals(1000,ids.toSet().size); assertTrue(ids.all { it.matches(Regex("[0-9]{28}")) })
    }
}
