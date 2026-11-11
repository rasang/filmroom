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
    private fun fixture(version: Long = 2014L, block:(String,Uri,Uri)->Unit) {
        val key=System.nanoTime().toString()
        val image=Uri.parse("content://fixture/$key/image")
        val video=Uri.parse("content://fixture/$key/video")
        val wrong=Uri.parse("content://fixture/$key/wrong")
        val id="1234567890123000000000000000"
        fun tail(id:String)=Vivo.tail(linkedMapOf(Vivo.ID_KEY to id,"version" to version))
        shadowOf(context.contentResolver).registerInputStreamSupplier(image){(jpeg()+(if(version == 2100L) jpeg() else byteArrayOf())+tail(id)).inputStream()}
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
    @Test fun native2100ImageEntersPairRequiredAndRecoversAfterVideoSelection()=fixture(2100L) { image,video,wrong->
        var status:MotionInputStatus?=null
        assertNull(EffectMotionInput.prepareWithDiagnostics(context,image){status=it.status})
        assertEquals(MotionInputStatus.PAIR_REQUIRED,status)
        assertTrue(EffectMotionInput.needsPair(image))
        assertFalse(EffectMotionInput.associate(context,image,wrong))
        assertTrue(EffectMotionInput.associate(context,image,video))
        val extracted=requireNotNull(EffectMotionInput.prepareWithDiagnostics(context,image){status=it.status})
        try { assertEquals(MotionInputStatus.SUCCESS,status); assertArrayEquals(movie(),extracted.readBytes()) }
        finally { extracted.delete() }
    }
    @Test fun changedOrRevokedExplicitVideoDoesNotSilentlyUseStill()=fixture { image,video,wrong->
        EffectMotionPairAccess.rememberSelected(context,image,video){true}
        shadowOf(context.contentResolver).registerInputStreamSupplier(video){byteArrayOf(1,2,3).inputStream()}
        var status:MotionInputStatus?=null
        assertNull(EffectMotionInput.prepareWithDiagnostics(context,image){status=it.status})
        assertEquals(MotionInputStatus.PAIR_REQUIRED,status);assertTrue(EffectMotionInput.needsPair(image))
        assertEquals(listOf(video.toString()),EffectMotionPairAccess.candidates(context,image))
    }
    @Test fun failedSavedSelectionFallsBackToAuthorizedMediaStoreAndReportsNoIdentifiers()=fixture { image,video,wrong->
        val provider=object:android.content.ContentProvider() {
            override fun onCreate()=true
            override fun query(uri:Uri, projection:Array<out String>?, selection:String?, args:Array<out String>?, order:String?):android.database.Cursor =
                if(uri.authority=="fixture") android.database.MatrixCursor(arrayOf("_display_name")).apply {addRow(arrayOf("PRIVATE.jpg"))}
                else android.database.MatrixCursor(arrayOf("_id")).apply {addRow(arrayOf(33L))}
            override fun getType(uri:Uri):String?=null
            override fun insert(uri:Uri, values:android.content.ContentValues?):Uri?=null
            override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
            override fun update(uri:Uri,values:android.content.ContentValues?,selection:String?,args:Array<out String>?)=0
        }
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply {authority="fixture"})
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("fixture",provider)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider)
        val automatic=Uri.parse("content://media/external/video/media/33")
        val bytes=context.contentResolver.openInputStream(video)!!.use {it.readBytes()}
        shadowOf(context.contentResolver).registerInputStreamSupplier(automatic){bytes.inputStream()}
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.READ_MEDIA_VIDEO)
        EffectMotionPairAccess.rememberSelected(context,image,wrong){true}
        var diagnostic:MotionInputDiagnosticInfo?=null
        val extracted=requireNotNull(EffectMotionInput.prepareWithDiagnostics(context,image){diagnostic=it})
        try {
            assertArrayEquals(movie(),extracted.readBytes())
            val d=requireNotNull(diagnostic)
            assertEquals(PairMatchStatus.MATCHED,d.pairMatch)
            assertEquals(PairPermission.FULL,d.pairPermission)
            assertEquals(2,d.pairCandidates)
            assertEquals(1,d.pairRejected)
            assertTrue(d.pairSavedFallback)
            assertFalse(d.fields().toString().contains("PRIVATE"))
            assertFalse(d.fields().toString().contains("content://"))
            assertEquals(d,MotionInputDiagnosticInfo.parse(d.fields().mapValues {it.value.toString()}))
            shadowOf(context.contentResolver).registerInputStreamSupplier(wrong){throw SecurityException("private uri must not enter logs")}
            val retry=requireNotNull(EffectMotionInput.prepareWithDiagnostics(context,image){diagnostic=it})
            retry.delete()
            assertEquals(PairMatchStatus.MATCHED,diagnostic!!.pairMatch)
            assertEquals(1,diagnostic!!.pairReadFailures)
            assertTrue(diagnostic!!.pairSavedFallback)
            assertFalse(diagnostic!!.fields().toString().contains("private uri"))
        } finally { extracted.delete(); shadowOf(context as Application).denyPermissions(android.Manifest.permission.READ_MEDIA_VIDEO) }
    }
    @Test fun invalidSavedSelectionWithoutPermissionStaysPairRequiredAndExplainsFailure()=fixture { image,_,wrong->
        shadowOf(context as Application).denyPermissions(android.Manifest.permission.READ_MEDIA_VIDEO,android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        EffectMotionPairAccess.rememberSelected(context,image,wrong){true}
        var diagnostic:MotionInputDiagnosticInfo?=null
        assertNull(EffectMotionInput.prepareWithDiagnostics(context,image){diagnostic=it})
        val d=requireNotNull(diagnostic)
        assertEquals(MotionInputStatus.PAIR_REQUIRED,d.status)
        assertEquals(PairLookupStatus.NO_PERMISSION,d.pairLookup)
        assertEquals(PairMatchStatus.INVALID_PAIR,d.pairMatch)
        assertEquals(1,d.pairRejected)
        assertTrue(d.pairSavedFallback)
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
