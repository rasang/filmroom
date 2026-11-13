package dev.filmroom

import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.codec.*
import dev.filmroom.io.*
import dev.filmroom.diagnostics.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Explicit fake MediaStore and JNI. Checks journal/commit ownership, not Android provider compatibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class DownloadsExportHostTest {
 class Provider:ContentProvider(){
  val rows=mutableMapOf<Uri,ContentValues>();var next=0;var refuseDelete=false;var openFailure:PhotoException?=null
  override fun onCreate()=true
  override fun getType(uri:Uri)=rows[uri]?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
  override fun insert(uri:Uri,values:ContentValues?):Uri {
   val result=Uri.withAppendedPath(uri,(++next).toString())
   rows[result]=ContentValues(values).apply{put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,context!!.packageName)};return result
  }
  override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor {
   val columns=projection?:arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
   return MatrixCursor(columns).apply{rows[uri]?.let{row->addRow(columns.map{row.get(it)}.toTypedArray())}}
  }
  override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {val row=rows[uri]?:return 0;row.putAll(values);return 1}
  override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int {
   if(refuseDelete)throw java.io.IOException("synthetic cleanup refusal")
   val row=rows[uri]?:return 0
   if(selection!=null&&(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING)!=1 || row.getAsString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)!=args?.firstOrNull()))return 0
   rows.remove(uri);return 1
  }
  override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
   openFailure?.let{throw it}
   require(rows.containsKey(uri))
   // Queue thumbnail reads must not truncate an already published export.
   return ParcelFileDescriptor.open(File(context!!.cacheDir,"provider-${uri.lastPathSegment}.bin"),ParcelFileDescriptor.parseMode(mode))
  }
 }
 private class Api(private val failure:String?=null):CodecNativeApi {
  override val available=true
  override fun nativeCreate(root:String)=1L
  override fun nativeCancel(handle:Long){}
  override fun nativeDestroy(handle:Long){}
  override fun nativeProbe(handle:Long,input:String,budget:String)="{}"
  override fun nativeDecode(handle:Long,input:String,output:String,budget:String)="{}"
  override fun nativeEncode(handle:Long,input:String,output:String,parameters:String):String {failure?.let{return it};File(output).writeBytes(byteArrayOf(73,73,42,0,0,0,0,0));return "{\"ok\":true}"}
 }
 private fun setup(failure:String?=null):Triple<Application,PhotoRepository,Provider>{
  val app=ApplicationProvider.getApplicationContext<Application>();val provider=Provider();provider.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY;exported=false})
  ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,provider)
  app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
  return Triple(app,PhotoRepository(app,PhotoCodecs(app.cacheDir,Api(failure),memorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)})),provider)
 }
 @Test fun pngAndTiffMeasuredWorkNeverReportsPublishBeforeCommit()=runBlocking {
  val(app,repo,provider)=setup()
  val input=File(app.cacheDir,"progress-input.rgb").apply{writeBytes(ByteArray(8*8*12))}
  try {
   for(format in listOf(ExportFormat.PNG,ExportFormat.TIFF16)) {
    val events=mutableListOf<Pair<String,Boolean>>()
    var committed=false
    repo.exportLinear(input,8,8,format,onCommitted={committed=true},onWork={kind,done,total,finished->
     assertFalse(committed);assertTrue(done in 0..total)
     if(kind=="copy")assertFalse(finished)
     events += kind to finished
    })
    assertTrue(committed)
    assertTrue(events.contains("encode" to true))
    if(format==ExportFormat.TIFF16)assertTrue(events.any{it.first=="copy"})
    else assertTrue(events.any{it==("encode" to false)})
   }
  }finally{input.delete()}
 }
 @Test fun tiffCommitsToDownloadsAndKeepsInputEvenWhenDeliveryCancelled()=runBlocking {
  val(app,repo,provider)=setup();val input=File(app.cacheDir,"test-input.rgb").apply{writeBytes(ByteArray(8*8*12))}
  val saved=AtomicReference<ExportResult?>();var task:Job?=null
  try{
   task=launch(start=CoroutineStart.LAZY){repo.exportLinear(input,8,8,ExportFormat.TIFF16,onCommitted={saved.set(it);task!!.cancel()})}
   task.start();task.join()
   val result=checkNotNull(saved.get());assertEquals(listOf("external_primary","downloads","1"),result.uri.pathSegments)
   val row=provider.rows[result.uri]!!;assertEquals("Download/暗房",row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));assertEquals("image/tiff",row.getAsString(MediaStore.MediaColumns.MIME_TYPE));assertEquals(0,row.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
   assertTrue(input.exists());assertFalse(app.cacheDir.listFiles().orEmpty().any{it.name.startsWith("codec-output-")})
   assertEquals(0,repo.cleanupPendingExports().removed);assertTrue(provider.rows.containsKey(result.uri))
  }finally{input.delete()}
 }
 @Test fun pngMetadataUsesOriginalCaptureMillisAndOneExportInstant()=runBlocking {
  val(app,repo,provider)=setup()
  val input=File(app.cacheDir,"metadata-linear.rgb").apply{writeBytes(ByteArray(2*3*12))}
  val metadata=PhotoMetadata.fromAttributes(mapOf("Make" to "Capture camera","LensModel" to "Original lens","DateTimeOriginal" to "2001:02:03 04:05:06","OffsetTimeOriginal" to "+05:30","SubSecTimeOriginal" to "123"))
  val exportMillis=java.time.Instant.parse("2026-10-31T09:01:02.987Z").toEpochMilli()
  try {
   val result=repo.exportLinearRgbPng(input,2,3,photoMetadata=metadata,exportMillis=exportMillis)
   val row=provider.rows[result.uri]!!
   assertEquals(metadata.captureTimeMillis(),row.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
   assertFalse(row.containsKey(MediaStore.MediaColumns.DATE_ADDED));assertFalse(row.containsKey(MediaStore.MediaColumns.DATE_MODIFIED))
   assertTrue(result.displayName.contains(exportMillis.toString()))
   val output=File(app.cacheDir,"provider-${result.uri.lastPathSegment}.bin")
   val exif=androidx.exifinterface.media.ExifInterface(output.absolutePath)
   assertEquals("Original lens",exif.getAttribute("LensModel"));assertEquals("2001:02:03 04:05:06",exif.getAttribute("DateTimeOriginal"))
   assertEquals("2026:10:31 09:01:02",exif.getAttribute("DateTime"));assertEquals("987",exif.getAttribute("SubSecTime"))
   assertEquals(1,exif.getAttributeInt("Orientation",0))
  }finally{input.delete()}
 }
 @Test fun explicitSourceXmpOffsetKeepsMediaStoreAndEncodedExifConsistent()=runBlocking {
  val(app,repo,provider)=setup()
  val input=File(app.cacheDir,"xmp-metadata-linear.rgb").apply{writeBytes(ByteArray(2*3*12))}
  val captured=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2026:10:24 00:15:59"))
  val metadata=PhotoMetadata.fromJson(org.json.JSONObject(captured.toJson()).put("sourceCreateDate","2026-10-24T00:15:59+09:00").toString())
  try {
   for(attempt in listOf(1000L,2000L)) {
    val result=repo.exportLinearRgbPng(input,2,3,photoMetadata=metadata,exportMillis=attempt)
    val row=provider.rows[result.uri]!!
    assertEquals(java.time.Instant.parse("2026-10-23T15:15:59Z").toEpochMilli(),row.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
    val exif=androidx.exifinterface.media.ExifInterface(File(app.cacheDir,"provider-${result.uri.lastPathSegment}.bin").absolutePath)
    assertEquals("2026:10:24 00:15:59",exif.getAttribute("DateTimeOriginal"));assertEquals("+09:00",exif.getAttribute("OffsetTimeOriginal"))
    assertEquals(if(attempt==1000L)"1970:01:01 00:00:01" else "1970:01:01 00:00:02",exif.getAttribute("DateTime"))
   }
   val missing=repo.exportLinearRgbPng(input,2,3,photoMetadata=PhotoMetadata.fromAttributes(emptyMap()))
   assertFalse(provider.rows[missing.uri]!!.containsKey(MediaStore.Images.Media.DATE_TAKEN))
  }finally{input.delete()}
 }
 @Test fun cleanupDeletesOnlyOwnedPendingDownloadRows()=runBlocking {
  val(app,repo,provider)=setup();val collection=MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
  val pending=provider.insert(collection,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,1)})
  val complete=provider.insert(collection,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)})
  val foreign=provider.insert(collection,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,1)})
  provider.rows[foreign]!!.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,"another.app")
  app.getSharedPreferences("filmroom-pending-exports",0).edit().putStringSet("uris",setOf(pending.toString(),complete.toString(),foreign.toString())).commit()
  val report=repo.cleanupPendingExports();assertEquals(1,report.removed);assertEquals(1,report.preservedFinalized);assertEquals(1,report.unresolved)
  assertFalse(provider.rows.containsKey(pending));assertTrue(provider.rows.containsKey(complete));assertTrue(provider.rows.containsKey(foreign))
 }
 @Test fun exportWrapperPreservesTypedFailureAndCleanupWarning()=runBlocking {
  val secret="content://private/filename.tiff"
  listOf(false,true).forEach { cleanupFails ->
   val(app,repo,provider)=setup();provider.refuseDelete=cleanupFails
   provider.openFailure=PhotoException(PhotoException.Reason.UNREADABLE,secret,codecCode=CodecErrorCode.IO_ERROR,codecStage=CodecStage.ENCODE,
    rawTechnical=RawTechnicalInfo.fromNative(org.json.JSONObject().put("rawWidth",8).put("filename",secret)))
   val input=File(app.cacheDir,"test-failed-encode.rgb").apply{writeBytes(ByteArray(8*8*12))}
   try {
    try {repo.exportLinear(input,8,8,ExportFormat.TIFF16);fail("expected encoding failure")}
    catch(error:PhotoException) {
     assertEquals(PhotoException.Reason.EXPORT,error.reason);assertEquals(cleanupFails,error.cleanupUncertain)
     assertEquals(CodecErrorCode.IO_ERROR,error.codecCode);assertEquals(CodecStage.ENCODE,error.codecStage)
     assertEquals(mapOf("rawWidth" to 8L),error.rawTechnical!!.fields())
     val info=OperationTrace.failureInfo(error,1)
     assertEquals("IO_ERROR",info.fields()["codecCode"]);assertEquals("8",info.fields()["rawWidth"])
     assertFalse(info.fields().toString().contains(secret));assertFalse(error.message!!.contains(secret))
     assertEquals(cleanupFails,error.message!!.contains("未能确认"));assertTrue(input.exists())
    }
   } finally {input.delete();provider.refuseDelete=false;repo.cleanupPendingExports()}
  }
 }

 @Test fun nativeEncodeFailureBeforeMediaInsertPreservesDiagnosticsDirectly()=runBlocking {
  val failure="""{"ok":false,"code":"IO_ERROR","stage":"encode","message":"content://private/photo","technical":{"rawWidth":8,"filename":"private"}}"""
  val(app,repo,provider)=setup(failure)
  val input=File(app.cacheDir,"test-native-encode-failure.rgb").apply{writeBytes(ByteArray(8*8*12))}
  try {
   try {repo.exportLinear(input,8,8,ExportFormat.TIFF16);fail("expected native failure")}
   catch(error:PhotoException) {
    assertEquals(PhotoException.Reason.EXPORT,error.reason);assertFalse(error.cleanupUncertain)
    assertEquals(CodecErrorCode.IO_ERROR,error.codecCode);assertEquals(CodecStage.ENCODE,error.codecStage)
    assertEquals(mapOf("rawWidth" to 8L),error.rawTechnical!!.fields());assertFalse(error.message!!.contains("private"))
    assertTrue(provider.rows.isEmpty());assertTrue(input.exists())
   }
  } finally {input.delete()}
 }

 @Test fun queueJournalPrecedesPublicationAndRecoveryFindsSameRow()=runBlocking {
  val(app,repo,provider)=setup();val input=File(app.cacheDir,"queue-commit.rgb").apply{writeBytes(ByteArray(8*8*12))}
  var journal:String?=null
  try {
   val result=repo.exportLinear(input,8,8,ExportFormat.TIFF16,onPrepared={prepared->
    assertEquals(1,provider.rows[prepared.uri]!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
    journal=prepared.uri.toString()
   })
   assertEquals(result.uri.toString(),journal)
   assertEquals(result,repo.committedExport(journal!!,8,8,ExportFormat.TIFF16))
   assertEquals(1,provider.rows.size)
   repo.cleanupPendingExports()
   assertEquals(result,repo.committedExport(journal!!,8,8,ExportFormat.TIFF16))
  } finally {input.delete()}
 }
 @Test fun failedQueueJournalCannotPublishPhoto()=runBlocking {
  val(app,repo,provider)=setup();val input=File(app.cacheDir,"queue-failed-commit.rgb").apply{writeBytes(ByteArray(8*8*12))}
  try {
   try {repo.exportLinear(input,8,8,ExportFormat.TIFF16,onPrepared={throw java.io.IOException("journal full")});fail("must fail")}
   catch(_:PhotoException){}
   assertTrue(provider.rows.isEmpty())
  } finally {input.delete()}
 }

}
