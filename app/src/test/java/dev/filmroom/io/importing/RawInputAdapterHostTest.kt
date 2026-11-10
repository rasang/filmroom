package dev.filmroom.io.importing

import android.app.Application
import dev.filmroom.io.*
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adapter ownership/metadata contract only; fake native pixels, not actual RAW decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class RawInputAdapterHostTest {
    @get:Rule val temp=TemporaryFolder()
    private val header=PhotoInputHeader(byteArrayOf(73,73,42,0),4)
    private fun metadata()=JSONObject().put("ok",true).put("schemaVersion",1).put("format","DNG")
        .put("outputOrientation",1).put("orientationApplied",true).put("width",8).put("height",8).put("outputBytes",768)
    private fun request(directory:File,existing:File?=null,allow:Boolean=true,onNew:(File)->Unit={}):PhotoImportRequest {
        val source=File(directory,"photo.jpg").apply{if(!exists())writeBytes(byteArrayOf(73,73,42,0))}
        return PhotoImportRequest(source,"photo.jpg",1280,EmptyCoroutineContext,existing,allow,onNew,directory,PhotoMetadata.unavailable())
    }
    private class Decoder(val result:()->JSONObject):RawSourceDecoder {
        var probes=0;var decodes=0;var write:(File)->Unit={it.writeBytes(ByteArray(768))}
        override suspend fun probe(file:File):JSONObject { probes++;return result() }
        override suspend fun decode(file:File,target:File):JSONObject { decodes++;write(target);return result() }
    }
    @Test fun cachedPrivateSpoolProbesWithoutDecodeAndPreservesCaptureMetadata()=runBlocking {
        val dir=temp.newFolder();val cached=File(dir,"cached.rgb").apply{writeBytes(ByteArray(768))}
        val decoder=Decoder{metadata()}
        val capture=PhotoMetadata.fromAttributes(mapOf("Make" to "Synthetic", "Model" to "Fixture", "DateTimeOriginal" to "2026:10:31 12:34:56", "ExposureTime" to "1/125", "FNumber" to "28/10", "PhotographicSensitivity" to "100"))
        val request=request(dir,cached,allow=false){fail("Must not allocate")}.copy(captureMetadata=capture)
        val loaded=RawInputAdapter(decoder).load(request,header)
        try {
            assertEquals(1,decoder.probes);assertEquals(0,decoder.decodes)
            assertSame(cached,loaded.source.linearFile);assertSame(request.captureMetadata,loaded.source.photoMetadata)
            assertEquals(capture.toJson(),loaded.source.photoMetadata.toJson())
            assertEquals("image/x-adobe-dng",loaded.source.mimeType)
            assertEquals(8,loaded.preview.width)
        }finally{loaded.preview.recycle()}
    }
    @Test fun missingRestoreSpoolDoesNotDecode()=runBlocking {
        val decoder=Decoder{metadata()}
        try { RawInputAdapter(decoder).load(request(temp.newFolder(),allow=false),header);fail("Missing restore spool") }
        catch(e:PhotoException){assertEquals(PhotoException.Reason.UNREADABLE,e.reason)}
        assertEquals(0,decoder.probes);assertEquals(0,decoder.decodes)
    }
    @Test fun outsideCacheIsNeverBorrowedAndNewSpoolIsRegisteredBeforeNativeWork()=runBlocking {
        val outside=temp.newFile().apply{writeBytes(ByteArray(768))};var registered:File?=null
        val decoder=Decoder{metadata()}.apply{write={target->assertSame(registered,target);target.writeBytes(ByteArray(768))}}
        val dir=temp.newFolder();val loaded=RawInputAdapter(decoder).load(request(dir,outside){registered=it},header)
        try {
            assertEquals(0,decoder.probes);assertEquals(1,decoder.decodes)
            assertEquals(dir.canonicalFile,loaded.source.linearFile!!.canonicalFile.parentFile)
            assertNotEquals(outside,loaded.source.linearFile);assertTrue(outside.isFile)
        }finally{loaded.preview.recycle()}
    }
    @Test fun failedDecodeExposesOwnedPartialToRepositoryWithoutSwallowingError()=runBlocking {
        val expected=PhotoException(PhotoException.Reason.UNREADABLE,"injected")
        var registered:File?=null
        val decoder=Decoder{metadata()}.apply{write={target->assertSame(registered,target);target.writeBytes(byteArrayOf(1));throw expected}}
        try { RawInputAdapter(decoder).load(request(temp.newFolder()){registered=it},header);fail("Injected failure") }
        catch(e:PhotoException){assertSame(expected,e)}
        assertNotNull(registered);assertEquals(1L,registered!!.length())
        // Lifecycle owner, not adapter, rolls back registered output on any Throwable.
        assertTrue(registered!!.delete())
    }
    @Test fun cachedByteMismatchIsRejectedBeforePreview()=runBlocking {
        val dir=temp.newFolder();val cached=File(dir,"bad.rgb").apply{writeBytes(byteArrayOf(1))}
        val decoder=Decoder{metadata()}
        try { RawInputAdapter(decoder).load(request(dir,cached),header);fail("Length mismatch") }
        catch(e:PhotoException){assertEquals(PhotoException.Reason.UNREADABLE,e.reason);assertEquals(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_52dca03550a9),e.message)}
        assertEquals(1,decoder.probes);assertEquals(0,decoder.decodes);assertTrue(cached.isFile)
    }
    @Test fun cancelledCachedImportNeverPublishesPreviewOrDeletesBorrowedSpool()=runBlocking {
        val dir=temp.newFolder();val cached=File(dir,"cached.rgb").apply{writeBytes(ByteArray(768))}
        val job=kotlinx.coroutines.Job().apply{cancel()}
        val decoder=Decoder{metadata()}
        try { RawInputAdapter(decoder).load(request(dir,cached).copy(job=job),header);fail("Cancelled") }
        catch(_:kotlinx.coroutines.CancellationException){}
        assertEquals(0,decoder.decodes);assertTrue(cached.isFile);assertEquals(768L,cached.length())
    }
    @Test fun invalidNativeMetadataIsNotTrustedEvenWithCachedSpool()=runBlocking {
        val dir=temp.newFolder();val cached=File(dir,"cached.rgb").apply{writeBytes(ByteArray(768))}
        val decoder=Decoder{metadata().put("outputOrientation",6)}
        try { RawInputAdapter(decoder).load(request(dir,cached),header);fail("Invalid native contract") }
        catch(e:PhotoException){assertEquals(PhotoException.Reason.UNREADABLE,e.reason);assertEquals(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5f37317ec18e),e.message);assertNotNull(e.cause)}
        assertEquals(1,decoder.probes);assertEquals(0,decoder.decodes)
    }
    @Test fun cameraRawFormatsUseNativeIdentityDespiteMisleadingJpegName()=runBlocking {
        for((format,mime) in mapOf("NEF" to "image/x-nikon-nef", "CR2" to "image/x-canon-cr2", "ARW" to "image/x-sony-arw", "RW2" to "image/x-panasonic-rw2", "CR3" to "image/x-canon-cr3", "ORF" to "image/x-olympus-orf", "PEF" to "image/x-pentax-pef", "RAF" to "image/x-fuji-raf")) {
            val decoder=Decoder{metadata().put("format",format)}
            val loaded=RawInputAdapter(decoder).load(request(temp.newFolder()),header)
            try {
                assertEquals(mime,loaded.source.mimeType)
                assertEquals(1,decoder.decodes);assertEquals(0,decoder.probes)
                assertEquals(8,loaded.source.originalWidth);assertEquals(8,loaded.source.originalHeight)
                assertEquals(768L,loaded.source.linearFile!!.length())
            }finally{loaded.preview.recycle()}
        }
    }
    @Test fun unsupportedNativeIdentityNeverFallsBackToPreview()=runBlocking {
        for(format in listOf("TIFF","CR3_PRIVATE","JPEG","NEF_PRIVATE","nef")) {
            val decoder=Decoder{metadata().put("format",format)}
            try { RawInputAdapter(decoder).load(request(temp.newFolder()),header);fail("Unsupported identity $format") }
            catch(e:PhotoException){assertEquals(PhotoException.Reason.UNREADABLE,e.reason)}
            assertEquals(1,decoder.decodes)
        }
    }
    @Test fun rafSignatureRoutesToNativeAndTruncatedSignatureDoesNot()=runBlocking {
        val bytes="FUJIFILMCCD-RAW ".toByteArray(Charsets.US_ASCII)
        val raf=PhotoInputHeader(bytes,bytes.size)
        val decoder=Decoder{metadata().put("format","RAF")}
        val adapter=RawInputAdapter(decoder)
        assertTrue(adapter.accepts(raf))
        assertFalse(adapter.accepts(PhotoInputHeader(bytes,bytes.size-1)))
        val selected=PhotoInputAdapters(listOf(adapter)).select(raf)
        assertSame(adapter,selected)
        val loaded=selected.load(request(temp.newFolder()),raf)
        try { assertEquals("image/x-fuji-raf",loaded.source.mimeType);assertEquals(1,decoder.decodes) }
        finally { loaded.preview.recycle() }
    }
    @Test fun classicTiffRoutingIsDisjointFromRasterAndHeicHeaders() {
        val adapter=RawInputAdapter(Decoder{metadata()})
        assertTrue(adapter.accepts(header))
        assertTrue(adapter.accepts(PhotoInputHeader(byteArrayOf(77,77,0,42),4)))
        for(bytes in listOf(byteArrayOf(-1,-40,-1),byteArrayOf(73,73,43,0),byteArrayOf(0,0,0,24,102,116,121,112,104,101,105,99)))
            assertFalse(adapter.accepts(PhotoInputHeader(bytes,bytes.size)))
    }

}
