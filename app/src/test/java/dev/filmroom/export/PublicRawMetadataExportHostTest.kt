package dev.filmroom.export

import android.app.ActivityManager
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.codec.*
import dev.filmroom.engine.*
import dev.filmroom.io.*
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.TimeZone
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Opt-in public originals. Actual RAW decode, production queue/render/resize/border/JPEG;
 * only Android MediaStore is a host fake. No claim about phone gallery indexing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicRawMetadataExportHostTest {
 class PublishingMedia:ContentProvider() {
  val rows=linkedMapOf<Uri,ContentValues>();val submitted=mutableListOf<ContentValues>();private var next=0
  fun file(uri:Uri)=File(context!!.cacheDir,"metadata-provider-${uri.lastPathSegment}.jpg")
  override fun onCreate()=true
  override fun getType(uri:Uri)=rows[uri]?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
  override fun insert(uri:Uri,values:ContentValues?):Uri=Uri.withAppendedPath(uri,(++next).toString()).also {
   submitted+=ContentValues(values)
   rows[it]=ContentValues(values).apply {put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,context!!.packageName);put(MediaStore.MediaColumns.DATE_ADDED,System.currentTimeMillis()/1000)}
  }
  override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor {
   val columns=projection ?: arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
   return MatrixCursor(columns).apply{rows[uri]?.let{row->addRow(columns.map{row.get(it)}.toTypedArray())}}
  }
  override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {
   val row=rows[uri] ?: return 0;submitted+=ContentValues(values);row.putAll(values)
   if(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0) {
    // AOSP publish rescan clears the client-supplied index and derives it from the file.
    row.putNull(MediaStore.Images.Media.DATE_TAKEN)
    AospPhotoDateTakenOracle.scan(ExifInterface(file(uri).absolutePath),file(uri).lastModified())?.let{row.put(MediaStore.Images.Media.DATE_TAKEN,it)}
    row.put(MediaStore.MediaColumns.DATE_MODIFIED,file(uri).lastModified()/1000)
   }
   return 1
  }
  override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int {file(uri).delete();return if(rows.remove(uri)!=null)1 else 0}
  override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri),if(mode=="r")ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
 }

 @Test fun sixPublicRawFilesKeepCaptureMetadataThroughActualExports()=runBlocking {
  assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null && System.getenv("FILMROOM_PUBLIC_RAW_METADATA")=="1")
  val previousZone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
  try {
  assertNull(NativeFilmBridge.loadError);assertTrue(NativeCodecBridge.available)
  val app=ApplicationProvider.getApplicationContext<Application>()
  File(app.filesDir,"export-jobs").deleteRecursively()
  File(app.cacheDir,"photo-sources").deleteRecursively()
  shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply {
   availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
  })
  ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
  ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
  val media=PublishingMedia();media.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY})
  ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,media)
  app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
  val sources=listOf("750_D800.NEF","6887_Z_f.NEF","131_EOS_7D.CR2","130_EOS_7D.CR2","851_ILCE-7M2.ARW").map{File("/workspace/shared/filmroom-camera-raw-samples",it)}+File("/workspace/shared/filmroom-raw-variants-development/samples/8846_ILCE-7M5.ARW")
  val out=File("build/public-raw-metadata-exports").apply{mkdirs()};val report=JSONArray()
  val repository=PhotoRepository(app)
  for(source in sources) {
   val uri=Uri.parse("content://dev.filmroom.public-raw/${source.name}")
   shadowOf(app.contentResolver).registerInputStream(uri,source.inputStream())
   val loaded=repository.import(uri,128)
   try {
    assertNotNull(loaded.source.linearFile)
    val snapshot=JSONObject(loaded.source.photoMetadata.toJson())
    for(border in listOf(false,true)) {
     var queue=ExportQueue(app)
     val job=queue.enqueue(loaded.source,EditorState(filmBorder=border),ExportFormat.JPEG,320,95)
     queue.store.update(job.copy(state=ExportJobState.PAUSED))
     queue=ExportQueue(app)
     assertEquals(snapshot.toString(),queue.store.get(job.id)!!.photoMetadata)
     queue.retry(job.id)
     withTimeout(180000){queue.runPending{}}
     val done=queue.store.get(job.id)!!
     assertEquals("${source.name} border=$border ${done.message}",ExportJobState.COMPLETED,done.state)
     val published=Uri.parse(done.mediaUri)
     val jpeg=media.file(published).copyTo(File(out,source.name+if(border)"-border.jpg" else "-resize.jpg"),true)
     val exif=ExifInterface(jpeg.absolutePath)
     val read=JSONObject();val fields=snapshot.getJSONObject("attributes")
     fields.keys().forEach {key->read.put(key,exif.getAttribute(key) ?: JSONObject.NULL)}
     for(key in listOf("Make","Model","LensModel","LensSpecification","DateTimeOriginal","DateTimeDigitized","OffsetTimeOriginal","OffsetTimeDigitized","SubSecTimeOriginal","SubSecTimeDigitized","PhotographicSensitivity")) {
      if(fields.has(key))assertEquals("${source.name} border=$border $key",fields.getString(key),exif.getAttribute(key))
     }
     for(key in listOf("FNumber","ExposureTime","FocalLength")) {
      assertTrue("Source must contain $key",fields.has(key))
      assertEquals("${source.name} border=$border $key",fields.getString(key).let{v->if(v.contains('/'))v.substringBefore('/').toDouble()/v.substringAfter('/').toDouble() else v.toDouble()},exif.getAttributeDouble(key,-1.0),1e-12)
     }
     assertNotEquals("Modification time must not overwrite original capture date",exif.getAttribute("DateTimeOriginal"),exif.getAttribute("DateTime"))
     val exportTime=Instant.ofEpochMilli(checkNotNull(done.exportMillis)).atOffset(ZoneOffset.UTC)
     assertEquals(DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").format(exportTime),exif.getAttribute("DateTime"))
     assertEquals("+00:00",exif.getAttribute("OffsetTime"))
     assertEquals(1,exif.getAttributeInt("Orientation",0))
     assertEquals(done.outputWidth,exif.getAttributeInt("ImageWidth",0));assertEquals(done.outputHeight,exif.getAttributeInt("ImageLength",0))
     val values=media.rows[published]!!
     val originalExif=ExifInterface(source.absolutePath)
     val capture=LocalDateTime.parse(originalExif.getAttribute("DateTimeOriginal"),DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss"))
     val offset=ZoneOffset.of(originalExif.getAttribute("OffsetTimeOriginal") ?: if(source.name=="750_D800.NEF")"+02:00" else "+08:00")
     val sub=originalExif.getAttribute("SubSecTimeOriginal").orEmpty().take(3).padEnd(3,'0').toInt()
     val expectedTime=capture.toInstant(offset).toEpochMilli()+sub
     assertEquals(expectedTime,loaded.source.photoMetadata.mediaStoreCaptureTimeMillis())
     assertNotNull("Every public sample has a capture date for gallery indexing",expectedTime)
     assertEquals(expectedTime,values.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
     assertTrue(media.submitted.none{it.containsKey(MediaStore.Images.Media.DATE_MODIFIED) || it.containsKey(MediaStore.Images.Media.DATE_ADDED)})
     assertTrue("Provider file modification time remains new export time",values.getAsLong(MediaStore.Images.Media.DATE_MODIFIED)>1_700_000_000L)
     report.put(JSONObject().put("source",source.name).put("sourceMime",loaded.source.mimeType).put("sourceWidth",loaded.source.originalWidth).put("sourceHeight",loaded.source.originalHeight).put("border",border).put("snapshot",snapshot).put("finalExif",read).put("dateTaken",expectedTime ?: JSONObject.NULL).put("output",jpeg.path).put("width",done.outputWidth).put("height",done.outputHeight))
     File(out,"readback.json").writeText(report.toString(2))
    }
   } finally {loaded.preview.recycle();repository.discard(loaded.source)}
  }
  } finally {TimeZone.setDefault(previousZone)}
 }
}
