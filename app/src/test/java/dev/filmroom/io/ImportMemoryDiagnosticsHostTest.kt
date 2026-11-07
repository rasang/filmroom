package dev.filmroom.io

import dev.filmroom.codec.CodecNativeApi
import dev.filmroom.codec.PhotoCodecs
import dev.filmroom.diagnostics.*
import dev.filmroom.engine.NativeMemorySnapshot
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Injected allocation/OS snapshots and fake codec. Does not claim a device OOM reproduction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ImportMemoryDiagnosticsHostTest {
    private val mib=1024L*1024
    private class Api:CodecNativeApi {
        override val available=true
        var creates=0;var calls=0;var destroys=0
        override fun nativeCreate(root:String):Long{creates++;return 1}
        override fun nativeProbe(handle:Long,input:String,budget:String)="{\"ok\":true}"
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String{calls++;return "{\"ok\":false,\"error\":{\"code\":\"MEMORY_BUDGET\",\"stage\":\"decode\"}}"}
        override fun nativeEncode(handle:Long,input:String,output:String,parameters:String)=nativeDecode(handle,input,output,parameters)
        override fun nativeCancel(handle:Long){}
        override fun nativeDestroy(handle:Long){destroys++}
    }
    private suspend fun files(block:suspend(File)->Unit){val root=kotlin.io.path.createTempDirectory("import-memory").toFile();try{block(root)}finally{root.deleteRecursively()}}
    @Test fun budgetSelectionRejectsWithoutCreatingNativeHandleAndKeepsExactSnapshot()=runBlocking {
        files { root ->
            val observation=ImportObservation();val api=Api()
            val snapshot=NativeMemorySnapshot(400*mib,128*mib,false)
            withContext(observation){
                val codec=PhotoCodecs(root,api,memoryBudgetMiB={ImportObservation.current()?.budgetSnapshot=snapshot;0})
                try{codec.decode(File(root,"secret.ARW"),File(root,"out"));fail()}catch(_:PhotoException){}
            }
            assertEquals(0,api.creates);assertEquals(0,api.calls)
            assertEquals(ImportMemoryFailure.CODEC_BUDGET_SELECTION,observation.memory?.failure)
            assertEquals(0,observation.memory?.selectedBudgetMiB)
            assertEquals(snapshot.availableBytes,observation.memory?.availableBytes)
            assertEquals(ImportMemorySnapshotStage.BUDGET_SELECTION,observation.memory?.snapshotStage)
            assertEquals(false,observation.memory?.admitted)
        }
    }
    @Test fun sharedRefusalAndNativeBudgetAreDistinctAndHandlesAlwaysClose()=runBlocking {
        files { root ->
            for(low in listOf(true,false)){
                val api=Api();val observation=ImportObservation();val snapshot=NativeMemorySnapshot(8L*1024*mib,128*mib,low)
                var snapshots=0
                withContext(observation){
                    val codec=PhotoCodecs(root,api,memorySnapshot={snapshots++;snapshot})
                    try{codec.decode(File(root,"secret.ARW"),File(root,"out"));fail()}catch(e:PhotoException){if(!low)assertEquals(CodecErrorCode.MEMORY_BUDGET,e.codecCode)}
                }
                assertEquals(1,snapshots);assertEquals(1,api.creates);assertEquals(1,api.destroys)
                assertEquals(if(low)0 else 1,api.calls)
                assertEquals(if(low)ImportMemoryFailure.SHARED_ADMISSION else ImportMemoryFailure.NATIVE_CODEC_BUDGET,observation.memory?.failure)
                assertEquals(if(low)ImportMemoryStage.CODEC_ADMISSION else ImportMemoryStage.CODEC_NATIVE,observation.memoryStage)
                assertEquals(snapshot.availableBytes,observation.memory?.availableBytes)
                assertEquals(!low,observation.memory?.admitted)
                assertEquals(ImportMemorySnapshotStage.SHARED_ADMISSION,observation.memory?.snapshotStage)
                val saved=observation.memory
                observation.budgetSnapshot=NativeMemorySnapshot(1,0,true)
                assertEquals(saved,observation.memory)
            }
        }
    }
    @Test fun heicFixedEstimateAndPlatformAllocationRemainDistinct()=runBlocking {
        val estimate=ImportObservation()
        withContext(estimate){try{HeicMemoryPolicy.estimatedBytes(HeicContainer.Info(8192,6144,0,50_331_648,50_331_648),PhotoLimits.MAX_COMPRESSED_BYTES);fail()}catch(_:PhotoException){}}
        assertEquals(ImportMemoryFailure.HEIC_ESTIMATE_LIMIT,estimate.memory?.failure)
        assertTrue(estimate.memory!!.estimatedBytes!!>2L*1024*mib)
        files { root ->
            val source=File(root,"secret.heic").apply{writeBytes(byteArrayOf(1))}
            for(low in listOf(true,false)){
                val observation=ImportObservation();var calls=0
                val backend=object:HeicBackend {
                    override fun supported()=true
                    override fun decode(file:File,info:HeicContainer.Info,job:CoroutineContext):HeicPixels{calls++;throw OutOfMemoryError("secret-file-and-stack")}
                }
                val target=File(root,"out")
                withContext(observation){try{HeicImport({NativeMemorySnapshot(8L*1024*mib,128*mib,low)},backend).write(source,HeicContainer.Info(2,2,0,4,4),target,coroutineContext);fail()}catch(_:PhotoException){}}
                assertEquals(if(low)0 else 1,calls);assertFalse(target.exists())
                assertEquals(if(low)ImportMemoryFailure.HEIC_ADMISSION else ImportMemoryFailure.HEIC_PLATFORM_ALLOCATION,observation.memory?.failure)
                assertFalse(observation.memory!!.fields().toString().contains("secret"))
            }
        }
    }
    @Test fun contextFollowsDispatchersRestoresNestedImportsAndDoesNotLeak()=runBlocking {
        val outer=ImportObservation();val inner=ImportObservation()
        assertNull(ImportObservation.current())
        withContext(outer){
            assertSame(outer,ImportObservation.current())
            withContext(Dispatchers.IO){assertSame(outer,ImportObservation.current());outer.dimensions(12,34)}
            withContext(inner+Dispatchers.Default){assertSame(inner,ImportObservation.current());inner.dimensions(5,6)}
            assertSame(outer,ImportObservation.current());assertEquals(12,outer.width);assertEquals(5,inner.width)
        }
        assertNull(ImportObservation.current())
    }
}
