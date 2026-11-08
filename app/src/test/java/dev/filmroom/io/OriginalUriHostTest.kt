package dev.filmroom.io

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportJobStore
import dev.filmroom.model.EditorState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalUriHostTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    @Test fun originalUriIsMetadataNotAFileOrNetworkLocator() {
        assertEquals("content://media/external/images/media/123",OriginalPhotoUri.sanitize("content://media/external/images/media/123"))
        listOf("file:///data/user/0/private", "https://example.com/a", "content:///no-authority", "content://user@media/item", "content://media/item#fragment", "", "content://media/\nitem").forEach { assertNull(it,OriginalPhotoUri.sanitize(it)) }
    }
    @Test fun importReopenAndJobRestartPreserveOriginalUriWithoutOpeningItAgain()=runBlocking {
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val uri=Uri.parse("content://source-uri-test/image/1")
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        val bytes=java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray();bitmap.recycle()
        shadowOf(app.contentResolver).registerInputStream(uri,bytes.inputStream())
        val repo=PhotoRepository(app)
        val loaded=repo.import(uri,128)
        try {
            assertEquals(uri.toString(),loaded.source.originalUri)
            val reopened=repo.reopen(loaded.source.cacheKey,loaded.source.name,128,loaded.source.rawCacheKey,loaded.source.originalUri)
            try { assertEquals(uri.toString(),reopened.source.originalUri) } finally { reopened.preview.recycle() }
            val legacy=repo.reopen(loaded.source.cacheKey,loaded.source.name,128)
            try { assertNull(legacy.source.originalUri) } finally { legacy.preview.recycle() }
            val root=File(app.cacheDir,"original-uri-jobs").apply { mkdirs() }
            try {
                val job=ExportJob(ExportJobStore.newId(),1,EditorState(),16,16,ExportFormat.PNG,95,ExportJobState.COMPLETED,originalUri=loaded.source.originalUri)
                ExportJobStore(root).update(job)
                assertEquals(uri.toString(),ExportJobStore(root).get(job.id)!!.originalUri)
            } finally { root.deleteRecursively() }
        } finally { loaded.preview.recycle();repo.discard(loaded.source) }
    }
}
