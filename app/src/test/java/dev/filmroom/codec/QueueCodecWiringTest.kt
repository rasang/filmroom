package dev.filmroom.codec

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.diagnostics.*
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class QueueCodecWiringTest {
    private val memory={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
    private class Api:CodecNativeApi {
        override val available=true
        val roots=mutableListOf<File>();var destroys=0;var calls=0
        override fun nativeCreate(root:String):Long {roots+=File(root);return roots.size.toLong()}
        override fun nativeCancel(handle:Long){}
        override fun nativeDestroy(handle:Long){destroys++}
        override fun nativeProbe(handle:Long,input:String,budget:String)="{\"ok\":true}"
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String)="{\"ok\":true}"
        override fun nativeEncode(handle:Long,input:String,output:String,parameters:String):String {
            calls++;assertEquals(File(input).parentFile,roots[(handle-1).toInt()])
            return "{\"ok\":false,\"code\":\"INVALID_PATH\",\"stage\":\"validate\",\"message\":\"private-photo-secret\"}"
        }
    }
    @Test fun defaultRepositoryFactoryExplicitlyConfiguresPersistentJobs() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val repo=PhotoRepository(app)
        val field=PhotoRepository::class.java.getDeclaredField("codecs").apply{isAccessible=true}
        val codecs=field.get(repo)
        val jobs=PhotoCodecs::class.java.getDeclaredField("exportJobsRoot").apply{isAccessible=true}.get(codecs) as File
        assertEquals(File(app.filesDir,"export-jobs").canonicalFile,jobs.canonicalFile)
        val cache=PhotoCodecs::class.java.getDeclaredField("root").apply{isAccessible=true}.get(codecs) as File
        assertEquals(app.cacheDir.canonicalFile,cache.canonicalFile)
    }
    @Test fun queueEncodeUsesJobHandleButImportStillUsesCacheAndFailureIsTraced()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>();Diagnostics.initialize(app)
        val jobs=File(app.filesDir,"export-jobs").apply{mkdirs()}
        val job=File(jobs,UUID.randomUUID().toString()).apply{mkdir()}
        val input=File(job,"film-result-${UUID.randomUUID()}.rgb").apply{writeBytes(ByteArray(12))}
        val api=Api();val codecs=PhotoCodecs(app.cacheDir,api,memorySnapshot=memory,exportJobsRoot=jobs)
        try {
            codecs.probe(File(app.cacheDir,"source"))
            assertEquals(app.cacheDir.canonicalFile,api.roots.single())
            try{PhotoRepository(app,codecs).exportLinear(input,1,1,ExportFormat.JPEG);fail()}
            catch(e:PhotoException){assertEquals(PhotoException.Reason.EXPORT,e.reason);assertEquals(CodecErrorCode.INVALID_PATH,e.codecCode);assertFalse(e.message!!.contains("private-photo-secret"))}
            assertEquals(job.canonicalFile,api.roots.last());assertEquals(1,api.calls);assertEquals(2,api.destroys)
            assertTrue(input.exists());assertFalse(job.listFiles()!!.any{it.name.startsWith("codec-output-")})
            val share=Diagnostics.prepareShare(5)
            try {
                val text=share.file.readText()
                assertTrue(text.contains("EXPORT_ENCODE"));assertTrue(text.contains("INVALID_PATH"));assertTrue(text.contains("codecStage"));assertTrue(text.contains("validate"))
                assertFalse(text.contains("private-photo-secret"));assertFalse(text.contains(input.name));assertFalse(text.contains(job.path))
            }finally{share.file.delete()}
        }finally{job.deleteRecursively()}
    }
    @Test fun repositoryDoesNotEraseInputOrJobSymlinksBeforeScopeValidation()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val jobs=File(app.filesDir,"export-jobs").apply{mkdirs()}
        val job=File(jobs,UUID.randomUUID().toString()).apply{mkdir()}
        val input=File(job,"film-result-${UUID.randomUUID()}.rgb").apply{writeBytes(ByteArray(12))}
        val inputLink=File(job,"film-result-${UUID.randomUUID()}.rgb")
        val jobLink=File(jobs,UUID.randomUUID().toString())
        java.nio.file.Files.createSymbolicLink(inputLink.toPath(),input.toPath())
        java.nio.file.Files.createSymbolicLink(jobLink.toPath(),job.toPath())
        val api=Api();val repo=PhotoRepository(app,PhotoCodecs(app.cacheDir,api,memorySnapshot=memory,exportJobsRoot=jobs))
        try {
            for(path in listOf(inputLink,File(jobLink,input.name))) {
                try{repo.exportLinear(path,1,1,ExportFormat.JPEG);fail()}
                catch(e:PhotoException){assertEquals(CodecErrorCode.INVALID_PATH,e.codecCode)}
            }
            assertEquals(0,api.calls);assertTrue(api.roots.isEmpty());assertTrue(input.exists())
        }finally{inputLink.delete();jobLink.delete();job.deleteRecursively()}
    }

}
