package dev.filmroom.export

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupReinstallAccessHostTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val media=Uri.parse("content://media/external_primary/images/media/123")
    private val document=Uri.parse("content://docs/document/picked-photo")
    private fun job()=ExportJob(ExportJobStore.newId(),123,EditorState(),32,24,ExportFormat.JPEG,95,ExportJobState.COMPLETED,
        mediaUri=media.toString(),outputName="Filmroom-123.jpg",originalUri="content://picker/photo/3")
    private fun png(width:Int=32,height:Int=24)=ByteArrayOutputStream().also{out->Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).let{b->b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle()}}.toByteArray()
    private class Provider:ContentProvider() {
        var permitted=true;var present=true;var name="Filmroom-123.jpg";var owner:String?=null;var path="Pictures/暗房/"
        override fun onCreate()=true
        override fun query(uri:Uri,projection:Array<out String>?,s:String?,args:Array<out String>?,sort:String?):android.database.Cursor {
            if(!permitted)throw SecurityException()
            val columns=projection ?: emptyArray()
            return MatrixCursor(columns).apply { if(present)addRow(columns.map { when(it){"_display_name"->name;"relative_path"->path;"owner_package_name"->owner;else->null} }.toTypedArray()) }
        }
        override fun getType(uri:Uri)="image/png"
        override fun insert(uri:Uri,v:ContentValues?):Uri?=error("no mutation")
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=error("no mutation")
        override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("no mutation")
    }
    private fun provider(authority:String,uri:Uri):Provider=Provider().also {
        it.attachInfo(app,android.content.pm.ProviderInfo().apply{this.authority=authority})
        ShadowContentResolver.registerProviderInternal(authority,it)
        val bytes=png();shadowOf(app.contentResolver).registerInputStreamSupplier(uri){bytes.inputStream()}
    }
    @Test fun reinstallOwnerCanBeMissingButAccessMustBeGrantedAndIdentityMustMatch() {
        val p=provider("media",media);p.permitted=false
        assertEquals(BackupMediaLink.Status.NO_PERMISSION,BackupMediaLink.inspect(app,job()))
        p.permitted=true;assertTrue(BackupMediaLink.readable(app,job()))
        p.path="Pictures/Other/";assertEquals(BackupMediaLink.Status.IDENTITY_MISMATCH,BackupMediaLink.inspect(app,job()))
        app.contentResolver.takePersistableUriPermission(media,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(BackupMediaLink.selectedMatches(app,job(),media));assertTrue(BackupMediaLink.readable(app,job()))
        app.contentResolver.releasePersistableUriPermission(media,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        p.path="Pictures/暗房/"
        p.name="different.png";assertFalse(BackupMediaLink.readable(app,job()));p.name="Filmroom-123.jpg"
        val wrong=png(40,30);shadowOf(app.contentResolver).registerInputStreamSupplier(media){wrong.inputStream()}
        assertEquals(BackupMediaLink.Status.IDENTITY_MISMATCH,BackupMediaLink.inspect(app,job()))
        p.present=false;assertEquals(BackupMediaLink.Status.MISSING,BackupMediaLink.inspect(app,job()))
    }
    @Test fun arbitraryProviderRequiresExactPersistedGrantAndExplicitPickerMatch() {
        val p=provider("docs",document);val row=job().copy(mediaUri=document.toString())
        assertEquals(BackupMediaLink.Status.NO_PERMISSION,BackupMediaLink.inspect(app,row))
        assertFalse(BackupMediaLink.selectedMatches(app,row,document))
        app.contentResolver.takePersistableUriPermission(document,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(BackupMediaLink.readable(app,row));assertTrue(BackupMediaLink.selectedMatches(app,row,document))
        p.name="other.jpg";assertFalse(BackupMediaLink.selectedMatches(app,row,document))
        assertTrue("legacy record may be explicitly confirmed without an old filename",BackupMediaLink.selectedMatches(app,row.copy(outputName=null),document))
        app.contentResolver.releasePersistableUriPermission(document,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(BackupMediaLink.readable(app,row))
    }
    @Test fun relinkIsAtomicArchiveOnlyPreservesSourceAndRejectsStaleCallback() {
        val source=job();val root=File(app.cacheDir,"relink-${System.nanoTime()}");val store=ExportJobStore(root)
        store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(source)))
        assertTrue(store.relinkArchivedPhoto(source.id,source.mediaUri,document.toString(),source.outputName!!))
        val restored=ExportJobStore(root).get(source.id)!!
        assertEquals(document.toString(),restored.mediaUri);assertEquals(source.originalUri,restored.originalUri);assertFalse(restored.canRetry)
        assertFalse(store.relinkArchivedPhoto(source.id,source.mediaUri,document.toString(),source.outputName!!))
        val live=source.copy(id=ExportJobStore.newId());store.update(live)
        assertFalse(store.relinkArchivedPhoto(live.id,live.mediaUri,document.toString(),live.outputName!!))
    }
}
