package dev.filmroom.effects

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.diagnostics.*
import dev.filmroom.livephoto.core.Vivo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class EffectMotionPairIntegrationTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private fun box(t:String,b:ByteArray)=ByteBuffer.allocate(8+b.size).putInt(8+b.size).put(t.toByteArray()).put(b).array()
    private fun movie()=box("ftyp","isom0000".toByteArray())+box("moov",box("trak",box("mdia",box("hdlr",ByteArray(8)+"vide".toByteArray()))))+box("mdat",byteArrayOf(1,2,3))
    private fun jpeg()=byteArrayOf(-1,-40,-1,-64,0,11,8,0,1,0,1,1,1,17,0,-1,-38,0,8,1,1,0,0,63,0,1,2,3,-1,-39)
    private fun fixture(block:(String,Uri,Uri)->Unit) {
        val key=System.nanoTime().toString()
        val image=Uri.parse("content://fixture/$key/image")
        val video=Uri.parse("content://fixture/$key/video")
        val wrong=Uri.parse("content://fixture/$key/wrong")
        val id="1234567890123000000000000000"
        fun tail(id:String)=Vivo.tail(linkedMapOf(Vivo.ID_KEY to id,"version" to 2014L))
        shadowOf(context.contentResolver).registerInputStreamSupplier(image){(jpeg()+tail(id)).inputStream()}
        shadowOf(context.contentResolver).registerInputStreamSupplier(video){(movie()+Vivo.uuid(tail(id))).inputStream()}
        shadowOf(context.contentResolver).registerInputStreamSupplier(wrong){(movie()+Vivo.uuid(tail("ffffffffffffffffffffffffffff"))).inputStream()}
        block(image.toString(),video,wrong)
    }
    @Test fun missingCompanionIsExplicitAndSelectedMatchingVideoFlowsToPreviewAndExport()=fixture { image,video,wrong->
        var status:MotionInputStatus?=null
        assertNull(EffectMotionInput.prepareWithDiagnostics(context,image){status=it.status})
        assertEquals(MotionInputStatus.PAIR_REQUIRED,status);assertTrue(EffectMotionInput.needsPair(image))
        assertFalse(EffectMotionInput.associate(context,image,wrong))
        assertTrue(EffectMotionInput.needsPair(image));assertTrue(EffectMotionPairAccess.candidates(context,image).isEmpty())
        assertTrue(EffectMotionInput.associate(context,image,video))
        // Validation alone is not a committed preview; export must remain blocked until reload.
        assertTrue(EffectMotionInput.needsPair(image))
        val extracted=requireNotNull(EffectMotionInput.prepareWithDiagnostics(context,image){status=it.status})
        try {assertArrayEquals(movie(),extracted.readBytes());assertEquals(MotionInputStatus.SUCCESS,status);assertTrue(EffectMotionInput.needsPair(image))} finally {extracted.delete()}
        assertFalse(File(context.cacheDir,"effect-motion").listFiles().orEmpty().any{it.name.startsWith("input-")||it.name.startsWith("pair-")})
    }
    @Test fun changedOrRevokedExplicitVideoDoesNotSilentlyUseStill()=fixture { image,video,wrong->
        EffectMotionPairAccess.rememberSelected(context,image,video){true}
        shadowOf(context.contentResolver).registerInputStreamSupplier(video){byteArrayOf(1,2,3).inputStream()}
        var status:MotionInputStatus?=null
        assertNull(EffectMotionInput.prepareWithDiagnostics(context,image){status=it.status})
        assertEquals(MotionInputStatus.PAIR_REQUIRED,status);assertTrue(EffectMotionInput.needsPair(image))
        assertEquals(listOf(video.toString()),EffectMotionPairAccess.candidates(context,image))
    }
    @Test fun activityCannotStartEitherExportDuringAssociation() {
        val activity=PhotoEffectActivity()
        fun set(name:String,value:Any)=PhotoEffectActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.set(activity,value)
        val bitmap=android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888)
        try {
            set("source",bitmap);set("result",bitmap);set("pairAssociating",true)
            // Without the entry guard these would touch uninitialized UI or start work.
            for(name in listOf("export","exportLivePhoto"))PhotoEffectActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(activity)
            assertFalse(PhotoEffectActivity::class.java.getDeclaredField("working").apply{isAccessible=true}.getBoolean(activity))
        } finally {bitmap.recycle()}
    }
    @Test fun activityCannotExportWhileVideoPermissionDialogIsPending() {
        val activity=PhotoEffectActivity()
        PhotoEffectActivity::class.java.getDeclaredField("pairPermissionSource").apply{isAccessible=true}.set(activity,"content://fixture/image")
        for(name in listOf("export","exportLivePhoto"))PhotoEffectActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(activity)
        assertFalse(PhotoEffectActivity::class.java.getDeclaredField("working").apply{isAccessible=true}.getBoolean(activity))
    }
    @Test fun cancelledCandidateCopyDoesNotRetainFiles()=fixture { image,video,_->
        EffectMotionPairAccess.rememberSelected(context,image,video){true}
        assertThrows(CancellationException::class.java){EffectMotionInput.prepareWithDiagnostics(context,image,{
            if(File(context.cacheDir,"effect-motion").listFiles().orEmpty().any{it.name.startsWith("pair-")})throw CancellationException()
        }){}}
        assertFalse(File(context.cacheDir,"effect-motion").listFiles().orEmpty().any{it.name.startsWith("input-")||it.name.startsWith("pair-")})
    }
}
