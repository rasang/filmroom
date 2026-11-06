package dev.filmroom.io

import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.OperationTrace
import dev.filmroom.codec.*
import dev.filmroom.diagnostics.*
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.formats.PhotoFormat
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs

/** Real ContentResolver -> registered ContentProvider -> file/slice descriptor host paths.
 * Stream fault injection is in a separate class and is not provider transport coverage.
 * Robolectric pipes are regular files and are NOT used to claim real pipe/Binder behavior.
 * This is a controlled provider, not realme DocumentsProvider. The private RAW test is in a separate fork. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderImportHostTest {
    class Provider : ContentProvider() {
        enum class Mode { FILE, SLICE, MISSING, DENIED }
        lateinit var source: File
        var mode = Mode.FILE
        var displayName: String? = null
        var mime: String? = null
        var sizeHint: Long? = null
        var modifiedMillis: Long? = null
        var openCalls=0
        var typedCalls=0
        override fun onCreate()=true
        override fun getType(uri:Uri)=mime
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor {
            val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
            return MatrixCursor(columns).apply { addRow(columns.map {
                when(it){OpenableColumns.DISPLAY_NAME->displayName;OpenableColumns.SIZE->sizeHint;android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED->modifiedMillis;else->null}
            }.toTypedArray()) }
        }
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("read only")
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int=error("read only")
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int=error("read only")
        override fun openTypedAssetFile(uri:Uri,mimeTypeFilter:String,opts:Bundle?,signal:CancellationSignal?):AssetFileDescriptor {
            typedCalls++
            assertEquals("*/*",mimeTypeFilter)
            return openAssetFile(uri,"r")
        }
        override fun openAssetFile(uri:Uri,mode:String):AssetFileDescriptor {
            openCalls++
            assertEquals("r",mode)
            when(this.mode) {
                Mode.MISSING -> throw FileNotFoundException("private provider text")
                Mode.DENIED -> throw SecurityException("private provider text")
                Mode.SLICE -> return AssetFileDescriptor(ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY),128,source.length()-256)
                Mode.FILE -> return AssetFileDescriptor(ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY),0,AssetFileDescriptor.UNKNOWN_LENGTH)
            }
        }
    }
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun setup(file:File,mode:Provider.Mode=Provider.Mode.FILE):Pair<Provider,Uri> {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            availMem=1024L*1024*1024;threshold=128L*1024*1024;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val provider=Provider().apply {
            source=file;this.mode=mode
            attachInfo(app,ProviderInfo().apply{authority="filmroom.provider.host";exported=false})
        }
        ShadowContentResolver.registerProviderInternal("filmroom.provider.host",provider)
        return provider to Uri.parse("content://filmroom.provider.host/private.ARW")
    }
    private fun png():File {
        val file=File(app.cacheDir,"source.png")
        val bitmap=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(Color.rgb(60,110,170));file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
        finally{bitmap.recycle()}
        return file
    }
    private fun privateFiles()=File(app.cacheDir,"photo-sources").listFiles().orEmpty().map{it.name}.toSet()
    private fun hash(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;hash.update(bytes,0,n)} }
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    private suspend fun failure(repo:PhotoRepository,uri:Uri):PhotoException {
        try {repo.import(uri,256,ImportOrigin.FILES_PICKER);error("Expected failure")}
        catch(e:PhotoException){return e}
    }
    @Test fun actualProviderFilePreservesBytesWithUnknownMimeAndWrongExtension()=runBlocking {
        val source=png()
        for(mode in listOf(Provider.Mode.FILE)) {
            val(provider,uri)=setup(source,mode)
            provider.displayName="pretend.ARW";provider.mime="application/octet-stream";provider.sizeHint=null
            val info=mutableListOf<ImportDiagnosticInfo>();val repo=PhotoRepository(app)
            val loaded=repo.import(uri,128,ImportOrigin.FILES_PICKER){info+=it}
            try {
                assertTrue(provider.openCalls>0)
                assertEquals(hash(source),hash(loaded.source.localFile))
                assertEquals(32,loaded.source.originalWidth);assertEquals(16,loaded.source.originalHeight)
                assertEquals("image/png",loaded.source.mimeType)
                assertEquals(ImportPhase.COMPLETE,info.last().phase)
                assertEquals(ImportRoute.RASTER,info.last().route)
                assertEquals(PhotoFormat.PNG,info.last().format)
                assertEquals(source.length(),info.last().copiedBytes)
                assertEquals(source.length(),info.last().sourceBytes)
                assertNull(info.last().sizeHintBytes)
                assertEquals(32,info.last().width);assertEquals(16,info.last().height)
                assertEquals(ImportSuffix.ARW,info.last().suffix)
                assertFalse(SafeInfo(importDiagnostic=info.last()).fields().toString().contains("pretend"))
                assertTrue(info.all{it.origin==ImportOrigin.FILES_PICKER})
            }finally{loaded.preview.recycle();repo.discard(loaded.source)}
        }
    }
    @Test fun assetDescriptorSliceIsRespectedInsteadOfReadingWholeBackingFile()=runBlocking {
        val png=png();val original=png.readBytes()
        val sliced=File(app.cacheDir,"slice.bin").apply{writeBytes(ByteArray(128){7}+original+ByteArray(128){9})}
        val(provider,uri)=setup(sliced,Provider.Mode.SLICE);val repo=PhotoRepository(app)
        val loaded=repo.import(uri,128,ImportOrigin.FILES_PICKER)
        try {assertTrue(provider.openCalls>0);assertArrayEquals(original,loaded.source.localFile.readBytes())}
        finally{loaded.preview.recycle();repo.discard(loaded.source)}
    }
    @Test fun missingAndDeniedProvidersHaveDistinctOpenFailuresAndRollback()=runBlocking {
        for((mode,category) in listOf(Provider.Mode.MISSING to ImportFailure.FILE_NOT_FOUND,Provider.Mode.DENIED to ImportFailure.PERMISSION_DENIED)) {
            val(provider,uri)=setup(png(),mode);val before=privateFiles()
            val error=failure(PhotoRepository(app),uri)
            assertTrue(provider.openCalls>0);assertEquals(before,privateFiles())
            assertEquals(PhotoException.Reason.UNREADABLE,error.reason)
            assertEquals(ImportPhase.SOURCE_OPEN,error.importDiagnostic!!.phase)
            assertEquals(category,error.importDiagnostic!!.failure)
            assertEquals(0L,error.importDiagnostic!!.copiedBytes)
            assertNull(error.codecCode)
            val fields=OperationTrace.failureInfo(error,1).fields()
            assertFalse(fields.toString().contains("private"));assertFalse(fields.toString().contains("content://"))
        }
    }
    @Test fun unsupportedRecognizedContainerRetainsFormatWithoutInventingAdapter()=runBlocking {
        val source=File(app.cacheDir,"unsupported-big-tiff.bin").apply {
            writeBytes(byteArrayOf(73,73,43,0,8,0,0,0,16,0,0,0,0,0,0,0))
        }
        val(_,uri)=setup(source);val before=privateFiles()
        val error=failure(PhotoRepository(app),uri)
        assertEquals(PhotoException.Reason.UNSUPPORTED,error.reason)
        assertEquals(ImportPhase.INPUT_PROBE,error.importDiagnostic!!.phase)
        assertEquals(PhotoFormat.TIFF,error.importDiagnostic!!.format)
        assertNull(error.importDiagnostic!!.route)
        assertEquals(source.length(),error.importDiagnostic!!.copiedBytes)
        assertEquals(before,privateFiles())
    }
    @Test fun cancellationAfterCopyDoesNotWrapAsReadFailureOrKeepPartialFile()=runBlocking {
        val(provider,uri)=setup(png());val before=privateFiles()
        try {
            PhotoRepository(app).import(uri,128,ImportOrigin.FILES_PICKER){if(it.phase==ImportPhase.INPUT_PROBE)throw CancellationException("synthetic")}
            fail("expected cancellation")
        }catch(expected:CancellationException){assertEquals(before,privateFiles());assertTrue(provider.openCalls>0)}
    }
    @Test fun diagnosticFailureCannotBreakImportOrLoseTimestampSidecar()=runBlocking {
        for(oom in listOf(false,true)) {
            val(provider,uri)=setup(png());provider.modifiedMillis=1_700_000_000_000L
            val before=privateFiles();val repo=PhotoRepository(app)
            val loaded=repo.import(uri,128,ImportOrigin.FILES_PICKER){if(oom)throw OutOfMemoryError("synthetic diagnostic failure")else throw java.io.IOException("synthetic observer failure")}
            val sidecar=File(loaded.source.localFile.path+".imprint-time")
            try {assertTrue(sidecar.isFile);assertTrue(sidecar.readText().startsWith("2023:"));assertEquals(32,loaded.source.originalWidth)}
            finally{loaded.preview.recycle();repo.discard(loaded.source)}
            assertEquals(before,privateFiles())
        }
    }
    @Test fun nativeCreateZeroIsExplicitAndCannotLookLikeProviderIo()=runBlocking {
        val source=File(app.cacheDir,"synthetic.tiff").apply {
            writeBytes(byteArrayOf(73,73,42,0,8,0,0,0,0,0,0,0,0,0))
        }
        val(_,uri)=setup(source)
        val api=object:CodecNativeApi {
            override val available=true
            override fun nativeCreate(root:String)=0L
            override fun nativeProbe(handle:Long,input:String,budget:String):String=error("not called")
            override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String=error("not called")
            override fun nativeEncode(handle:Long,input:String,output:String,parameters:String):String=error("not called")
            override fun nativeCancel(handle:Long){}
            override fun nativeDestroy(handle:Long){}
        }
        val error=failure(PhotoRepository(app,PhotoCodecs(app.cacheDir,api,memorySnapshot={NativeMemorySnapshot(1024L*1024*1024,128L*1024*1024,false)})),uri)
        assertEquals(CodecErrorCode.NATIVE_HANDLE_CREATE_FAILED,error.codecCode)
        assertEquals(CodecStage.NATIVE_CREATE,error.codecStage)
        assertEquals(ImportFailure.CODEC_REPORTED,error.importDiagnostic!!.failure)
        assertEquals(ImportPhase.INPUT_PROCESS,error.importDiagnostic!!.phase)
        assertEquals(source.length(),error.importDiagnostic!!.copiedBytes)
    }
}
