package dev.filmroom.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class PackedSpectralLutHostTest {
    @Test fun actualAssetManagerAndFilmEngineInstallOnlyOriginalNpy()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assertFalse(context.assets.list("engine-data/luts/spectral_upsampling")!!.contains(PackedSpectralLut.ORIGINAL_NAME))
        assertTrue(context.assets.list("engine-data/luts/spectral_upsampling")!!.contains(PackedSpectralLut.PACKED_NAME))
        val engine=FilmEngine(context)
        val method=FilmEngine::class.java.getDeclaredMethod("copyData",Continuation::class.java).apply{isAccessible=true}
        val root=suspendCoroutine<File>{continuation->val result=method.invoke(engine,continuation);if(result!==COROUTINE_SUSPENDED)continuation.resume(result as File)}
        val restored=File(root,"luts/spectral_upsampling/${PackedSpectralLut.ORIGINAL_NAME}")
        assertArrayEquals(File(System.getProperty("filmroom.lut.original")!!).readBytes(),restored.readBytes())
        assertFalse(File(restored.parentFile,PackedSpectralLut.PACKED_NAME).exists())
        assertFalse(root.walkTopDown().any{it.name.endsWith(".tmp")})
    }
}
