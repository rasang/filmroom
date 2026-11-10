package dev.filmroom.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.LoadedPhoto
import dev.filmroom.io.PhotoSource
import dev.filmroom.io.formats.PhotoFormat
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class EffectImageDecoderTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private fun bitmap()=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
    private fun loaded()=LoadedPhoto(PhotoSource(File(context.cacheDir,"unused-test-raw"),"test","image/x-adobe-dng",6000,4000,1),bitmap())

    @Test fun systemSuccessNeverProbesOrImportsEvenForSmallDecodedPreview()=runBlocking {
        val result=bitmap()
        EffectImageDecoder(context,systemRead={_,_,_,size->size(EffectExportSize(2,2));result},
            importRaw={_,_->error("must keep system success")},containerCheck={error("must not probe")}).use {
            assertSame(result,it.read("test",1600))
        }
        assertFalse(result.isRecycled);result.recycle()
    }
    @Test fun systemKeeps6144BudgetButNativeUsesRepositoryLimit()=runBlocking {
        val systemBitmap=bitmap()
        EffectImageDecoder(context,systemRead={_,edge,_,_->assertEquals(6144,edge);systemBitmap}).use {
            it.read("jpeg",6144).recycle()
        }
        val value=loaded()
        EffectImageDecoder(context,systemRead={_,_,_,_->throw IOException()},containerCheck={true},
            importRaw={_,edge->assertEquals(4096,edge);value},discardRaw={}).use {
            it.read("raw",6144).recycle()
        }
    }
    @Test fun confirmedRawFailureUsesNativeOnceForBoundsAndPixelsAndCleansSource()=runBlocking {
        var imports=0;var reads=0;var discards=0;var system=0
        val preview=loaded();val output=bitmap()
        EffectImageDecoder(context,systemRead={_,_,_,_->system++;throw IOException("unsupported")},
            containerCheck={true},importRaw={_,edge->imports++;assertEquals(1,edge);preview},
            decodeRaw={source,edge->reads++;assertSame(preview.source,source);assertEquals(1600,edge);output},
            discardRaw={discards++}).use {
            assertEquals(EffectExportSize(6000,4000),it.bounds("raw"))
            assertTrue(preview.preview.isRecycled)
            assertSame(output,it.read("raw",1600))
        }
        assertEquals(1,system);assertEquals(1,imports);assertEquals(1,reads);assertEquals(1,discards)
        assertFalse(output.isRecycled);output.recycle()
    }
    @Test fun nonRawIoFailureIsNotRetried()=runBlocking {
        val failure=IOException("broken jpeg")
        EffectImageDecoder(context,systemRead={_,_,_,_->throw failure},containerCheck={false},
            importRaw={_,_->error("not RAW")}).use {
            try { it.read("jpeg",1600);fail() } catch(e:IOException){assertSame(failure,e)}
        }
    }
    @Test fun noFallbackOnPermissionMissingMemoryOrCancellation()=runBlocking {
        for(failure in listOf(SecurityException(),FileNotFoundException(),OutOfMemoryError(),CancellationException(),
            IOException("wrapped",SecurityException()),IOException("wrapped",FileNotFoundException()),
            IOException("wrapped",OutOfMemoryError()),IOException("wrapped",CancellationException()))) {
            EffectImageDecoder(context,systemRead={_,_,_,_->throw failure},containerCheck={error("must not probe")},
                importRaw={_,_->error("must not import")}).use {
                try { it.read("raw",1600);fail() } catch(e:Throwable){assertSame(failure,e)}
            }
        }
    }
    @Test fun explicitlyDisabledFallbackPreservesFailure()=runBlocking {
        val failure=IOException()
        EffectImageDecoder(context,systemRead={_,_,_,_->throw failure},containerCheck={error("disabled")}).use {
            try { it.read("result",1600,allowRawFallback=false);fail() } catch(e:IOException){assertSame(failure,e)}
        }
    }
    @Test fun failureAfterImportDoesNotLeakPreviewOrSource()=runBlocking {
        val loaded=loaded();var discarded=0
        EffectImageDecoder(context,systemRead={_,_,_,_->throw IOException()},containerCheck={true},
            importRaw={_,_->loaded},discardRaw={discarded++}).use {
            try { it.read("raw",1600,onSize={throw IllegalStateException("consumer failed")});fail() }
            catch(_:IllegalStateException){}
        }
        assertTrue(loaded.preview.isRecycled);assertEquals(1,discarded)
    }
    @Test fun nativeUnsupportedIsPropagatedWithoutLoop()=runBlocking {
        var calls=0;val failure=IOException("native unsupported")
        EffectImageDecoder(context,systemRead={_,_,_,_->throw IOException()},containerCheck={true},
            importRaw={_,_->calls++;throw failure}).use {
            try { it.read("raw",1600);fail() } catch(e:IOException){assertEquals(failure.javaClass,e.javaClass);assertEquals(failure.message,e.message)}
        }
        assertEquals(1,calls)
    }
    @Test fun cancellationAfterNativeReturnReclaimsPreviewAndSource()=runBlocking {
        val value=loaded();var discarded=0
        EffectImageDecoder(context,systemRead={_,_,_,_->throw IOException()},containerCheck={true},
            importRaw={_,_->currentCoroutineContext().cancel();value},discardRaw={discarded++}).use { decoder ->
            val task=async { decoder.read("raw",1600) }
            task.join()
            assertTrue(task.isCancelled)
        }
        assertTrue(value.preview.isRecycled);assertEquals(1,discarded)
    }
    @Test fun exportCancelFlagInterruptsNativeCoroutine()=runBlocking {
        var cancelled=false;var importCancelled=false
        EffectImageDecoder(context,cancelled={cancelled},systemRead={_,_,_,_->throw IOException()},containerCheck={true},
            importRaw={_,_->
                cancelled=true
                try { delay(5000);error("must cancel") }
                finally { importCancelled=true }
            }).use { decoder ->
            try { decoder.read("raw",1600);fail() } catch(_:CancellationException){}
        }
        assertTrue(importCancelled)
    }
    @Test fun realPlatformRejectsUnsupportedRawBytesAndTriggersNativeRoute()=runBlocking {
        // A real TIFF-container header rejected by ImageDecoder; the native importer is injected.
        val file=File.createTempFile("effect-raw-",".bin",context.cacheDir)
        file.writeBytes(byteArrayOf(0x49,0x49,42,0,8,0,0,0,0,0))
        val value=loaded();var calls=0;var discarded=0
        try {
            EffectImageDecoder(context,
                // Robolectric cannot decode Android asset file descriptors. Use the same actual
                // ImageDecoder over bytes, while leaving signature reading on the real file URI.
                systemRead={_,_,_,_->ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes())))},
                importRaw={_,_->calls++;value},discardRaw={discarded++}).use { decoder ->
                val decoded=decoder.read(android.net.Uri.fromFile(file).toString(),1600)
                assertSame(value.preview,decoded);decoded.recycle()
            }
            assertEquals(1,calls);assertEquals(1,discarded)
        } finally { file.delete() }
    }
    @Test fun containerHintsAreBoundedAndDoNotTreatRasterAsRaw() {
        assertTrue(EffectImageDecoder.isRawContainer(PhotoFormat.TIFF))
        assertTrue(EffectImageDecoder.isRawContainer(PhotoFormat.DNG))
        assertTrue(EffectImageDecoder.isRawContainer(PhotoFormat.CAMERA_RAW))
        for(format in listOf(PhotoFormat.JPEG,PhotoFormat.PNG,PhotoFormat.HEIF,PhotoFormat.UNKNOWN))
            assertFalse(EffectImageDecoder.isRawContainer(format))
    }
}
