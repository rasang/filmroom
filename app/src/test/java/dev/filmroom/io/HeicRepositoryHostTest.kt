package dev.filmroom.io

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.codec.PhotoCodecs
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportQueue
import dev.filmroom.model.EditorState
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs

/** Real container + production lifecycle/queue, explicit substitute HEVC pixels. No device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeicRepositoryHostTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val memory={NativeMemorySnapshot(8L*1024*1024*1024,128L*1024*1024,false)}
    private fun fixture(name:String="corners-sdr8.heic")=listOf(File("src/androidTest/assets/heic/$name"),File("app/src/androidTest/assets/heic/$name")).first{it.isFile}.readBytes()
    private class Backend:HeicBackend {
        var calls=0;var closed=false;var fail=false
        override fun supported()=true
        override fun decode(file:File,info:HeicContainer.Info,job:CoroutineContext):HeicPixels {
            calls++
            return object:HeicPixels{
                override val width=info.displayWidth;override val height=info.displayHeight
                override fun readRow(y:Int,argb:IntArray){if(fail)throw IOException("injected failure");for(x in argb.indices)argb[x]=0xff000000.toInt() or ((x*2) shl 16) or (y*3)}
                override fun close(){closed=true}
            }
        }
    }
    private fun setup(backend:Backend,bytes:ByteArray=fixture()):Pair<PhotoRepository,Uri>{
        val uri=Uri.parse("content://dev.filmroom.heic-test/${System.nanoTime()}")
        shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(bytes))
        val repo=PhotoRepository(app,PhotoCodecs(app.cacheDir),HeicImport(memory,backend))
        return repo to uri
    }
    @Test fun sourceColorMustBeSrgbBeforeRequestingSrgbOutput(){
        HeicColorPolicy.requireSource(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        for(color in listOf(null,
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3),
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.BT2020),
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.EXTENDED_SRGB))){
            try{HeicColorPolicy.requireSource(color);fail("Unverified input color must not be relabeled by output request")}
            catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
        }
    }
    @Test fun verifiedP3SourceMustMatchProfileBeforeActualSrgbConversion(){
        val p3=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
        val srgb=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        HeicColorPolicy.requireSource(p3,HeicIccProfile.ColorIdentity.DISPLAY_P3)
        HeicColorPolicy.requireSource(srgb,HeicIccProfile.ColorIdentity.SRGB)
        for((source,expected) in listOf(p3 to HeicIccProfile.ColorIdentity.SRGB,srgb to HeicIccProfile.ColorIdentity.DISPLAY_P3)){
            try{HeicColorPolicy.requireSource(source,expected);fail("Wrong source profile must not pass via target sRGB")}
            catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
        }
        val converted=android.graphics.ColorSpace.connect(p3,srgb).transform(floatArrayOf(.75f,.25f,.3f))
        assertTrue("P3 must be numerically converted, not relabeled",kotlin.math.abs(converted[0]-.75f)>.01f)
    }
    @Test fun p3ToSrgbConnectorMatchesIndependentLittleCmsReference(){
        val root=listOf(File("src/androidTest/assets/heic"),File("app/src/androidTest/assets/heic")).first{it.isDirectory}
        val reference=org.json.JSONObject(File(root,"icc-p3-srgb-reference.json").readText())
        val p3=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
        val srgb=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        val connector=android.graphics.ColorSpace.connect(p3,srgb,android.graphics.ColorSpace.RenderIntent.RELATIVE)
        val samples=reference.getJSONArray("samples")
        repeat(samples.length()){i->
            val sample=samples.getJSONObject(i);val input=sample.getJSONArray("p3_encoded");val expected=sample.getJSONArray("srgb_encoded")
            val actual=connector.transform(FloatArray(3){input.getDouble(it).toFloat()})
            repeat(3){c->assertEquals("Independent LCMS sample $i channel $c",expected.getDouble(c),actual[c].toDouble(),reference.getDouble("float_tolerance"))}
        }
    }
    @Test fun importRestoreAndOriginalSpoolShareOneFullSizeSdrSourceWithoutRawMislabel()=runBlocking {
        val backend=Backend();val(repo,uri)=setup(backend)
        val loaded=repo.import(uri,48)
        val output=File(app.cacheDir,"heic-original-${System.nanoTime()}.rgb")
        val sized=File(app.cacheDir,"heic-sized-${System.nanoTime()}.rgb")
        try{
            assertEquals("image/heif",loaded.source.mimeType)
            assertEquals(96,loaded.source.originalWidth);assertEquals(64,loaded.source.originalHeight)
            assertEquals(48,loaded.preview.width);assertEquals(32,loaded.preview.height)
            assertTrue(loaded.source.rawCacheKey!!.startsWith("heic-source-"))
            assertArrayEquals(fixture(),loaded.source.localFile.readBytes())
            val canonical=checkNotNull(loaded.source.linearFile)
            assertEquals(96L*64*12,canonical.length());assertTrue(backend.closed)
            val restored=repo.reopen(loaded.source.cacheKey,previewMaxEdge=48,rawCacheKey=loaded.source.rawCacheKey)
            try{
                assertEquals("image/heif",restored.source.mimeType)
                assertEquals(loaded.source.developmentWarning,restored.source.developmentWarning)
                assertTrue(loaded.preview.sameAs(restored.preview));assertEquals(1,backend.calls)
            }finally{restored.preview.recycle()}
            val dimensions=repo.spoolOriginal(loaded.source,output)
            assertEquals(PhotoLimits.Dimensions(96,64),dimensions)
            assertArrayEquals(canonical.readBytes(),output.readBytes())
            repo.spoolSized(loaded.source,sized,48)
            val sizedPreview=LinearSpool.preview(sized,48,32,48,coroutineContext)
            try{assertTrue(loaded.preview.sameAs(sizedPreview))}finally{sizedPreview.recycle()}
            assertEquals(1,backend.calls)
        }finally{output.delete();sized.delete();loaded.preview.recycle();repo.discard(loaded.source)}
    }
    @Test fun p3IccImportAndRestoreKeepExplicitColorConversionNotice()=runBlocking {
        val backend=Backend();val(repo,uri)=setup(backend,fixture("corners-grid-icc-p3-tiles.heic"));val loaded=repo.import(uri)
        try{
            assertEquals("image/heif",loaded.source.mimeType)
            assertTrue(loaded.source.developmentWarning!!.contains("Display P3"))
            assertTrue(loaded.source.developmentWarning!!.contains("转换"))
            val restored=repo.reopen(loaded.source.cacheKey,rawCacheKey=loaded.source.rawCacheKey)
            try{assertEquals(loaded.source.developmentWarning,restored.source.developmentWarning);assertEquals(1,backend.calls)}
            finally{restored.preview.recycle()}
        }finally{loaded.preview.recycle();repo.discard(loaded.source)}
    }
    @Test fun failedDecodeCleansBothCopiedOriginalAndPartialSource()=runBlocking {
        val dir=File(app.cacheDir,"photo-sources");val before=dir.listFiles().orEmpty().map{it.name}.toSet()
        val backend=Backend().apply{fail=true};val(repo,uri)=setup(backend)
        try{repo.import(uri);fail()}catch(_:PhotoException){}
        assertTrue(backend.closed);assertEquals(before,dir.listFiles().orEmpty().map{it.name}.toSet())
    }
    @Test fun unsupportedVariantsNeverReachPixelBackendOrLeaveWorkFiles()=runBlocking {
        val dir=File(app.cacheDir,"photo-sources");val before=dir.listFiles().orEmpty().map{it.name}.toSet()
        for(name in listOf("corners-main10.heic","corners-mirror.heic")){
            val backend=Backend();val(repo,uri)=setup(backend,fixture(name))
            try{repo.import(uri);fail()}catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
            assertEquals(0,backend.calls)
        }
        assertEquals(before,dir.listFiles().orEmpty().map{it.name}.toSet())
    }
    @Test fun missingRestoreSpoolDoesNotSilentlyDecodeAgainOrDeleteOriginal()=runBlocking {
        val backend=Backend();val(repo,uri)=setup(backend);val photo=repo.import(uri)
        try{
            photo.source.linearFile!!.delete()
            try{repo.reopen(photo.source.cacheKey,rawCacheKey=photo.source.rawCacheKey);fail()}catch(_:PhotoException){}
            assertEquals(1,backend.calls);assertTrue(photo.source.localFile.isFile)
        }finally{photo.preview.recycle();repo.discard(photo.source)}
    }
    @Test fun queueSnapshotsHeicLinearSourceForOriginalAndSizedExports()=runBlocking {
        File(app.filesDir,"export-jobs").deleteRecursively()
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        val backend=Backend();val(repo,uri)=setup(backend);val photo=repo.import(uri)
        val canonical=photo.source.linearFile!!.readBytes();val queue=ExportQueue(app)
        val original=queue.enqueue(photo.source,EditorState(),ExportFormat.PNG,null,95)
        val sized=queue.enqueue(photo.source,EditorState(),ExportFormat.JPEG,48,95)
        photo.preview.recycle();repo.discard(photo.source)
        try{
            assertEquals("LINEAR",original.inputKind);assertEquals("LINEAR_SOURCE",sized.inputKind)
            assertArrayEquals(canonical,File(queue.store.directory(original.id),"input.rgb").readBytes())
            assertArrayEquals(canonical,File(queue.store.directory(sized.id),"source.rgb").readBytes())
            assertEquals(96,original.width);assertEquals(48,sized.width)
            assertEquals(1,backend.calls)
        }finally{queue.cancel(original.id);queue.cancel(sized.id)}
        assertEquals(ExportJobState.CANCELLED,queue.store.get(original.id)!!.state)
    }
}
