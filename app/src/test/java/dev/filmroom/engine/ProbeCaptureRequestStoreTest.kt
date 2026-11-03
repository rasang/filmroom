package dev.filmroom.engine

import dev.filmroom.BuildConfig
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ProbeCaptureRequestStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun setup(){ProbeCaptureRequestStore.cancelPending();File(context.cacheDir,"gpu-probes").deleteRecursively()}
    @Test fun explicitArmIsOneShotAndUnknownFilesAreNotRemoved(){
        if(!BuildConfig.PERF_BUILD){assertFalse(ProbeCaptureRequestStore.arm(context));return}
        assertNull(ProbeCaptureRequestStore.consume(context))
        assertTrue(ProbeCaptureRequestStore.arm(context))
        val request=ProbeCaptureRequestStore.consume(context)!!
        assertTrue(request.token>0);assertEquals("probe-${request.token}",request.directory.name)
        assertNull(ProbeCaptureRequestStore.consume(context))
        ProbeCaptureRequestStore.finish(request,null,false)
        val unknown=File(context.cacheDir,"gpu-probes/unknown").apply{writeText("do not remove")}
        assertFalse(ProbeCaptureRequestStore.arm(context));assertTrue(unknown.isFile)
        unknown.delete()
    }
    @Test fun nativeRejectedCollisionDoesNotGrantDeletionOwnership(){
        if(!BuildConfig.PERF_BUILD)return
        assertTrue(ProbeCaptureRequestStore.arm(context));val request=ProbeCaptureRequestStore.consume(context)!!
        request.directory.mkdir();val existing=File(request.directory,"manifest.json").apply{writeText("existing owner")}
        ProbeCaptureRequestStore.finish(request,JSONObject("{\"gpuProbeCapture\":{\"status\":\"skipped_failed\"}}"),true)
        assertEquals("existing owner",existing.readText());request.directory.deleteRecursively()
    }
    @Test fun cancelledCaptureNeverBecomesReady(){
        if(!BuildConfig.PERF_BUILD)return
        assertTrue(ProbeCaptureRequestStore.arm(context));val request=ProbeCaptureRequestStore.consume(context)!!
        request.directory.mkdir();File(request.directory,"manifest.json").writeText("{}")
        ProbeCaptureRequestStore.cancelPending()
        ProbeCaptureRequestStore.finish(request,JSONObject("{\"gpuProbeCapture\":{\"status\":\"captured\"}}"),true)
        assertNull(ProbeCaptureRequestStore.readyToken());assertFalse(request.directory.exists())
    }
    @Test fun onlySuccessfulCapturedResultTransfersOwnership(){
        if(!BuildConfig.PERF_BUILD)return
        assertTrue(ProbeCaptureRequestStore.arm(context));val request=ProbeCaptureRequestStore.consume(context)!!
        request.directory.mkdir();File(request.directory,"manifest.json").writeText("{}")
        ProbeCaptureRequestStore.finish(request,JSONObject("{\"metrics\":{\"gpuProbeCapture\":{\"status\":\"captured\"}}}"),true)
        assertEquals(request.token,ProbeCaptureRequestStore.readyToken())
        ProbeCaptureRequestStore.transferred(request.token)
        ProbeCaptureRequestStore.cancelPending();assertTrue(request.directory.exists())
        assertFalse(ProbeCaptureRequestStore.summary().contains(request.token.toString()))
        request.directory.deleteRecursively()
    }
}
