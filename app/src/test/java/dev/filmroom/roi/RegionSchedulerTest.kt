package dev.filmroom.roi

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class RegionSchedulerTest {
    private fun key()=RenderKey("s","p","region-v2","d","map",1536,1024)
    @Test fun fourCellsAreOneTightRequestAndContainedViewportReusesResult(){
        val worker=ProgressiveTilesTest.Controlled();val provider=ProgressiveTilesTest.Fake();val s=RegionScheduler(provider,worker=worker)
        val k=key();val r=PixelRect(250,250,270,270)
        s.update(k,r);worker.drain();assertEquals(listOf(r),provider.calls);assertEquals(22*22*4L,s.cacheBytes)
        val bitmap=s.drawableTiles().single().bitmap
        s.update(k,PixelRect(252,252,266,266));worker.drain();assertEquals(1,provider.calls.size)
        s.close();assertEquals(0,s.cacheBytes);assertFalse(bitmap.isRecycled)
    }
    @Test fun hundredPansCancelOldAndKeepOnlyLatestWithoutQueuedBacklog(){
        val worker=ProgressiveTilesTest.Controlled();val provider=ProgressiveTilesTest.Fake();val s=RegionScheduler(provider,worker=worker)
        s.update(key(),PixelRect(0,0,20,20))
        repeat(100){s.update(key(),PixelRect(it+1,0,it+21,20))}
        assertEquals(1,worker.tasks.size);worker.next();assertTrue(provider.results.first().bitmap.isRecycled)
        worker.drain();assertEquals(2,provider.calls.size);assertEquals(PixelRect(100,0,120,20),s.drawableTiles().single().coreRect);s.close()
    }
    @Test fun keyChangeLateResultCloseAndInvalidQualityNeverPublish(){
        val worker=ProgressiveTilesTest.Controlled();val provider=ProgressiveTilesTest.Fake();val s=RegionScheduler(provider,worker=worker)
        val r=PixelRect(0,0,200,200);s.update(key(),r);s.update(key().copy(parametersRevision="new"),r);worker.next()
        assertTrue(provider.results.first().bitmap.isRecycled);worker.drain();assertEquals("new",s.drawableTiles().single().key.parametersRevision)
        s.update(key().copy(dataRevision="new"),r);s.close();worker.drain();assertTrue(provider.results.last().bitmap.isRecycled)
        val bad=RegionScheduler(provider.apply{quality=TileQuality.ApproximateTestOnly},worker=worker);bad.update(key(),r);worker.drain()
        assertTrue(bad.drawableTiles().isEmpty());assertNotNull(bad.lastError);bad.close()
    }
    @Test fun cacheIsByteBoundedAndEmptyViewportDoesNotRender(){
        val worker=ProgressiveTilesTest.Controlled();val provider=ProgressiveTilesTest.Fake();val s=RegionScheduler(provider,byteBudget=64L*64*4,worker=worker)
        val k=key();s.update(k,null);worker.drain();assertTrue(provider.calls.isEmpty());assertFalse(s.hasDemand)
        s.update(k,PixelRect(0,0,60,60));worker.drain();val published=s.drawableTiles().single().bitmap
        s.update(k,PixelRect(500,500,560,560));worker.drain();assertTrue(s.cacheBytes<=64L*64*4);assertFalse(published.isRecycled)
        s.update(k,PixelRect(0,0,100,100));worker.drain();assertNotNull(s.lastError);assertEquals(2,provider.calls.size);s.close()
    }
    @Test fun partialOverlapIsReusedWithoutClaimingWholeViewportReady(){
        val worker=ProgressiveTilesTest.Controlled();val provider=ProgressiveTilesTest.Fake();val s=RegionScheduler(provider,worker=worker)
        s.update(key(),PixelRect(100,100,200,200));worker.drain();val old=s.drawableTiles().single()
        s.update(key(),PixelRect(150,100,250,200));assertFalse(s.ready);assertSame(old,s.drawableTiles().single())
        worker.drain();assertTrue(s.ready);assertEquals(PixelRect(150,100,250,200),s.drawableTiles().single().coreRect);s.close()
    }
    @Test fun reusedContainingRegionTouchesLruBeforeEviction(){
        val worker=ProgressiveTilesTest.Controlled();val provider=ProgressiveTilesTest.Fake();val s=RegionScheduler(provider,byteBudget=4000,worker=worker)
        val a=PixelRect(0,0,20,20);val b=PixelRect(100,100,120,120);val c=PixelRect(200,200,220,220)
        s.update(key(),a);worker.drain();s.update(key(),b);worker.drain()
        s.update(key(),PixelRect(1,1,19,19));worker.drain();assertEquals(2,provider.calls.size)
        s.update(key(),c);worker.drain();s.update(key(),a);worker.drain();assertEquals(3,provider.calls.size)
        s.update(key(),b);worker.drain();assertEquals(4,provider.calls.size);s.close()
    }
    @Test fun visibleRectSurvivesLandscapeFoldResizeAndZoom(){
        val v=Viewport().apply{sourceAspect(1.5);size(400,800);pinch(4.0,200.0,400.0)};val k=key()
        for((w,h) in listOf(400 to 800,1000 to 400,600 to 600,0 to 0)){
            v.size(w,h);val r=v.visibleRect(k)
            if(w==0)assertNull(r) else {assertNotNull(r);assertTrue(r!!.left>=0 && r.top>=0 && r.right<=1536 && r.bottom<=1024);assertTrue(r.width>0 && r.height>0)}
        }
    }
}
