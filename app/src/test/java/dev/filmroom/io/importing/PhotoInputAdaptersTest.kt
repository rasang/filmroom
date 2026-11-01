package dev.filmroom.io.importing

import dev.filmroom.io.*
import dev.filmroom.io.formats.PhotoFormat
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Host contract tests: no platform decoder or HEVC/device capability claims. */
class PhotoInputAdaptersTest {
    private fun header(vararg bytes:Int)=PhotoInputHeader(bytes.map{it.toByte()}.toByteArray(),bytes.size)
    private class Adapter(private val accepts: (PhotoInputHeader)->Boolean):PhotoInputAdapter {
        var calls=0
        override fun accepts(header:PhotoInputHeader)=accepts.invoke(header)
        override suspend fun load(request:PhotoImportRequest,header:PhotoInputHeader):LoadedPhoto {
            calls++;throw Failure()
        }
    }
    private class Failure:Exception()
    @Test fun newFormatNeedsOnlyAnAdapterAndRegistration(){
        val jpeg=Adapter{it.rasterMime=="image/jpeg"}
        val custom=Adapter{it.count==4 && it.bytes.contentEquals(byteArrayOf(70,65,75,69))}
        val before=PhotoInputAdapters(listOf(jpeg))
        val fake=header(70,65,75,69)
        try { before.select(fake);fail("Unsupported before registration") }
        catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
        val after=PhotoInputAdapters(listOf(jpeg,custom))
        assertSame(custom,after.select(fake));assertSame(jpeg,after.select(header(255,216,255)))
    }
    @Test fun ambiguousRegistrationFailsBeforeEitherDecoderRuns(){
        val first=Adapter{true};val second=Adapter{true}
        try { PhotoInputAdapters(listOf(first,second)).select(header(255,216,255));fail("Ambiguous") }
        catch(_:IllegalStateException){}
        assertEquals(0,first.calls);assertEquals(0,second.calls)
    }
    @Test fun decoderFailureDoesNotFallBackOrChangeTheError()=runBlocking {
        val jpeg=Adapter{it.rasterMime=="image/jpeg"};val other=Adapter{it.isClassicTiff}
        val request=PhotoImportRequest(File("renamed.raw"),"renamed.raw",1280,EmptyCoroutineContext,null,true,{},File("."),PhotoMetadata.unavailable())
        try { PhotoInputAdapters(listOf(jpeg,other)).load(request,header(255,216,255));fail("Injected failure") }
        catch(_:Failure){}
        assertEquals(1,jpeg.calls);assertEquals(0,other.calls)
    }
    @Test fun dngRoutingUsesClassicTiffContentNotNameOrContainerOnlyRecognition(){
        assertTrue(header(73,73,42,0).isClassicTiff)
        assertTrue(header(77,77,0,42).isClassicTiff)
        assertFalse(header(73,73,43,0).isClassicTiff)
        assertFalse(header(77,77,0,43).isClassicTiff)
        assertFalse(header(73,73,42).isClassicTiff)
        assertEquals(PhotoFormat.TIFF,header(73,73,42,0,0,0,0,0).identified.format)
    }
    @Test fun probeRejectsInvalidCountsBeforeIndexing(){
        for(count in listOf(-1,5)) {
            try { PhotoInputHeader(byteArrayOf(73,73,42,0),count);fail("Invalid prefix count") }
            catch(_:IllegalArgumentException){}
        }
    }
    @Test fun oversizedPrefixCannotExpandProbeBudget(){
        try { PhotoInputHeader(ByteArray(65537),65537);fail("Prefix exceeds budget") }
        catch(_:IllegalArgumentException){}
    }
    @Test fun registryCopiesRegistrationList(){
        val jpeg=Adapter{it.rasterMime=="image/jpeg"};val list=mutableListOf<PhotoInputAdapter>(jpeg)
        val registry=PhotoInputAdapters(list);list.clear()
        assertSame(jpeg,registry.select(header(255,216,255)))
    }
}
