package dev.filmroom

import android.app.ActivityManager
import android.app.Application
import dev.filmroom.border.*
import dev.filmroom.engine.*
import dev.filmroom.export.*
import dev.filmroom.io.*
import dev.filmroom.model.EditorState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PreImagingQueueHostTest {
    private fun checkOrder(stage:Int)=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        File(app.filesDir,"export-jobs").deleteRecursively()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply{
            availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val source=File(app.cacheDir,"pre-imaging-scene.rgb")
        val data=ByteBuffer.allocate(96*64*12).order(ByteOrder.LITTLE_ENDIAN).apply{repeat(96*64*3){putFloat(.1234567f)}}.array()
        source.writeBytes(data)
        var calls=0;var planCalls=0
        val raster=object:BorderRaster {
            override val stock="kodak_portra_400";override val version="queue-fixture"
            override val railRatio=.2;override val endRatio=.03;override val featherTotalAt3072=0.0;override val residentBytes=12L
            override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(.8f)}
        }
        val provider=object:BorderProvider {
            override fun snapshot(stock:String,width:Int,height:Int)=BorderSnapshot.create(stock,raster.version,width,height,.2,.03,0.0)
            override fun resolve(snapshot:BorderSnapshot)=raster
        }
        val processing=object:ExportProcessing {
            override suspend fun plan(width:Int,height:Int,parameters:EditorState):NativeExportPlan {
                assertEquals(0,stage);planCalls++;assertEquals(96,width);assertEquals(64,height)
                val n=width.toLong()*height;return NativeExportPlan(width,height,n*12,n*12,0,n*24)
            }
            override suspend fun planPreImaging(width:Int,height:Int,parameters:EditorState,preImaging:JSONObject):NativeExportPlan {
                assertEquals(1,stage);planCalls++;assertEquals(width,preImaging.getInt("frameWidth"));assertEquals(height,preImaging.getInt("frameHeight"))
                assertEquals(96,preImaging.getInt("meteringWidth"));assertEquals(64,preImaging.getInt("meteringHeight"))
                val n=width.toLong()*height;return NativeExportPlan(width,height,n*12,n*12,0,n*24)
            }
            override suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit):File {
                assertEquals(0,stage);calls++;assertArrayEquals(data,input.readBytes())
                throw java.io.IOException("Intentional stop after observing native input")
            }
            override suspend fun renderPreImaging(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,diskReserveBytes:Long,preImaging:JSONObject,onProgress:(EngineProgress)->Unit):File {
                assertEquals(1,stage);calls++;assertTrue(width>96 || height>64)
                assertEquals(width.toLong()*height*12,input.length());assertTrue(diskReserveBytes>input.length())
                val meter=File(preImaging.getString("meteringPath"));assertNotEquals(input.canonicalPath,meter.canonicalPath);assertArrayEquals(data,meter.readBytes())
                val values=ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                assertTrue(values.get(0)>.5f);assertTrue((0 until values.limit()).any{values.get(it)==.1234567f})
                throw java.io.IOException("Intentional stop after observing native input")
            }
            override fun cancel(){}
        }
        try {
            val queue=ExportQueue(app,processing,borderProvider=provider)
            val photo=PhotoSource(source,"fixture","application/octet-stream",96,64,source.length(),linearFile=source)
            val job=queue.enqueue(photo,EditorState(filmBorder=true,filmBorderStage=stage,autoExposure=true),ExportFormat.PNG,null,95)
            queue.runPending{}
            assertEquals(1,calls);assertEquals(1,planCalls);assertEquals(ExportJobState.FAILED,queue.store.get(job.id)!!.state)
            assertArrayEquals(data,source.readBytes());assertFalse(File(queue.store.directory(job.id),"pre-imaging-input.rgb").exists())
            assertFalse(queue.store.directory(job.id).listFiles()!!.any{it.name.endsWith(".part")})
        }finally{source.delete();File(app.filesDir,"export-jobs").deleteRecursively()}
    }
    @Test fun newJobComposesSourceAndEdgeBeforeEngineAndKeepsSeparateMeter()=checkOrder(1)
    @Test fun frozenLegacyJobStillSendsOnlyTheOriginalToEngine()=checkOrder(0)
}
