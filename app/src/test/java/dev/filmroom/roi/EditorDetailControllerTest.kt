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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class EditorDetailControllerTest {
    class FakeSession(override val key: RenderKey,override val regionOutput:Boolean=true) : EditorDetailSession {
        @Volatile var closed=false
        var retire:(()->Unit)?=null
        var retirementGate:CompletableDeferred<Unit>?=null
        val results=CopyOnWriteArrayList<TileResult>()
        val calls=CopyOnWriteArrayList<Pair<Int,Int>>()
        var failure:Throwable?=null
        var block:CountDownLatch?=null
        val entered=CountDownLatch(1)
        override fun onRetired(listener:()->Unit){retire=listener}
        override suspend fun awaitRetired(){retirementGate?.await()}
        override suspend fun tile(x:Int,y:Int):TileResult {
            failure?.let{throw it}
            check(Looper.myLooper()!=Looper.getMainLooper())
            calls+=x to y;entered.countDown();block?.await(10,TimeUnit.SECONDS)
            val core=TileId(x,y).rect(key);val sample=core.sampleRect(key)
            return TileResult(key,core,sample,Bitmap.createBitmap(sample.width,sample.height,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff88aa44.toInt())},TileQuality.Exact).also{results+=it}
        }
        override suspend fun region(rect:PixelRect,cancelled:()->Boolean):TileResult {
            failure?.let{throw it};check(Looper.myLooper()!=Looper.getMainLooper())
            calls+=rect.left to rect.top;entered.countDown();block?.await(10,TimeUnit.SECONDS)
            val sample=rect.sampleRect(key)
            return TileResult(key,rect,sample,Bitmap.createBitmap(sample.width,sample.height,Bitmap.Config.ARGB_8888),TileQuality.Exact).also{results+=it}
        }
        override fun close(){check(Looper.myLooper()!=Looper.getMainLooper());closed=true}
    }
    class FakeProvider : EditorDetailProvider {
        val sessions=CopyOnWriteArrayList<FakeSession>()
        var openFailure:Throwable?=null
        var tileFailure:Throwable?=null
        var openCalls=0
        val initialRegions=CopyOnWriteArrayList<PixelRect?>()
        var regionOutput=true
        var legacyCalls=0
        var retirementGate:CompletableDeferred<Unit>?=null
        var tileBlock:CountDownLatch?=null
        var openGate:CompletableDeferred<Unit>?=null
        override suspend fun open(source:PhotoSource,parameters:EditorState):EditorDetailSession = openMode(source,parameters,regionOutput)
        override suspend fun openWithInitialRegion(source:PhotoSource,parameters:EditorState,initialRegion:PixelRect?):EditorDetailSession {initialRegions+=initialRegion;return open(source,parameters)}
        override suspend fun openLegacy(source:PhotoSource,parameters:EditorState):EditorDetailSession {legacyCalls++;return openMode(source,parameters,false)}
        private suspend fun openMode(source:PhotoSource,parameters:EditorState,region:Boolean):EditorDetailSession {
            openCalls++;openFailure?.let{throw it}
            // Deliberately non-cooperative provider proves stale opening ownership is still retired.
            openGate?.let{withContext(NonCancellable){it.await()}}
            return FakeSession(RenderKey(source.cacheKey,parameters.json(),"a","d","orientation-source",1024,768),region).also{it.block=tileBlock;it.failure=tileFailure;it.retirementGate=retirementGate;sessions+=it}
        }
    }
    private fun photo(id:String="source")=PhotoSource(File(id),id,"image/jpeg",4001,3001,0)
    private fun viewport()=Viewport().apply{size(400,300);sourceAspect(4.0/3);pinch(3.0,200.0,150.0)}
    private fun await(test:()->Boolean){repeat(1000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(test())return;Thread.sleep(2)};error("Timed out")}
    @Test fun settleOnlyAndPanReusesContextAndCachedCells(){
        val p=FakeProvider();val v=viewport();val c=EditorDetailController(p,v,{})
        c.bind(photo(),EditorState(),true)
        repeat(50){v.drag(1.0,0.0);c.demand(true)}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(0,p.sessions.size)
        c.demand(false);await{c.cacheBytes>0};val session=p.sessions.single()
        await{session.calls.size==1};val initial=c.key
        repeat(100){v.drag(if(it%2==0)1.0 else -1.0,0.0);c.demand(true)}
        c.demand(false);await{c.viewportRevision>=100}
        assertSame(initial,c.key);assertEquals(1,p.sessions.size)
        c.close();await{session.closed};assertEquals(0,c.cacheBytes)
    }
    @Test fun parameterThenSourceChangesNeverPublishLateTileAndCloseOffMain(){
        val gate=CountDownLatch(1);val p=FakeProvider().apply{tileBlock=gate};val v=viewport();val c=EditorDetailController(p,v,{})
        val a=photo();c.bind(a,EditorState(),true);await{p.sessions.firstOrNull()?.entered?.count==0L}
        val old=p.sessions.single()
        c.bind(a,EditorState(brightness=.3f),true);c.bind(photo("new"),EditorState(brightness=.4f),true)
        p.tileBlock=null;gate.countDown()
        await{old.results.isNotEmpty() && old.results.all{it.bitmap.isRecycled} && c.key?.sourceRevision=="new"}
        assertTrue(old.closed);assertEquals("new",c.key!!.sourceRevision)
        c.close()
    }
    @Test fun pansWhileNativeOpenRunsDoNotRestartContextOrAccumulateOpens(){
        val gate=CompletableDeferred<Unit>();val p=FakeProvider().apply{openGate=gate};val v=viewport();val c=EditorDetailController(p,v,{})
        c.bind(photo(),EditorState(),true);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1,p.openCalls)
        repeat(100){v.drag(1.0,0.0);c.demand(true);c.demand(false)}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,p.openCalls)
        gate.complete(Unit);await{c.cacheBytes>0};assertEquals(1,p.sessions.size);c.close()
    }
    @Test fun absentAbiOnOpenOrTileKeepsUnderlayAndDoesNotCrashOrRetry(){
        val p=FakeProvider().apply{openFailure=UnsatisfiedLinkError("missing owned ABI")}
        val c=EditorDetailController(p,viewport(),{});c.bind(photo(),EditorState(),true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,p.openCalls);assertNull(c.key)
        repeat(20){c.demand(false)};shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,p.openCalls);c.close()
        val tiles=FakeProvider().apply{tileFailure=UnsatisfiedLinkError("missing tile symbol")}
        val d=EditorDetailController(tiles,viewport(),{});d.bind(photo(),EditorState(),true)
        await{tiles.sessions.firstOrNull()?.closed==true};assertNull(d.key);assertEquals(0,d.cacheBytes);d.close()
    }
    @Test fun preparingAndSanitizedFallbackAreObservableWithoutChangingGeneration(){
        val gate=CompletableDeferred<Unit>();val p=FakeProvider().apply{openGate=gate};val c=EditorDetailController(p,viewport(),{})
        c.bind(photo(),EditorState(),true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(DetailPhase.Preparing,c.status.phase)
        gate.complete(Unit);await{c.status.phase==DetailPhase.Ready}
        assertEquals(1024,c.status.width);assertEquals(768,c.status.height);assertEquals(1,p.openCalls);c.close()
        val failed=FakeProvider().apply{openFailure=IllegalArgumentException("/data/user/0/private/photo.heic")}
        val fallback=EditorDetailController(failed,viewport(),{});fallback.bind(photo(),EditorState(),true)
        await{fallback.status.phase==DetailPhase.Fallback}
        assertEquals(DetailFailure.SourceValidation,fallback.status.failure)
        assertFalse(fallback.status.label().contains("private"));assertTrue(fallback.status.label().contains("源文件校验失败"));fallback.close()
    }
    @Test fun openAfterCloseIsRetiredWithoutPublishing(){
        val gate=CompletableDeferred<Unit>();val p=FakeProvider().apply{openGate=gate};val c=EditorDetailController(p,viewport(),{})
        c.bind(photo(),EditorState(),true);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));c.close();gate.complete(Unit)
        await{p.sessions.firstOrNull()?.closed==true};assertNull(c.key);assertEquals(0,c.cacheBytes)
    }
    @Test fun pressureRetirementDropsCacheAndDoesNotImmediatelyReopen(){
        val p=FakeProvider();val c=EditorDetailController(p,viewport(),{})
        c.bind(photo(),EditorState(),true);await{c.cacheBytes>0};val session=p.sessions.single()
        session.retire!!.invoke();await{c.key==null && session.closed};c.demand(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,p.sessions.size);assertEquals(0,c.cacheBytes);c.close()
    }
    @Test fun compareRoundTripKeepsExactCacheButSourceOrParameterChangeInvalidates(){
        val p=FakeProvider();val v=viewport();val c=EditorDetailController(p,v,{})
        val source=photo();val params=EditorState();c.bind(source,params,true);await{c.cacheBytes>0}
        val first=p.sessions.single();val cached=c.cacheBytes
        c.bind(source,params,true,compareOriginal=true)
        assertNull(c.key);assertFalse(first.closed);assertTrue(c.cacheBytes>=cached)
        var drawn=0;c.forEachDrawable{drawn++};assertEquals(0,drawn)
        c.bind(source,params,true,compareOriginal=false);assertEquals(first.key,c.key);assertEquals(1,p.openCalls)
        c.bind(source,params,true,compareOriginal=true)
        c.bind(source,params.copy(brightness=.2f),true,compareOriginal=true)
        await{first.closed};assertNull(c.key);assertEquals(0,c.cacheBytes)
        c.bind(source,params.copy(brightness=.2f),true);await{p.sessions.size==2 && c.cacheBytes>0}
        val second=p.sessions.last();c.bind(photo("different"),params,true,true)
        await{second.closed};assertNull(c.key);assertEquals(0,c.cacheBytes);c.close()
    }
    @Test fun compareBackgroundAndFitZoomReleaseContextKeepUnderlayIndependent(){
        val p=FakeProvider();val v=viewport();val c=EditorDetailController(p,v,{})
        val source=photo();c.bind(source,EditorState(),true);await{c.cacheBytes>0}
        c.bind(source,EditorState(),false);await{p.sessions.single().closed};assertNull(c.key)
        c.bind(source,EditorState(),true);await{p.sessions.size==2 && c.key!=null}
        c.foreground(false);await{p.sessions.last().closed};assertEquals(0,c.cacheBytes)
        c.foreground(true);await{p.sessions.size==3 && c.key!=null};v.reset();c.demand(false)
        await{p.sessions.last().closed};assertNull(c.key);c.close()
    }
    @Test fun resourceRecoveryIsBoundedAndWaitsForIdleButOomNeverLoops(){
        var idle=false
        val p=FakeProvider().apply{openFailure=dev.filmroom.engine.NativeWorkUnavailable()}
        val c=EditorDetailController(p,viewport(),{},settleMillis=1,retryIdle={idle},retryDelays=listOf(10L,20L))
        c.bind(photo(),EditorState(),true);await{c.status.phase==DetailPhase.Fallback}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(1,p.openCalls)
        idle=true;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));assertEquals(3,p.openCalls)
        repeat(20){c.demand(false)};shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3));assertEquals(3,p.openCalls);c.close()
        val oom=FakeProvider().apply{openFailure=OutOfMemoryError()}
        val d=EditorDetailController(oom,viewport(),{},settleMillis=1,retryDelays=listOf(10L))
        d.bind(photo(),EditorState(),true);await{d.status.phase==DetailPhase.Fallback}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3));assertEquals(1,oom.openCalls);assertEquals(DetailFailure.AllocationFailed,d.status.failure);d.close()
    }

    @Test fun admittedLegacyModeKeepsSame1536GridAndNeverRetriesRegionOnPan(){
        val p=FakeProvider().apply{regionOutput=false};val v=viewport();val c=EditorDetailController(p,v,{})
        c.bind(photo(),EditorState(),true);await{c.status.phase==DetailPhase.Ready}
        val session=p.sessions.single();assertFalse(session.regionOutput)
        assertTrue(session.results.all{it.coreRect.width<=256 && it.coreRect.height<=256})
        repeat(20){v.drag(if(it%2==0)2.0 else -2.0,0.0);c.demand(true)};c.demand(false)
        await{c.status.phase==DetailPhase.Ready};assertEquals(1,p.openCalls);assertEquals(1024,c.key!!.virtualFullWidth);c.close()
    }

    @Test fun dynamicRegionAdmissionFailureWaitsForFullRetirementThenStaysLegacy(){
        val gate=CompletableDeferred<Unit>()
        val p=FakeProvider().apply{tileFailure=dev.filmroom.engine.NativeWorkUnavailable();retirementGate=gate}
        val v=viewport();val c=EditorDetailController(p,v,{},settleMillis=1,retryDelays=listOf(10L,20L))
        val source=photo();val params=EditorState()
        try {
            c.bind(source,params,true);await{c.status.phase==DetailPhase.Fallback && p.sessions.first().closed}
            p.tileFailure=null
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));assertEquals("Cannot rebuild while old native/Java owner is draining",1,p.openCalls)
            gate.complete(Unit);await{p.legacyCalls==1 && c.status.phase==DetailPhase.Ready}
            assertFalse(p.sessions.last().regionOutput)
            repeat(20){v.drag(if(it%2==0)1.0 else -1.0,0.0);c.demand(true)};c.demand(false)
            await{c.status.phase==DetailPhase.Ready};assertEquals(2,p.openCalls)
            c.foreground(false);c.foreground(true);await{p.legacyCalls==2 && c.status.phase==DetailPhase.Ready}
            assertFalse(p.sessions.last().regionOutput)
            c.bind(source,params.copy(brightness=.25f),true);await{p.sessions.size==4 && c.status.phase==DetailPhase.Ready}
            assertTrue("New science binding can choose ROI again",p.sessions.last().regionOutput)
        }finally{gate.complete(Unit);c.close()}
    }
    @Test fun runtimeOomDoesNotSelectLegacyOrAutoRetry(){
        val p=FakeProvider().apply{tileFailure=OutOfMemoryError("synthetic")}
        val c=EditorDetailController(p,viewport(),{},settleMillis=1,retryDelays=listOf(10L,20L))
        c.bind(photo(),EditorState(),true);await{c.status.phase==DetailPhase.Fallback}
        assertEquals(DetailFailure.AllocationFailed,c.status.failure)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3));assertEquals(1,p.openCalls);assertEquals(0,p.legacyCalls);c.close()
    }

    @Test fun forcedLegacyForegroundReopenWaitsForCancelledOpenAndItsLateCleanup(){
        val initialRetirement=CompletableDeferred<Unit>()
        val p=FakeProvider().apply{tileFailure=dev.filmroom.engine.NativeWorkUnavailable();retirementGate=initialRetirement}
        val c=EditorDetailController(p,viewport(),{},settleMillis=1,retryDelays=listOf(10L,20L))
        val gate=CompletableDeferred<Unit>()
        try {
            c.bind(photo(),EditorState(),true);await{c.status.phase==DetailPhase.Fallback}
            p.tileFailure=null;p.retirementGate=null;initialRetirement.complete(Unit)
            await{c.status.phase==DetailPhase.Ready && p.legacyCalls==1}
            p.openGate=gate;c.foreground(false);c.foreground(true);await{p.openCalls==3}
            c.foreground(false);c.foreground(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals("Prior non-cooperative open still owns its workers",3,p.openCalls)
            p.openGate=null;gate.complete(Unit)
            await{p.openCalls==4 && c.status.phase==DetailPhase.Ready}
            assertTrue(p.sessions[p.sessions.size-2].closed);assertFalse(p.sessions.last().regionOutput)
        }finally{initialRetirement.complete(Unit);gate.complete(Unit);c.close()}
    }

    @Test fun repeatedStableBindsDoNotRestartTheSameSettleTimer(){
        val p=FakeProvider();val c=EditorDetailController(p,viewport(),{});val source=photo();val params=EditorState()
        try {
            c.bind(source,params,true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            repeat(20){c.bind(source,params,true);c.demand(false)}
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90))
            assertEquals(1,p.openCalls)
        }finally{c.close()}
    }
    @Test fun changedVisibleRectStillRestartsSettleAndHintUsesLatestWorkingGrid(){
        val p=FakeProvider();val v=viewport();val c=EditorDetailController(p,v,{});val source=photo()
        try {
            c.bind(source,EditorState(),true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            v.drag(70.0,0.0);c.demand(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertEquals(0,p.openCalls)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90));assertEquals(1,p.openCalls)
            val size=source.outputSize(1536)
            val expected=v.visibleRect(RenderKey("x","x","x","x","x",size.width,size.height))
            assertEquals(expected,p.initialRegions.single());assertEquals(1536,size.width)
        }finally{c.close()}
    }
    @Test fun presentationOnlyChangesReuseReadyScienceAndOutput(){
        val p=FakeProvider();val c=EditorDetailController(p,viewport(),{});val source=photo();val params=EditorState()
        try {
            c.bind(source,params,true);await{c.cacheBytes>0};val session=p.sessions.single();val key=c.key
            val presentation=params.copy(imprint=params.imprint.copy(exposure=true),filmBorder=true,mediumPortrait=true,preferredOutputRoute="print")
            assertEquals(params.json(includePresentation=false),presentation.json(includePresentation=false))
            c.bind(source,presentation,true);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1,p.openCalls);assertSame(key,c.key);assertFalse(session.closed);assertTrue(c.cacheBytes>0)
            c.bind(source,presentation.copy(brightness=.25f),true);await{p.openCalls==2 && session.closed}
        }finally{c.close()}
    }
    @Test fun presentationChangesDuringOpenKeepSameBuildAndScienceChangesStillRetire(){
        val gate=CompletableDeferred<Unit>();val p=FakeProvider().apply{openGate=gate}
        val c=EditorDetailController(p,viewport(),{});val source=photo();val params=EditorState()
        try {
            c.bind(source,params,true);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,p.openCalls)
            c.bind(source,params.copy(imprint=params.imprint.copy(exposure=true)),true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,p.openCalls)
            gate.complete(Unit);await{c.cacheBytes>0};assertFalse(p.sessions.single().closed)
        }finally{gate.complete(Unit);c.close()}
    }

}
