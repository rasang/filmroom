package dev.filmroom.livephoto

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.filmroom.livephoto.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires a real Android runtime with an H.264 encoder. Not executed in the source-delivery environment. */
@RunWith(AndroidJUnit4::class)
class ConversionInstrumentedTest {
    @Test fun videoToGoogleMotionPhoto_hasDecodableCoverAndEmbeddedVideo() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val file=File(context.cacheDir,"test-${UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("clip_sdr.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val sdk=LivePhotoSdk(context)
        var asset:PackagedAsset?=null
        try {
            val result=sdk.convert(VideoToLiveRequest(Uri.fromFile(file),LivePhotoProfile.GOOGLE,durationMs=800,coverOffsetMs=200,saveToGallery=false))
            asset=result.asset
            assertTrue(result.publishedUris.isEmpty())
            assertFalse(result.galleryConfirmed)
            assertTrue(LivePhotoVerifier.verify(result.asset).videoRange.length>0)
            val bitmap=BitmapFactory.decodeFile(result.asset.image.file.absolutePath)
            assertNotNull(bitmap); assertTrue(bitmap.width>0); bitmap.recycle()
            val extracted=File(context.cacheDir,"extracted-${UUID.randomUUID()}.mp4")
            try {
                LivePhotoVerifier.extractVideo(result.asset,extracted)
                assertEquals("video/avc",MediaProbe.normalized(extracted).mime)
            } finally { extracted.delete() }
        } finally { asset?.let { sdk.deleteLocalAsset(it) }; file.delete() }
    }
}
