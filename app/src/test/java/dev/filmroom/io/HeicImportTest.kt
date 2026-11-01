package dev.filmroom.io

import dev.filmroom.engine.NativeMemorySnapshot
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Pixel backend is an explicit substitute. These tests do NOT execute Android HEVC decoding. */
class HeicImportTest {
    private val plentiful = { NativeMemorySnapshot(8L*1024*1024*1024, 128L*1024*1024, false) }
    private fun info(w:Int=3,h:Int=2,rotation:Int=0,pixels:Long=w.toLong()*h)=HeicContainer.Info(w,h,rotation,pixels,pixels)
    private class Backend(var supported:Boolean=true):HeicBackend {
        var calls=0;var closed=false;var wrongSize=false;var failRow=false;var cancelJob:Job?=null
        override fun supported()=supported
        override fun decode(file:File,info:HeicContainer.Info,job:CoroutineContext):HeicPixels {
            calls++
            return object:HeicPixels {
                override val width=info.displayWidth+(if(wrongSize)1 else 0)
                override val height=info.displayHeight
                override fun readRow(y:Int,argb:IntArray){
                    if(failRow&&y==1)throw IOException("injected row failure")
                    argb.fill(if(y==0)0xffff0000.toInt() else 0x800000ff.toInt())
                    cancelJob?.cancel()
                }
                override fun close(){closed=true}
            }
        }
    }
    private suspend fun withFiles(block:suspend(File,File)->Unit){
        val root=kotlin.io.path.createTempDirectory("heic-test-").toFile()
        try{val source=File(root,"source.heic");source.writeBytes(byteArrayOf(1,2,3));block(source,File(root,"canonical.rgb"));assertArrayEquals(byteArrayOf(1,2,3),source.readBytes())}
        finally{root.deleteRecursively()}
    }
    @Test fun originalDimensionsAndWhiteAlphaAreWrittenOnceAsLinearFloat()=runBlocking {
        withFiles { source,target ->
            val backend=Backend();HeicImport(plentiful,backend).write(source,info(),target,coroutineContext)
            assertEquals(1,backend.calls);assertTrue(backend.closed);assertEquals(3L*2*12,target.length())
            val floats=ByteBuffer.wrap(target.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(3){assertEquals(1f,floats.float,0f);assertEquals(0f,floats.float,0f);assertEquals(0f,floats.float,0f)}
            repeat(3){assertEquals(LinearRgb.srgb8ToLinear[127],floats.float,0f);assertEquals(LinearRgb.srgb8ToLinear[127],floats.float,0f);assertEquals(1f,floats.float,0f)}
        }
    }
    @Test fun rotationUsesBackendDisplayCoordinatesWithoutSecondExifTransform()=runBlocking {
        withFiles { source,target ->
            val backend=Backend();HeicImport(plentiful,backend).write(source,info(rotation=90),target,coroutineContext)
            assertEquals(72L,target.length());assertTrue(backend.closed)
            val data=ByteBuffer.wrap(target.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            assertEquals(1f,data[0],0f);assertEquals(1f,data[3],0f)
            assertEquals(LinearRgb.srgb8ToLinear[127],data[6],0f) // Row two starts at display width 2, not encoded width 3.
        }
    }
    @Test fun unsupportedDeviceDoesNotDecodeOrCreateWorkFile()=runBlocking {
        withFiles { source,target -> val backend=Backend(false)
            try{HeicImport(plentiful,backend).write(source,info(),target,coroutineContext);fail()}catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
            assertEquals(0,backend.calls);assertFalse(target.exists())
        }
    }
    @Test fun lowMemoryFailsBeforePlatformDecodeAndNeverDownsizes()=runBlocking {
        withFiles { source,target -> val backend=Backend()
            try{HeicImport({NativeMemorySnapshot(128L*1024*1024,0,false)},backend).write(source,info(8192,6144),target,coroutineContext);fail()}catch(e:PhotoException){assertEquals(PhotoException.Reason.TOO_LARGE,e.reason)}
            assertEquals(0,backend.calls);assertFalse(target.exists())
        }
    }
    @Test fun memoryEstimateIncludesAllCodedTilesAndOriginalNotPreviewSize(){
        val n=50_331_648L
        assertEquals(64L*1024*1024+n*32,HeicMemoryPolicy.estimatedBytes(info(8192,6144)))
        assertEquals(64L*1024*1024+6*32+1000,HeicMemoryPolicy.estimatedBytes(info(),1000))
        try{HeicMemoryPolicy.estimatedBytes(info(8192,6144),PhotoLimits.MAX_COMPRESSED_BYTES);fail()}
        catch(e:PhotoException){assertEquals(PhotoException.Reason.TOO_LARGE,e.reason)}
        assertEquals(64L*1024*1024+1024L*1024*32,HeicMemoryPolicy.estimatedBytes(info(32,32,pixels=1024L*1024)))
        try{HeicMemoryPolicy.estimatedBytes(info(1,1,pixels=52_000_001));fail()}catch(e:PhotoException){assertEquals(PhotoException.Reason.TOO_LARGE,e.reason)}
    }
    @Test fun decoderSizeMismatchClosesPixelsAndNeverPublishes()=runBlocking {
        withFiles { source,target -> val backend=Backend().apply{wrongSize=true}
            try{HeicImport(plentiful,backend).write(source,info(),target,coroutineContext);fail()}catch(_:IOException){}
            assertTrue(backend.closed);assertFalse(target.exists())
        }
    }
    @Test fun rowFailureDeletesPartialAndReleasesDecoder()=runBlocking {
        withFiles { source,target -> val backend=Backend().apply{failRow=true}
            try{HeicImport(plentiful,backend).write(source,info(),target,coroutineContext);fail()}catch(_:IOException){}
            assertTrue(backend.closed);assertFalse(target.exists())
            // A subsequent independent call proves no retained admission lease/partial target.
            HeicImport(plentiful,Backend()).write(source,info(),target,coroutineContext);assertEquals(72L,target.length())
        }
    }
    @Test fun cancellationAfterRowClosesDecoderAndDeletesPartial()=runBlocking {
        withFiles { source,target -> val job=Job();val backend=Backend().apply{cancelJob=job}
            try{HeicImport(plentiful,backend).write(source,info(),target,job);fail()}catch(_:CancellationException){}
            assertTrue(backend.closed);assertFalse(target.exists())
        }
    }
    @Test fun preCancelledWorkNeverCallsBackend()=runBlocking {
        withFiles { source,target -> val job=Job().apply{cancel()};val backend=Backend()
            try{HeicImport(plentiful,backend).write(source,info(),target,job);fail()}catch(_:CancellationException){}
            assertEquals(0,backend.calls);assertFalse(target.exists())
        }
    }
    @Test fun existingOutputIsNeverOverwritten()=runBlocking {
        withFiles { source,target -> target.writeText("keep");val backend=Backend()
            try{HeicImport(plentiful,backend).write(source,info(),target,coroutineContext);fail()}catch(_:IllegalArgumentException){}
            assertEquals("keep",target.readText());assertEquals(0,backend.calls)
        }
    }
}
