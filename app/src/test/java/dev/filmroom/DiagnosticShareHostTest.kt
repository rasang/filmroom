package dev.filmroom

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.ui.DiagnosticShareIntents
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** JVM Android-framework simulation, not a device Sharesheet verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class DiagnosticShareHostTest {
    @Before fun attachProviderToThisTestsCacheDirectory(){attachCurrentDiagnosticProvider()}
 @Test fun sharesOnlyDedicatedLogFileWithReadGrant(){
  val context=ApplicationProvider.getApplicationContext<FilmroomApplication>()
  val file=File(context.cacheDir,"diagnostic-shares/日志.txt").apply{parentFile.mkdirs();writeText("log")}
  val intent=DiagnosticShareIntents.create(context,file,15)
  assertEquals(Intent.ACTION_SEND,intent.action);assertEquals("text/plain",intent.type)
  assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.flags)
  val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
  assertEquals("content",uri.scheme);assertEquals(context.packageName+".diagnostics",uri.authority)
  assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
  assertEquals("text/plain",context.contentResolver.getType(uri))
  context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)!!.use { cursor ->
   assertTrue(cursor.moveToFirst());assertEquals("日志.txt",cursor.getString(0))
  }
  assertEquals("log",context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
 }
 @Test fun textMimeOverrideDoesNotBypassProviderRootValidation(){
  val context=ApplicationProvider.getApplicationContext<FilmroomApplication>()
  val escaped=Uri.Builder().scheme("content").authority(context.packageName+".diagnostics")
   .appendPath("diagnostics").appendPath("../photo-sources/private.txt").build()
  assertThrows(SecurityException::class.java){context.contentResolver.getType(escaped)}
 }
 @Test fun legacyZipAndPartialFilesCannotBeShared(){
  val context=ApplicationProvider.getApplicationContext<FilmroomApplication>()
  for(name in listOf("old.zip","unfinished.part")) {
   val file=File(context.cacheDir,"diagnostic-shares/$name").apply{parentFile.mkdirs();writeText("private")}
   assertThrows(IllegalArgumentException::class.java){DiagnosticShareIntents.create(context,file,5)}
  }
 }
 @Test fun photoAndSourcePathsCannotBeSharedThroughProvider(){
  val context=ApplicationProvider.getApplicationContext<FilmroomApplication>()
  for(file in listOf(File(context.cacheDir,"photo-sources/source-1.image"),File(context.filesDir,"engine-data/profile.json"))){
   file.parentFile.mkdirs();file.writeText("private")
   assertThrows(IllegalArgumentException::class.java){FileProvider.getUriForFile(context,context.packageName+".diagnostics",file)}
   assertThrows(IllegalArgumentException::class.java){DiagnosticShareIntents.create(context,file,15)}
  }
 }
}
