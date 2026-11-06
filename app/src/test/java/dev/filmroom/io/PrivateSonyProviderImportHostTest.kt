package dev.filmroom.io

import android.app.ActivityManager
import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.codec.NativeCodecBridge
import dev.filmroom.diagnostics.*
import dev.filmroom.io.formats.PhotoFormat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs

/** Opt-in actual private original, production repository, real Linux JNI, registered host provider.
 * Separate class/fork avoids loading one JNI library into API29 and API35 sandbox classloaders.
 * The bitmap is a host framework object; decoded native spool bytes are checked exactly.
 * Origin enum values do not exercise actual picker UI, URI grants, Binder or realme provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PrivateSonyProviderImportHostTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun setup(file:File):Pair<ProviderImportHostTest.Provider,Uri> {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            availMem=1024L*1024*1024;threshold=128L*1024*1024;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val provider=ProviderImportHostTest.Provider().apply {
            source=file
            attachInfo(app,ProviderInfo().apply{authority="filmroom.provider.host";exported=false})
        }
        ShadowContentResolver.registerProviderInternal("filmroom.provider.host",provider)
        return provider to Uri.parse("content://filmroom.provider.host/private.ARW")
    }
    private fun privateFiles()=File(app.cacheDir,"photo-sources").listFiles().orEmpty().map{it.name}.toSet()
    private fun hash(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;hash.update(bytes,0,n)} }
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    @Test
    fun originalSonyRawViaRealProviderAndJniPreservesOriginMetadata()=runBlocking {
        val privatePath=System.getenv("FILMROOM_PRIVATE_RAW")
        assumeTrue("Opt-in private RAW and Linux JNI test",privatePath != null)
        val source=File(checkNotNull(privatePath))
        assertTrue(NativeCodecBridge.available)
        assertEquals(49690112L,source.length())
        assertEquals("83b20355847b867f976c685d7d0934c6982890f928cd97b23982bd56213c86d1",hash(source))
        for(origin in listOf(ImportOrigin.FILES_PICKER,ImportOrigin.PHOTO_PICKER)) {
            val(provider,uri)=setup(source);provider.displayName=null;provider.mime=null
            val before=privateFiles();val info=mutableListOf<ImportDiagnosticInfo>();val repo=PhotoRepository(app)
            val loaded=repo.import(uri,256,origin){info+=it}
            try {
                assertTrue(provider.openCalls>0);assertEquals(hash(source),hash(loaded.source.localFile))
                assertEquals(4000,loaded.source.originalWidth);assertEquals(6000,loaded.source.originalHeight)
                assertEquals(288000000L,loaded.source.linearFile!!.length())
                assertEquals("bda2a4e9d9ce2b25e332e6f46f030f30d2b26acf82a12efbb535881296c7ebf4",hash(loaded.source.linearFile!!))
                assertEquals(ImportRoute.RAW,info.last().route);assertEquals(PhotoFormat.TIFF,info.last().format)
                assertEquals(origin,info.last().origin);assertEquals(source.length(),info.last().copiedBytes)
            }finally{loaded.preview.recycle();repo.discard(loaded.source)}
            assertEquals(before,privateFiles())
        }
    }
}
