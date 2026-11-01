package dev.filmroom.export

import android.app.ActivityManager
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.codec.*
import dev.filmroom.engine.*
import dev.filmroom.io.*
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID

/** Opt-in Linux JNI integration: production queue/engine/codec/repository.
 * MediaStore is fake; the HEIC-specific method also explicitly fakes the Android HEVC backend.
 * Run with tools/host-jni/real-queue.init.gradle and FILMROOM_HOST_JNI_DIR. Not Android device QA.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class RealNativeExportQueueHostTest {
    class Media:ContentProvider() {
        val rows=linkedMapOf<Uri,ContentValues>(); var inserts=0
        fun file(uri:Uri)=File(context!!.cacheDir,"real-provider-${uri.lastPathSegment}.bin")
        override fun onCreate()=true
        override fun getType(uri:Uri)=rows[uri]?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        override fun insert(uri:Uri,values:ContentValues?):Uri=Uri.withAppendedPath(uri,(++inserts).toString()).also {
            rows[it]=ContentValues(values).apply{put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,context!!.packageName)}
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor {
            val columns=projection?:arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            return MatrixCursor(columns).apply{rows[uri]?.let{row->addRow(columns.map{row.get(it)}.toTypedArray())}}
        }
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {val row=rows[uri]?:return 0;row.putAll(values);return 1}
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int {file(uri).delete();return if(rows.remove(uri)!=null)1 else 0}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            check(rows.containsKey(uri))
            return ParcelFileDescriptor.open(file(uri),if(mode=="r")ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
    }
    private fun verify(file:File,format:ExportFormat,width:Int=32,height:Int=24) {
        val bytes=file.readBytes();assertTrue(bytes.size>100)
        val decoded=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,file)
        assertNotNull("real encoded image must decode",decoded)
        assertEquals(width,decoded.javaClass.getMethod("getWidth").invoke(decoded));assertEquals(height,decoded.javaClass.getMethod("getHeight").invoke(decoded))
        if(format==ExportFormat.JPEG) assertTrue("JPEG ICC",String(bytes,Charsets.ISO_8859_1).contains("ICC_PROFILE"))
        else {
            val b=ByteBuffer.wrap(bytes).order(if(bytes[0]==73.toByte())ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            val offset=b.getInt(4);val count=b.getShort(offset).toInt() and 65535
            assertTrue("TIFF ICC tag",(0 until count).any{(b.getShort(offset+2+it*12).toInt() and 65535)==34675})
        }
    }
    /** Real container + repository/spool/queue/engine/codec; HEVC pixel backend and MediaStore
     * are explicit test doubles. This never claims that Linux ran Android's HEVC decoder. */
    @Test fun heicCanonicalSourceReopensAndExportsThroughRealNativeQueueIncludingLegacyRawRetry()=runBlocking {
        assumeTrue("Opt-in requires Linux host JNI libraries",System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        assertNull(NativeFilmBridge.loadError);assertTrue(NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir,"export-jobs").deleteRecursively()
        File(app.cacheDir,"photo-sources").deleteRecursively()
        val memory={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply{
            availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val media=Media();media.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,media)
        app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
        val fixture=listOf(File("src/androidTest/assets/heic/corners-sdr8.heic"),File("app/src/androidTest/assets/heic/corners-sdr8.heic"))
            .firstOrNull{it.isFile} ?: error("Real HEIC fixture missing")
        val original=fixture.readBytes()
        val info=HeicContainer.inspect(fixture)
        assertEquals(96,info.displayWidth);assertEquals(64,info.displayHeight)
        var decodes=0;var closes=0
        fun pixel(x:Int,y:Int):Int = (255 shl 24) or ((16+x*220/95) shl 16) or ((12+y*224/63) shl 8) or (24+(x+y)%180)
        val backend=object:HeicBackend {
            override fun supported()=true
            override fun decode(file:File,info:HeicContainer.Info,job:kotlin.coroutines.CoroutineContext):HeicPixels {
                job.ensureActive();decodes++
                assertArrayEquals("Immutable copied HEIC reaches backend",original,file.readBytes())
                assertEquals(96,info.displayWidth);assertEquals(64,info.displayHeight)
                return object:HeicPixels {
                    override val width=96;override val height=64
                    override fun readRow(y:Int,argb:IntArray){for(x in 0 until width)argb[x]=pixel(x,y)}
                    override fun close(){closes++}
                }
            }
        }
        val repository=PhotoRepository(app,PhotoCodecs(app.cacheDir,memorySnapshot=memory),HeicImport(memory,backend))
        val uri=Uri.parse("content://dev.filmroom.synthetic-heic/primary")
        shadowOf(app.contentResolver).registerInputStream(uri,java.io.ByteArrayInputStream(original))
        val loaded=repository.import(uri,96)
        var restored:LoadedPhoto?=null
        try {
            assertEquals("image/heif",loaded.source.mimeType)
            assertEquals(96,loaded.source.originalWidth);assertEquals(64,loaded.source.originalHeight)
            assertEquals(1,decodes);assertEquals(1,closes)
            val canonical=checkNotNull(loaded.source.linearFile)
            val canonicalBytes=canonical.readBytes()
            assertEquals(96L*64*12,canonical.length())
            val expected=ByteBuffer.allocate(canonicalBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            for(y in 0 until 64)for(x in 0 until 96){
                val color=pixel(x,y)
                for(shift in listOf(16,8,0))expected.putFloat(LinearRgb.srgb8ToLinear[(color ushr shift) and 255])
            }
            assertArrayEquals("Actual repository canonical RGB comes from explicit backend pixels",expected.array(),canonicalBytes)
            restored=repository.reopen(loaded.source.cacheKey,"HEIC fixture",48,loaded.source.rawCacheKey)
            assertEquals(canonical,restored.source.linearFile)
            assertEquals(48,restored.preview.width);assertEquals(32,restored.preview.height)
            assertEquals("Restore must not call HEVC backend again",1,decodes)
            var queue=ExportQueue(app)
            val completed=mutableListOf<String>()
            for(mode in listOf("LINEAR","LINEAR_SOURCE","RAW")) {
                val originalSize=mode=="LINEAR"
                val format=if(originalSize)ExportFormat.JPEG else ExportFormat.TIFF16
                val job=queue.enqueue(restored.source,EditorState(),format,if(originalSize)null else 48,95)
                assertEquals(if(originalSize)"LINEAR" else "LINEAR_SOURCE",job.inputKind)
                val directory=queue.store.directory(job.id)
                val snapshot=File(directory,if(originalSize)"input.rgb" else "source.rgb")
                assertArrayEquals("Queue captures the same canonical linear source",canonicalBytes,snapshot.readBytes())
                if(mode=="RAW") {
                    queue.store.update(job.copy(inputKind="RAW",state=ExportJobState.FAILED,message="legacy linear source retry"))
                    queue=ExportQueue(app)
                    assertEquals("RAW",queue.store.get(job.id)!!.inputKind)
                    assertEquals(ExportJobState.FAILED,queue.store.get(job.id)!!.state)
                    queue.retry(job.id)
                }
                withTimeout(60000){queue.runPending{}}
                val done=checkNotNull(queue.store.get(job.id))
                assertEquals("$mode: ${done.message}",ExportJobState.COMPLETED,done.state)
                assertEquals(if(originalSize)96 else 48,done.width)
                assertEquals(if(originalSize)64 else 32,done.height)
                val published=Uri.parse(done.mediaUri)
                verify(media.file(published),format,done.width,done.height)
                assertEquals(0,media.rows[published]!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
                assertFalse(File(directory,"input.rgb").exists());assertFalse(File(directory,"source.rgb").exists())
                assertFalse("No partial native/codec work remains",directory.walkTopDown().any{
                    it.isFile&&(it.extension=="part"||it.name.startsWith("film-result-")||it.name.startsWith("codec-output-"))
                })
                assertArrayEquals(canonicalBytes,canonical.readBytes())
                assertArrayEquals(original,loaded.source.localFile.readBytes())
                completed.add(done.id)
            }
            assertEquals(3,media.inserts);assertEquals(3,media.rows.size)
            assertTrue(media.rows.values.all{it.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0})
            assertTrue(completed.all{queue.store.get(it)!!.state==ExportJobState.COMPLETED})
            assertEquals("All exports and restoration use canonical pixels, never decode HEIC again",1,decodes)
            assertEquals(1,closes)
            assertArrayEquals("Original fixture is never rewritten",original,fixture.readBytes())
            assertArrayEquals(original,loaded.source.localFile.readBytes())
        } finally {
            restored?.preview?.recycle();loaded.preview.recycle();repository.discard(loaded.source)
        }
        assertFalse(loaded.source.localFile.exists());assertFalse(loaded.source.linearFile!!.exists())
        assertArrayEquals(original,fixture.readBytes())
    }

    /** Color conversion itself is tested against LCMS elsewhere. Here the explicit fake HEVC
     * backend hands the production repository LCMS-converted sRGB markers, NOT P3 code values. */
    @Test fun publicIccP3SourcesKeepValidatedIdentityAndConvertedCanonicalPixelsThroughRealNativeExports()=runBlocking {
        assumeTrue("Opt-in requires Linux host JNI libraries",System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        assertNull(NativeFilmBridge.loadError);assertTrue(NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir,"export-jobs").deleteRecursively()
        File(app.cacheDir,"photo-sources").deleteRecursively()
        val memory={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply{
            availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val media=Media();media.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,media)
        app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
        val root=listOf(File("src/androidTest/assets/heic"),File("app/src/androidTest/assets/heic"))
            .firstOrNull{it.isDirectory} ?: error("Public ICC fixtures missing")
        val reference=org.json.JSONObject(File(root,"icc-p3-srgb-reference.json").readText()).getJSONArray("samples")
        val srgb=Array(reference.length()){i->IntArray(3){channel->reference.getJSONObject(i).getJSONArray("srgb_code8").getInt(channel)}}
        val p3=Array(reference.length()){i->IntArray(3){channel->reference.getJSONObject(i).getJSONArray("p3_code8").getInt(channel)}}
        assertEquals(4,srgb.size)
        assertFalse("Reference must detect mere relabelling",srgb[1].contentEquals(p3[1]))
        fun index(x:Int,y:Int)=(if(y<32)0 else 2)+(if(x<48)0 else 1)
        fun color(x:Int,y:Int):Int {val rgb=srgb[index(x,y)];return (255 shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]}
        val expected=ByteBuffer.allocate(96*64*12).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until 64)for(x in 0 until 96)for(component in srgb[index(x,y)])expected.putFloat(LinearRgb.srgb8ToLinear[component])
        val queue=ExportQueue(app)
        for((case,name)in listOf("corners-icc-p3.heic","corners-grid-icc-p3-tiles.heic").withIndex()) {
            val fixture=File(root,name);val original=fixture.readBytes()
            assertEquals(name,HeicIccProfile.ColorIdentity.DISPLAY_P3,HeicContainer.inspect(fixture).sourceColor)
            var decodes=0;var closes=0
            val backend=object:HeicBackend {
                override fun supported()=true
                override fun decode(file:File,info:HeicContainer.Info,job:kotlin.coroutines.CoroutineContext):HeicPixels {
                    job.ensureActive();decodes++
                    assertEquals("ICC identity reaches backend before target conversion",HeicIccProfile.ColorIdentity.DISPLAY_P3,info.sourceColor)
                    assertEquals(96,info.displayWidth);assertEquals(64,info.displayHeight)
                    assertArrayEquals(original,file.readBytes())
                    return object:HeicPixels {
                        override val width=96;override val height=64
                        override fun readRow(y:Int,argb:IntArray){for(x in 0 until width)argb[x]=color(x,y)}
                        override fun close(){closes++}
                    }
                }
            }
            val repository=PhotoRepository(app,PhotoCodecs(app.cacheDir,memorySnapshot=memory),HeicImport(memory,backend))
            val uri=Uri.parse("content://dev.filmroom.synthetic-heic-icc/case$case")
            shadowOf(app.contentResolver).registerInputStream(uri,java.io.ByteArrayInputStream(original))
            val loaded=repository.import(uri,96)
            var restored:LoadedPhoto?=null
            try {
                val canonical=checkNotNull(loaded.source.linearFile)
                assertArrayEquals("Canonical sRGB comes from converted markers",expected.array(),canonical.readBytes())
                val greenRed=ByteBuffer.wrap(canonical.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getFloat(48*12)
                assertEquals(LinearRgb.srgb8ToLinear[srgb[1][0]],greenRed,0f)
                assertTrue("Unconverted P3 green red channel must not enter canonical data",
                    kotlin.math.abs(greenRed-LinearRgb.srgb8ToLinear[p3[1][0]])>.01f)
                restored=repository.reopen(loaded.source.cacheKey,"public ICC fixture",48,loaded.source.rawCacheKey)
                assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,HeicContainer.inspect(restored.source.localFile).sourceColor)
                assertEquals(canonical,restored.source.linearFile);assertEquals(1,decodes);assertEquals(1,closes)
                val format=if(case==0)ExportFormat.JPEG else ExportFormat.TIFF16
                val job=queue.enqueue(restored.source,EditorState(),format,null,95)
                assertEquals("LINEAR",job.inputKind)
                val directory=queue.store.directory(job.id)
                assertArrayEquals(expected.array(),File(directory,"input.rgb").readBytes())
                withTimeout(60000){queue.runPending{}}
                val done=checkNotNull(queue.store.get(job.id))
                assertEquals("$name: ${done.message}",ExportJobState.COMPLETED,done.state)
                val published=Uri.parse(done.mediaUri)
                verify(media.file(published),format,96,64)
                assertEquals(0,media.rows[published]!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
                assertFalse(File(directory,"input.rgb").exists())
                assertFalse(directory.walkTopDown().any{it.isFile&&(it.extension=="part"||it.name.startsWith("film-result-")||it.name.startsWith("codec-output-"))})
                assertEquals("Neither restore nor exports re-decodes",1,decodes)
                assertArrayEquals(expected.array(),canonical.readBytes())
                assertArrayEquals(original,loaded.source.localFile.readBytes());assertArrayEquals(original,fixture.readBytes())
            } finally {
                restored?.preview?.recycle();loaded.preview.recycle();repository.discard(loaded.source)
            }
            assertFalse(loaded.source.localFile.exists());assertFalse(loaded.source.linearFile!!.exists())
            assertArrayEquals(original,fixture.readBytes())
        }
        assertEquals(2,media.inserts);assertEquals(2,media.rows.size)
        assertTrue(media.rows.values.all{it.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0})
    }

    @Test fun realQueueRenderEncodePublishRetryAndRejectedPaths()=runBlocking {
        assumeTrue("Opt-in requires Linux host JNI libraries",System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        assertNull(NativeFilmBridge.loadError);assertTrue(NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir,"export-jobs").deleteRecursively()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply{availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false})
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val media=Media();media.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,media)
        app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
        val data=ByteBuffer.allocate(32*24*12).order(ByteOrder.LITTLE_ENDIAN)
        repeat(32*24){i->data.putFloat(.02f+.4f*(i%32)/31);data.putFloat(.18f);data.putFloat(.07f)}
        val source=File(app.cacheDir,"real-source.rgb").apply{writeBytes(data.array())}
        val photo=PhotoSource(source,"fixture","image/dng",32,24,source.length(),linearFile=source)
        var queue=ExportQueue(app)
        for(format in listOf(ExportFormat.JPEG,ExportFormat.TIFF16)) {
            val job=queue.enqueue(photo,EditorState(),format,null,95)
            if(format==ExportFormat.TIFF16) {
                val dir=queue.store.directory(job.id)
                var scanSeen=false
                val result=FilmEngine(app).exportSpool(File(dir,"input.rgb"),32,24,job.parameters,workspace=dir,progressOnMain=false,onProgress={ progress ->
                    progress.scanPreview?.let { file ->
                        assertEquals(6,progress.display.stage)
                        assertEquals(dir.canonicalFile,file.canonicalFile.parentFile)
                        assertTrue(file.name.startsWith("scan-source-"))
                        val thumb=checkNotNull(android.graphics.BitmapFactory.decodeFile(file.path))
                        assertTrue(thumb.width in 1..256 && thumb.height in 1..256)
                        thumb.recycle();scanSeen=true
                    }
                })
                assertTrue("Real stage6 callback exposes decodable full-color scan before encoding",scanSeen)
                assertTrue(File(dir,"checkpoint").isDirectory);result.delete()
                queue.store.update(job.copy(state=ExportJobState.FAILED,progress=ExportDisplayProgress(7),message="old codec root failure"))
                assertTrue(File(dir,"input.rgb").isFile)
                queue=ExportQueue(app) // process recreation reads persisted FAILED checkpoint
                queue.retry(job.id)
            }
            withTimeout(60000){queue.runPending{}}
            val done=queue.store.get(job.id)!!
            assertEquals("${done.message}",ExportJobState.COMPLETED,done.state)
            val uri=Uri.parse(done.mediaUri);verify(media.file(uri),format)
            assertEquals(0,media.rows[uri]!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
            assertFalse(File(queue.store.directory(job.id),"input.rgb").exists())
            // Simulate crash after provider commit but before durable terminal job state.
            queue.store.update(done.copy(state=ExportJobState.RUNNING))
            val restarted=ExportQueue(app);restarted.retry(job.id);restarted.runPending{}
            assertEquals(ExportJobState.COMPLETED,restarted.store.get(job.id)!!.state)
            assertEquals(if(format==ExportFormat.JPEG)1 else 2,media.inserts)
        }
        val jobs=File(app.filesDir,"export-jobs");val job=File(jobs,UUID.randomUUID().toString()).apply{mkdirs()}
        val input=File(job,"film-result-${UUID.randomUUID()}.rgb").apply{writeBytes(data.array())}
        fun output(dir:File=job)=File(dir,"codec-output-${UUID.randomUUID()}.jpg")
        val memory={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val codecs=PhotoCodecs(app.cacheDir,memorySnapshot=memory,exportJobsRoot=jobs)
        val old=NativeCodecBridge.nativeCreate(app.cacheDir.canonicalPath)
        try {assertTrue("old cache root reproduces exact reported failure",NativeCodecBridge.nativeEncode(old,input.path,output().path,"{\"width\":32,\"height\":24,\"format\":\"JPEG\",\"quality\":95,\"memoryBudgetMiB\":128,\"diskBudgetBytes\":1073741824}").contains("INVALID_PATH"))} finally{NativeCodecBridge.nativeDestroy(old)}
        val sibling=File(jobs,UUID.randomUUID().toString()).apply{mkdirs()}
        suspend fun rejected(file:File,dest:File) {try{codecs.encode(file,dest,32,24,"JPEG",95);fail("must reject unsafe scope")}catch(e:PhotoException){assertEquals("INVALID_PATH",e.codecCode?.name)};assertFalse(dest.exists())}
        rejected(input,output(sibling))
        val link=File(job,"film-result-${UUID.randomUUID()}.rgb");Files.createSymbolicLink(link.toPath(),input.toPath());rejected(link,output())
        val invalidJob=File(jobs,"not-a-job").apply{mkdirs()}
        rejected(File(invalidJob,input.name).apply{writeBytes(data.array())},output(invalidJob))
        val aliasJob=File(jobs,UUID.randomUUID().toString());Files.createSymbolicLink(aliasJob.toPath(),job.toPath())
        rejected(File(aliasJob,input.name),output(aliasJob))
        val wrongName=File(job,"input.rgb").apply{writeBytes(data.array())};rejected(wrongName,output())
        val target=File(sibling,"protected.jpg").apply{writeText("KEEP")}
        val outputLink=output();Files.createSymbolicLink(outputLink.toPath(),target.toPath())
        try{codecs.encode(input,outputLink,32,24,"JPEG",95);fail("symlink destination must fail")}catch(e:PhotoException){assertEquals("INVALID_PATH",e.codecCode?.name)}
        assertEquals("KEEP",target.readText());outputLink.delete()
        val insertsBeforeSymlink=media.inserts
        for(unsafe in listOf(link,File(aliasJob,input.name))) {
            try{PhotoRepository(app).exportLinear(unsafe,32,24,ExportFormat.JPEG);fail("repository must preserve lexical symlink rejection")}catch(e:PhotoException){assertEquals("INVALID_PATH",e.codecCode?.name)}
            assertEquals(insertsBeforeSymlink,media.inserts)
        }
        // Real codec rejects nonfinite RGB before any fake provider insertion.
        input.writeBytes(ByteBuffer.allocate(data.capacity()).order(ByteOrder.LITTLE_ENDIAN).apply{repeat(32*24*3){putFloat(Float.NaN)}}.array())
        val before=media.inserts
        try{PhotoRepository(app).exportLinear(input,32,24,ExportFormat.JPEG);fail("nonfinite encode must fail")}catch(_:PhotoException){}
        assertEquals(before,media.inserts);assertEquals(2,media.rows.size)
        assertFalse(job.listFiles()!!.any{it.name.startsWith("codec-output-")})
        // Real sticky cancellation: no output can escape a cancelled codec handle.
        val cancelled=NativeCodecBridge.nativeCreate(job.canonicalPath);val destination=output()
        try{NativeCodecBridge.nativeCancel(cancelled);assertTrue(NativeCodecBridge.nativeEncode(cancelled,input.path,destination.path,"{\"width\":32,\"height\":24,\"format\":\"JPEG\",\"quality\":95}").contains("CANCELLED"));assertFalse(destination.exists())}finally{NativeCodecBridge.nativeDestroy(cancelled)}
        val waiting=queue.enqueue(photo,EditorState(),ExportFormat.JPEG,null,95);queue.cancel(waiting.id);queue.runPending{}
        assertEquals(ExportJobState.CANCELLED,queue.store.get(waiting.id)!!.state);assertEquals(before,media.inserts)
        assertTrue(source.exists())
    }
}
