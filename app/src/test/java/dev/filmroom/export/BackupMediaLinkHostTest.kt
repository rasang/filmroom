package dev.filmroom.export

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.res.AssetFileDescriptor
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class BackupMediaLinkHostTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private fun job()=ExportJob(ExportJobStore.newId(),1,EditorState(),32,24,ExportFormat.JPEG,95,ExportJobState.COMPLETED,
        mediaUri="content://media/external/images/media/123",outputName="Filmroom-123.jpg",originalUri="content://picker/photo/3")
    @Test fun linksSurviveBackupAndArchiveRestartWithoutExecutableInputs() {
        val dir=File(app.cacheDir,"links-${System.nanoTime()}"); val source=job()
        val rows=ExportHistoryBackup.exportRows(listOf(source));val store=ExportJobStore(dir)
        val document=dev.filmroom.backup.AppBackupCodec.Document(org.json.JSONObject(),emptyList(),rows)
        val output=java.io.ByteArrayOutputStream();dev.filmroom.backup.AppBackupCodec.writeZip(document,output)
        val decoded=dev.filmroom.backup.AppBackupCodec.read(output.toByteArray().inputStream())
        assertEquals(source.originalUri,decoded.history.getJSONObject(0).getString("originalUri"))
        assertEquals(source.mediaUri,decoded.history.getJSONObject(0).getString("mediaUri"))
        assertEquals(1,store.mergeBackupHistory(rows));val result=ExportJobStore(dir).get(source.id)!!
        assertEquals(source.mediaUri,result.mediaUri);assertEquals(source.originalUri,result.originalUri)
        assertEquals(source.outputName,result.outputName);assertFalse(result.inputReady);assertFalse(result.canRetry)
        assertNull(ExportJobStore(dir).next())
    }
    @Test fun oldRowsNeverInventLinksAndOriginalUrisAreMetadataOnly() {
        val rows=ExportHistoryBackup.exportRows(listOf(job()));listOf("mediaUri","outputName","originalUri").forEach{rows.getJSONObject(0).remove(it)}
        val restored=ExportHistoryBackup.validateRows(rows).single();assertNull(restored.mediaUri);assertNull(restored.originalUri)
        assertFalse(BackupMediaLink.validOutput("file:///tmp/photo.jpg"));assertFalse(BackupMediaLink.validOutput("content://media/external/images/media"))
        assertTrue(BackupMediaLink.validOutput("content://evil/a/1"));assertFalse(BackupMediaLink.validOriginal("https://example.com/a"))
    }
    @Test fun newerBackupCanFillMissingLegacyLinksWithoutReplacingLiveRows() {
        val dir=File(app.cacheDir,"upgrade-${System.nanoTime()}");val source=job()
        val oldRows=ExportHistoryBackup.exportRows(listOf(source));listOf("mediaUri","outputName","originalUri").forEach{oldRows.getJSONObject(0).remove(it)}
        val store=ExportJobStore(dir);store.mergeBackupHistory(oldRows)
        assertEquals(0,store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(source)),validateOnly=true));assertNull(store.get(source.id)!!.mediaUri)
        store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(source)))
        assertEquals(source.mediaUri,store.get(source.id)!!.mediaUri);assertEquals(source.originalUri,store.get(source.id)!!.originalUri)
        assertEquals(0,store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(source))))
    }
    @Test fun mediaMustStillBeReadableAndMatchNameAndOwner() {
        var name="Filmroom-123.jpg";var owner=app.packageName;var deny=false;var missing=false
        val bytes=File(app.cacheDir,"media-link-image").apply{writeText("image")}
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):android.database.Cursor {
                if(deny)throw SecurityException()
                return MatrixCursor(projection).apply{if(!missing)addRow(projection!!.map{when(it){"_display_name"->name;"owner_package_name"->owner;"relative_path"->"Pictures/Other/";else->null}}.toTypedArray())}
            }
            override fun openAssetFile(uri:Uri,mode:String):AssetFileDescriptor {if(deny)throw SecurityException();return AssetFileDescriptor(ParcelFileDescriptor.open(bytes,ParcelFileDescriptor.MODE_READ_ONLY),0,bytes.length())}
            override fun getType(uri:Uri)="image/jpeg"
            override fun insert(uri:Uri,values:ContentValues?):Uri?=error("No mutations")
            override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("No mutations")
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?):Int=error("No mutations")
        }
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply {authority="media"})
        ShadowContentResolver.registerProviderInternal("media",provider)
        val png=java.io.ByteArrayOutputStream().also{out->android.graphics.Bitmap.createBitmap(32,24,android.graphics.Bitmap.Config.ARGB_8888).let{it.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);it.recycle()}}.toByteArray()
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStreamSupplier(Uri.parse(job().mediaUri)){png.inputStream()}
        assertTrue(BackupMediaLink.readable(app,job()))
        name="different.jpg";assertFalse(BackupMediaLink.readable(app,job()));name="Filmroom-123.jpg"
        owner="other.package";assertFalse(BackupMediaLink.readable(app,job()));owner=app.packageName
        deny=true;assertFalse(BackupMediaLink.readable(app,job()));deny=false
        missing=true;assertFalse(BackupMediaLink.readable(app,job()))
    }
}
