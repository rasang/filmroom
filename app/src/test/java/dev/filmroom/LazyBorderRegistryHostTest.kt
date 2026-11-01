package dev.filmroom

import dev.filmroom.border.*
import dev.filmroom.model.EditorState
import dev.filmroom.io.PhotoException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class LazyBorderRegistryHostTest {
    private val first=definition("fixture_a","v1")
    private val second=definition("fixture_b","b-v1")
    private fun definition(stock:String,version:String)=LazyBorderRegistry.Definition(
        StraightAlphaBorderLoader.Spec(stock,version,"0".repeat(64),1),"fixed135/$stock.png")
    private fun raster(d:LazyBorderRegistry.Definition)=object:BorderRaster {
        override val stock=d.spec.stock;override val version=d.spec.version
        override val railRatio=5.5/24;override val endRatio=1.0/24
        override val residentBytes=12L;override val featherTotalAt3072=d.spec.featherTotalAt3072
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(if(stock=="fixture_a") .25f else .75f)}
    }
    private fun profiles()=mapOf("fixture_a" to first.key,"fixture_push1" to first.key,"fixture_push2" to first.key,"fixture_b" to second.key)
    @Test fun snapshotsAreCheapAndThreeAliasesDecodeOnePhysicalAtlas(){
        val count=AtomicInteger();val r=LazyBorderRegistry(listOf(first,second),profiles()){d,_->count.incrementAndGet();raster(d)}
        val snapshots=profiles().keys.map{r.snapshot(it,96,64)}
        assertEquals(0,count.get());assertEquals(0,r.cachedAtlasCount);assertFalse(EditorState().filmBorder)
        val a=r.resolve(snapshots[0]);val p1=r.resolve(snapshots[1]);val p2=r.resolve(snapshots[2])
        assertEquals(1,count.get());assertEquals(1,r.cachedAtlasCount)
        assertEquals("fixture_push1",p1.stock);assertEquals("fixture_push2",p2.stock)
        p1.validate(snapshots[1]);p2.validate(snapshots[2])
        val rgb=FloatArray(3);for(v in listOf(a,p1,p2)){v.sample(snapshots[0],1.0,1.0,rgb);assertArrayEquals(floatArrayOf(.25f,.25f,.25f),rgb,0f)}
    }
    @Test fun evictionDoesNotInvalidateBorrowedRasterAndRetainsOnlyOneCacheEntry(){
        val count=AtomicInteger();val r=LazyBorderRegistry(listOf(first,second),profiles()){d,_->count.incrementAndGet();raster(d)}
        val a=r.snapshot("fixture_a",96,64);val held=r.resolve(a)
        r.resolve(r.snapshot("fixture_b",96,64));assertEquals(2,count.get());assertEquals(1,r.cachedAtlasCount)
        val rgb=FloatArray(3);held.sample(a,1.0,1.0,rgb);assertEquals(.25f,rgb[0],0f)
        assertNotSame(held,r.resolve(a));assertEquals(3,count.get());assertEquals(1,r.cachedAtlasCount)
    }
    @Test fun exactPinnedVersionRemainsResolvableButUnknownAndLegacyFailClosed(){
        val old=definition("fixture_a","v0")
        val r=LazyBorderRegistry(listOf(first,second,old),profiles()){d,_->raster(d)}
        val s=r.snapshot("fixture_a",96,64)
        assertEquals("v0",r.resolve(s.copy(assetVersion="v0")).version)
        assertThrows(PhotoException::class.java){r.resolve(s.copy(assetVersion="missing"))}
        assertThrows(PhotoException::class.java){r.snapshot("missing",96,64)}
        assertThrows(PhotoException::class.java){r.resolve(BorderSnapshot.create("fixture_a","v1",96,64,.2,.03,4.0))}
        assertThrows(IllegalArgumentException::class.java){r.resolve(s.copy(featherTotalAt3072=8.0))}
    }
    @Test fun concurrentAliasesShareOneDecode(){
        val count=AtomicInteger();val r=LazyBorderRegistry(listOf(first,second),profiles()){d,check->
            count.incrementAndGet();Thread.sleep(30);check();raster(d)
        }
        val executor=Executors.newFixedThreadPool(8)
        try {
            val tasks=(0 until 24).map{n->executor.submit<BorderRaster>{r.resolve(r.snapshot(if(n%2==0)"fixture_a" else "fixture_push1",96,64))}}
            tasks.forEach{assertEquals("v1",it.get(5,TimeUnit.SECONDS).version)}
            assertEquals(1,count.get());assertEquals(1,r.cachedAtlasCount)
        }finally{executor.shutdownNow()}
    }
    @Test fun cancelledDecodeNeverPopulatesCacheAndNextAttemptCanSucceed(){
        var cancelled=false;val count=AtomicInteger()
        val r=LazyBorderRegistry(listOf(first,second),profiles()){d,_->count.incrementAndGet();raster(d).also{cancelled=true}}
        val snapshot=r.snapshot("fixture_a",96,64)
        assertThrows(CancellationException::class.java){r.resolve(snapshot){if(cancelled)throw CancellationException("authored cancellation")}}
        assertEquals(0,r.cachedAtlasCount)
        r.resolve(snapshot);assertEquals(2,count.get());assertEquals(1,r.cachedAtlasCount)
    }
    @Test fun missingOrHashRejectedAssetDoesNotFallbackOrCache(){
        var missing=true;val calls=AtomicInteger()
        val registry=LazyBorderRegistry(listOf(first,second),profiles()){d,_->
            calls.incrementAndGet();if(missing)throw java.io.IOException("authored missing or invalid asset")
            raster(d)
        }
        val snapshot=registry.snapshot("fixture_a",96,64)
        assertThrows(java.io.IOException::class.java){registry.resolve(snapshot)}
        assertEquals(1,calls.get());assertEquals(0,registry.cachedAtlasCount)
        missing=false;assertEquals("v1",registry.resolve(snapshot).version);assertEquals(2,calls.get())
    }
    @Test fun profileRemapCannotReinterpretPinnedPhysicalVersion(){
        val original=LazyBorderRegistry(listOf(first,second),profiles()){d,_->raster(d)}
        val queued=original.snapshot("fixture_push1",96,64)
        val remapped=LazyBorderRegistry(listOf(first,second),profiles()+ ("fixture_push1" to second.key)){d,_->raster(d)}
        assertThrows(PhotoException::class.java){remapped.resolve(queued)}
        val duplicateHumanVersion=definition("fixture_b","v1")
        assertThrows(IllegalArgumentException::class.java){
            LazyBorderRegistry(listOf(first,duplicateHumanVersion),mapOf("fixture_a" to first.key)){d,_->raster(d)}
        }
    }
    @Test fun metadataVerificationNeverLoadsAndRejectsUnknownPinnedVersion(){
        val count=AtomicInteger();val r=LazyBorderRegistry(listOf(first,second),profiles()){d,_->count.incrementAndGet();raster(d)}
        val snapshot=r.snapshot("fixture_a",96,64)
        repeat(20){r.verifySnapshot(snapshot)}
        assertEquals(0,count.get());assertEquals(0,r.cachedAtlasCount)
        assertThrows(PhotoException::class.java){r.verifySnapshot(snapshot.copy(assetVersion="missing"))}
        assertThrows(IllegalArgumentException::class.java){r.verifySnapshot(snapshot.copy(featherTotalAt3072=9.0))}
        assertEquals(0,count.get())
    }
    @Test fun explicitClearDropsOnlyCacheReferenceNotBorrowedPixels(){
        val count=AtomicInteger();val r=LazyBorderRegistry(listOf(first,second),profiles()){d,_->count.incrementAndGet();raster(d)}
        val snapshot=r.snapshot("fixture_a",96,64);val held=r.resolve(snapshot)
        r.clearCache();assertEquals(0,r.cachedAtlasCount)
        val rgb=FloatArray(3);held.sample(snapshot,1.0,1.0,rgb);assertEquals(.25f,rgb[0],0f)
        r.resolve(snapshot);assertEquals(2,count.get())
    }
    @Test fun mainThreadTrimDoesNotWaitForDecodeAndOldEpochCannotRepopulateCache(){
        assertEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper())
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val cleared=CountDownLatch(1)
        val watchdogReleased=java.util.concurrent.atomic.AtomicBoolean(false);val calls=AtomicInteger()
        val registry=LazyBorderRegistry(listOf(first,second),profiles()){d,_->
            if(calls.incrementAndGet()==1){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            raster(d)
        }
        val executor=Executors.newFixedThreadPool(2)
        try {
            val snapshot=registry.snapshot("fixture_a",96,64)
            val pending=executor.submit<BorderRaster>{registry.resolve(snapshot)}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val watchdog=executor.submit{
                if(!cleared.await(1,TimeUnit.SECONDS)){watchdogReleased.set(true);release.countDown()}
            }
            // This is Robolectric's Main thread. The watchdog bounds regressions without hanging.
            registry.clearCache();cleared.countDown()
            assertFalse("Main trim waited for blocked decoder",watchdogReleased.get())
            assertEquals(1L,release.count);assertFalse(pending.isDone);assertEquals(0,registry.cachedAtlasCount)
            release.countDown();val borrowed=pending.get(2,TimeUnit.SECONDS);watchdog.get(2,TimeUnit.SECONDS)
            assertEquals(0,registry.cachedAtlasCount) // Old epoch must not undo trim.
            val rgb=FloatArray(3);borrowed.sample(snapshot,1.0,1.0,rgb);assertEquals(.25f,rgb[0],0f)
            registry.resolve(snapshot);assertEquals(2,calls.get());assertEquals(1,registry.cachedAtlasCount)
        }finally{cleared.countDown();release.countDown();executor.shutdownNow()}
    }
    @Test fun malformedDefinitionsCannotPointOutsideCompiledAssetFolder(){
        assertThrows(IllegalArgumentException::class.java){LazyBorderRegistry.Definition(first.spec,"../fixture.png")}
        assertThrows(IllegalArgumentException::class.java){LazyBorderRegistry(listOf(first,first),profiles()){d,_->raster(d)}}
    }
}
