package dev.filmroom.io

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.OperationTrace
import dev.filmroom.diagnostics.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/** Explicit input-stream fault injection, NOT a real provider, Binder or pipe test.
 * Separate registered-ContentProvider path coverage lives in ProviderImportHostTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ImportStreamFailureHostTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun files()=File(app.cacheDir,"photo-sources").listFiles().orEmpty().map{it.name}.toSet()
    private suspend fun fail(stream:InputStream):PhotoException {
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val uri=Uri.parse("content://filmroom.injected-stream/private.ARW")
        shadowOf(app.contentResolver).registerInputStream(uri,stream)
        val before=files()
        try{PhotoRepository(app).import(uri,256,ImportOrigin.FILES_PICKER);error("expected failure")}
        catch(e:PhotoException){assertEquals(before,files());return e}
    }
    @Test fun midReadIOExceptionPreservesByteCountAndNeverLooksLikeCodecFailure()=runBlocking {
        val stream=object:InputStream(){
            var copied=0;var closed=false
            override fun read():Int=error("bulk read expected")
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                if(length==0)return 0
                if(copied>=65536)throw IOException("private injected stream error")
                val count=minOf(length,65536-copied)
                java.util.Arrays.fill(bytes,offset,offset+count,42.toByte());copied+=count;return count
            }
            override fun close(){closed=true}
        }
        val error=fail(stream)
        assertTrue(stream.closed)
        assertEquals(ImportPhase.SOURCE_READ,error.importDiagnostic!!.phase)
        assertEquals(ImportFailure.IO,error.importDiagnostic!!.failure)
        assertEquals(65536L,error.importDiagnostic!!.copiedBytes)
        assertNull(error.codecCode)
        assertFalse(OperationTrace.failureInfo(error,1).fields().toString().contains("private"))
    }
    @Test fun sourceCloseFailureIsDistinctFromReadFailure()=runBlocking {
        val stream=object:InputStream(){
            override fun read():Int=-1
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int=-1
            override fun close(){throw IOException("private close error")}
        }
        val error=fail(stream)
        assertEquals(ImportPhase.SOURCE_CLOSE,error.importDiagnostic!!.phase)
        assertEquals(ImportFailure.IO,error.importDiagnostic!!.failure)
        assertEquals(0L,error.importDiagnostic!!.copiedBytes)
    }
}
