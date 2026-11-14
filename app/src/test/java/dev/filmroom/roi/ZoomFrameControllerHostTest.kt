package dev.filmroom.roi

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ZoomFrameControllerHostTest {
    private class Provider : ZoomFrameProvider {
        val calls=mutableListOf<Int>()
        val results=mutableListOf<Bitmap>()
        var gate:CompletableDeferred<Unit>?=null
        var failEdge:Int?=null
        var cancelled=0
        @Volatile var closed=false
        override suspend fun render(photo:PhotoSource,parameters:EditorState,baseBitmap:Bitmap,edge:Int):Bitmap {
            calls+=edge
            gate?.let{withContext(NonCancellable){it.await()}}
            if(edge==failEdge)error("budget")
            return Bitmap.createBitmap(edge,edge*3/4,Bitmap.Config.ARGB_8888).also{results+=it}
        }
        override fun cancel(){cancelled++}
        override fun close(){closed=true}
    }
    private class Fixture(timeout:Long=8_000) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val provider=Provider()
        val base=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
        val photo=PhotoSource(File("zoom"),"zoom","image/jpeg",4000,3000,0)
        val params=EditorState(filmBorder=true,filmBorderStage=1,useGpu=true)
        var leases=0
        var pressure:(()->Unit)?=null
        var changed:()->Unit={}
        val controller=ZoomFrameController(provider,scope,{changed()},retain={_,callback->leases++;pressure=callback;AutoCloseable{leases--}},settleMillis=250,refinementTimeoutMillis=timeout)
        fun bind(eligible:Boolean=true,exporting:Boolean=false)=controller.bind(photo,params,base,eligible,exporting=exporting)
        fun close(){controller.close();scope.cancel();base.recycle()}
    }
    private fun pump(ms:Long=300){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))}
    private fun await(test:()->Boolean){repeat(500){pump(10);if(test())return;Thread.sleep(1)};error("Timed out")}
    @Test fun plainPhotoRefinesBeforeAnyExportAndPresentationChangesRetireFrame(){
        val f=Fixture()
        val plain=EditorState(useGpu=true,filmBorder=false)
        try {
            f.controller.bind(f.photo,plain,f.base,true)
            f.controller.viewport(3.0)
            await{f.controller.bitmap?.width==3072}
            assertEquals(listOf(2048,3072),f.provider.calls)
            val ready=f.controller.bitmap!!
            // A post-imaging border must never display the uncomposed photo frame.
            f.controller.bind(f.photo,plain.copy(filmBorder=true,filmBorderStage=0),f.base,true)
            pump(1000)
            assertNull(f.controller.bitmap);assertTrue(ready.isRecycled)
            assertEquals(2,f.provider.calls.size)
        } finally {f.close()}
    }
    @Test fun unsupportedPresentationsAndCpuKeepExistingRoute(){
        val plain=EditorState(useGpu=true,filmBorder=false)
        val unsupported=listOf(
            plain.copy(useGpu=false),
            plain.copy(filmBorder=true,filmBorderStage=0),
            plain.copy(crop=dev.filmroom.model.CropSettings(dev.filmroom.model.CropMode.SQUARE)),
            plain.copy(imprint=plain.imprint.copy(dateEnabled=true))
        )
        assertTrue(ZoomFramePolicy.usesWholeFrame(plain))
        assertTrue(ZoomFramePolicy.usesWholeFrame(plain.copy(filmBorder=true,filmBorderStage=1)))
        for(parameters in unsupported){
            assertFalse(ZoomFramePolicy.usesWholeFrame(parameters))
            val f=Fixture()
            try {
                f.controller.bind(f.photo,parameters,f.base,true);f.controller.viewport(3.0);pump(1000)
                assertTrue(f.provider.calls.isEmpty());assertNull(f.controller.bitmap)
            }finally{f.close()}
        }
    }
    @Test fun firstTierFailureReleasesOwnershipForExistingRoiFallback(){
        val f=Fixture();f.provider.failEdge=2048
        try {
            f.controller.bind(f.photo,EditorState(filmBorder=false,useGpu=true),f.base,true)
            f.controller.viewport(1.5)
            assertTrue(f.controller.ownsDetail)
            await{f.controller.failed}
            assertFalse(f.controller.ownsDetail)
            assertNull(f.controller.bitmap)
        }finally{f.close()}
    }
    @Test fun timeoutDoesNotReleaseRoiOwnershipUntilNativeDrain(){
        val f=Fixture(timeout=500);val gate=CompletableDeferred<Unit>();f.provider.gate=gate
        try {
            f.controller.bind(f.photo,EditorState(filmBorder=false,useGpu=true),f.base,true)
            f.controller.viewport(1.5);await{f.controller.loading};pump(600)
            assertTrue(f.controller.failed);assertTrue(f.controller.ownsDetail)
            gate.complete(Unit);await{!f.controller.ownsDetail}
            assertNull(f.controller.bitmap)
        }finally{gate.complete(Unit);f.close()}
    }
    @Test fun pressureDuringNativeDrainNotifiesRoiOwnershipRelease(){
        val f=Fixture();val gate=CompletableDeferred<Unit>();f.provider.gate=gate
        val observed=mutableListOf<Boolean>();f.changed={observed+=f.controller.ownsDetail}
        try {
            f.controller.bind(f.photo,EditorState(filmBorder=false,useGpu=true),f.base,true)
            f.controller.viewport(1.5);await{f.controller.loading}
            f.controller.releaseForPressure()
            assertTrue(f.controller.ownsDetail);assertTrue(observed.last())
            gate.complete(Unit);await{!f.controller.ownsDetail && observed.last()==false}
            assertNull(f.controller.bitmap)
        }finally{gate.complete(Unit);f.close()}
    }
    @Test fun timedOutTierStopsSpinnerWithoutRetryAndWaitsForNativeDrain(){
        val f=Fixture(timeout=500)
        try {
            f.bind();f.controller.viewport(1.5);await{f.controller.bitmap!=null}
            val ready=f.controller.bitmap
            val gate=CompletableDeferred<Unit>();f.provider.gate=gate
            f.controller.viewport(3.0);await{f.controller.loading}
            pump(600)
            assertFalse(f.controller.loading);assertTrue(f.controller.failed)
            assertSame(ready,f.controller.bitmap);assertEquals(1,f.provider.cancelled)
            repeat(10){f.bind();f.controller.viewport(3.0)}
            assertEquals(listOf(2048,3072),f.provider.calls)
            gate.complete(Unit);await{f.provider.results.size==2}
            pump(1000)
            assertTrue(f.provider.results.last().isRecycled)
            assertSame(ready,f.controller.bitmap);assertEquals(2,f.provider.calls.size)
        }finally{f.close()}
    }
    @Test fun policyNeedsRealExtraSamplesAndUsesBoundedLevels(){
        // Tier requests remain subject to the real budget; 3072 is not promised on 512 MiB.
        assertTrue(dev.filmroom.engine.NativeMemoryPolicy.zoomFrameFloorFits(2048,1365,512))
        assertFalse(dev.filmroom.engine.NativeMemoryPolicy.zoomFrameFloorFits(3072,2048,512))
        assertNull(ZoomFramePolicy.target(1.1,6000,1536))
        assertNull(ZoomFramePolicy.target(Double.NaN,6000,1536))
        assertNull(ZoomFramePolicy.target(3.0,1000,1000))
        assertEquals(2048,ZoomFramePolicy.target(1.5,6000,1536))
        assertEquals(3072,ZoomFramePolicy.target(3.0,6000,1536))
        assertEquals(2048,ZoomFramePolicy.next(3072,0,1536))
        assertEquals(3072,ZoomFramePolicy.next(3072,2048,1536))
        assertNull(ZoomFramePolicy.next(2048,3072,1536))
        assertTrue(ZoomFramePolicy.matchesFrame(2048,1365,1536,1024,2048))
        assertFalse(ZoomFramePolicy.matchesFrame(2048,1200,1536,1024,2048))
        assertFalse(ZoomFramePolicy.matchesFrame(1536,1024,1536,1024,2048))
    }
    @Test fun settlesThenRendersOnceAndPanReusesOwnedFrame(){
        val f=Fixture()
        try {
            f.bind();f.controller.viewport(1.5,true);pump(1000)
            assertTrue(f.provider.calls.isEmpty());assertFalse(f.controller.loading)
            f.controller.viewport(1.5,false);pump(100)
            assertTrue(f.provider.calls.isEmpty());assertFalse(f.controller.loading)
            await{f.controller.bitmap!=null}
            val ready=f.controller.bitmap
            repeat(20){f.bind();f.controller.viewport(1.5,true);f.controller.viewport(1.5,false)}
            pump(1000);assertEquals(listOf(2048),f.provider.calls);assertSame(ready,f.controller.bitmap);assertEquals(1,f.leases)
        }finally{f.close()}
        assertEquals(0,f.leases)
    }
    @Test fun higherTierFailureKeeps2048WithoutAutomaticRetry(){
        val f=Fixture();f.provider.failEdge=3072
        try {
            f.bind();f.controller.viewport(3.0);await{f.controller.failed}
            assertEquals(listOf(2048,3072),f.provider.calls)
            assertEquals(2048,f.controller.bitmap!!.width);assertFalse(f.controller.loading)
            repeat(10){f.bind();f.controller.viewport(3.0)};pump(1000)
            assertEquals(2,f.provider.calls.size);assertEquals(1,f.leases)
        }finally{f.close()}
    }
    @Test fun failureCanRetryAfterLeavingZoomButNotOnRepeatedBind(){
        val f=Fixture();f.provider.failEdge=2048
        try {
            f.bind();f.controller.viewport(1.5);await{f.controller.failed}
            repeat(5){f.bind()};pump(1000);assertEquals(1,f.provider.calls.size)
            f.controller.viewport(1.0);f.provider.failEdge=null;f.controller.viewport(1.5)
            await{f.controller.bitmap!=null};assertEquals(2,f.provider.calls.size)
        }finally{f.close()}
    }
    @Test fun lateCancelledFrameIsRecycledBeforeNewRenderStarts(){
        val f=Fixture();val gate=CompletableDeferred<Unit>();f.provider.gate=gate
        try {
            f.bind();f.controller.viewport(1.5);await{f.controller.loading}
            val replacement=f.params.copy(brightness=.25f)
            f.controller.bind(f.photo,replacement,f.base,true)
            pump(1000);assertEquals(1,f.provider.calls.size)
            f.provider.gate=null;gate.complete(Unit)
            await{f.controller.bitmap!=null}
            assertEquals(2,f.provider.calls.size);assertTrue(f.provider.results.first().isRecycled)
            assertFalse(f.controller.bitmap!!.isRecycled)
        }finally{gate.complete(Unit);f.close()}
    }
    @Test fun repeatedlyCancelledWaitersCannotBreakNativeDrainChain(){
        val f=Fixture();val gate=CompletableDeferred<Unit>();f.provider.gate=gate
        try {
            f.bind();f.controller.viewport(1.5);await{f.controller.loading}
            repeat(3){ index->
                f.controller.bind(f.photo,f.params.copy(brightness=(index+1)*.1f),f.base,true)
                assertEquals(1,f.provider.calls.size)
            }
            pump(1000);assertEquals(1,f.provider.calls.size)
            f.provider.gate=null;gate.complete(Unit)
            await{f.controller.bitmap!=null}
            assertEquals(2,f.provider.calls.size)
            assertTrue(f.provider.results.first().isRecycled)
        }finally{gate.complete(Unit);f.close()}
    }
    @Test fun exportComparisonAndBackgroundRemoveOverlayAndLease(){
        val f=Fixture()
        try {
            f.bind();f.controller.viewport(1.5);await{f.controller.bitmap!=null}
            val first=f.controller.bitmap!!;f.bind(exporting=true)
            assertNull(f.controller.bitmap);assertTrue(first.isRecycled);assertEquals(0,f.leases)
            f.bind();await{f.controller.bitmap!=null}
            f.controller.bind(f.photo,f.params,f.base,true,compareOriginal=true)
            assertNull(f.controller.bitmap);assertEquals(0,f.leases)
            f.bind();await{f.controller.bitmap!=null};f.controller.foreground(false)
            assertNull(f.controller.bitmap);assertEquals(0,f.leases)
        }finally{f.close()}
    }
    @Test fun pressureEvictsOverlayWithoutRestartingWork(){
        val f=Fixture()
        try {
            f.bind();f.controller.viewport(1.5);await{f.controller.bitmap!=null}
            f.pressure!!.invoke();pump()
            assertNull(f.controller.bitmap);assertEquals(0,f.leases);assertEquals(1,f.provider.calls.size)
            repeat(10){f.bind();f.controller.viewport(1.5,true);f.controller.viewport(1.5,false)}
            pump(1000);assertEquals(1,f.provider.calls.size);assertTrue(f.controller.failed)
            f.controller.viewport(1.0);f.controller.viewport(1.5)
            await{f.controller.bitmap!=null};assertEquals(2,f.provider.calls.size)
        }finally{f.close()}
    }
    @Test fun explicitPressureBlocksRebindButForegroundReentryCanRetry(){
        val f=Fixture()
        try {
            f.bind();f.controller.viewport(1.5);await{f.controller.bitmap!=null}
            f.controller.releaseForPressure();assertNull(f.controller.bitmap);assertEquals(0,f.leases)
            repeat(10){f.bind();f.controller.viewport(1.6)};pump(1000)
            assertEquals(1,f.provider.calls.size)
            f.controller.foreground(false);f.controller.foreground(true)
            await{f.controller.bitmap!=null};assertEquals(2,f.provider.calls.size)
        }finally{f.close()}
    }
    @Test fun pressureBlockDoesNotPreventNewScientificRequest(){
        val f=Fixture()
        try {
            f.bind();f.controller.viewport(1.5);await{f.controller.bitmap!=null}
            f.controller.releaseForPressure()
            f.controller.bind(f.photo,f.params.copy(brightness=.2f),f.base,true)
            await{f.controller.bitmap!=null};assertEquals(2,f.provider.calls.size)
        }finally{f.close()}
    }
    @Test fun closeWaitsForNonCooperativeRenderThenClosesProvider(){
        val f=Fixture();val gate=CompletableDeferred<Unit>();f.provider.gate=gate
        f.bind();f.controller.viewport(1.5);await{f.controller.loading}
        f.close();assertFalse(f.provider.closed)
        gate.complete(Unit);await{f.provider.closed}
        assertTrue(f.provider.results.all{it.isRecycled});assertEquals(0,f.leases)
    }
}
