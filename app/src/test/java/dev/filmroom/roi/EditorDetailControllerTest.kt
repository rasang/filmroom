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
    class FakeSession(override val key: RenderKey) : EditorDetailSession {
        @Volatile var closed=false
        var retire:(()->Unit)?=null
        val results=CopyOnWriteArrayList<TileResult>()
        val calls=CopyOnWriteArrayList<Pair<Int,Int>>()
        var failure:Throwable?=null
        var block:CountDownLatch?=null
        val entered=CountDownLatch(1)
        override fun onRetired(listener:()->Unit){retire=listener}
        override suspend fun tile(x:Int,y:Int):TileResult {
            failure?.let{throw it}
            check(Looper.myLooper()!=Looper.getMainLooper())
            calls+=x to y;entered.countDown();block?.await(10,TimeUnit.SECONDS)
            val core=TileId(x,y).rect(key);val sample=core.sampleRect(key)
            return TileResult(key,core,sample,Bitmap.createBitmap(sample.width,sample.height,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff88aa44.toInt())},TileQuality.Exact).also{results+=it}
        }
        override fun close(){check(Looper.myLooper()!=Looper.getMainLooper());closed=true}
    }
    class FakeProvider : EditorDetailProvider {
        val sessions=CopyOnWriteArrayList<FakeSession>()
        var openFailure:Throwable?=null
        var tileFailure:Throwable?=null
        var openCalls=0
        var tileBlock:CountDownLatch?=null
        var openGate:CompletableDeferred<Unit>?=null
        override suspend fun open(source:PhotoSource,parameters:EditorState):EditorDetailSession {
            openCalls++;openFailure?.let{throw it}
            // Deliberately non-cooperative provider proves stale opening ownership is still retired.
            openGate?.let{withContext(NonCancellable){it.await()}}
            return FakeSession(RenderKey(source.cacheKey,parameters.json(),"a","d","orientation-source",1024,768)).also{it.block=tileBlock;it.failure=tileFailure;sessions+=it}
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
        await{session.calls.size>=v.visibleTiles(session.key).size};val initial=c.key
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
}
