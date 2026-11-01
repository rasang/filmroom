package dev.filmroom.roi

import android.graphics.*
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class ProgressiveTilesTest {
    private fun key(w:Int=1024,h:Int=768)=RenderKey("source1","params1","algo1","data1","oriented90/crop-full",w,h)
    class Controlled : Executor {
        val tasks = ArrayDeque<Runnable>()
        var executed=0
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runWithoutPublication() {
            val task=tasks.removeFirst()
            val thread=Thread { executed++;task.run() }
            thread.start(); thread.join(5000); check(!thread.isAlive)
        }
        fun next() { runWithoutPublication(); shadowOf(Looper.getMainLooper()).idle() }
        fun drain() { var n=0; while(tasks.isNotEmpty()) { check(++n<1000);next() } }
    }
    class Fake : TileProvider {
        val calls=mutableListOf<PixelRect>()
        val results=mutableListOf<TileResult>()
        var quality=TileQuality.Exact // Exact synthetic coordinates ONLY, not film effects.
        override fun render(key:RenderKey,outputRect:PixelRect,cancelled:()->Boolean):TileResult {
            check(Looper.myLooper()!=Looper.getMainLooper())
            calls+=outputRect
            val sample=outputRect.sampleRect(key)
            val b=Bitmap.createBitmap(sample.width,sample.height,Bitmap.Config.ARGB_8888)
            b.eraseColor(CoordinateFixtureProvider.color(outputRect.left+1,outputRect.top+1))
            return TileResult(key,outputRect,sample,b,quality).also{results+=it}
        }
    }
    @Test fun hundredRapidViewportsKeepOnlyLatestDemandAndReuseOffscreenCompletion() {
        val worker=Controlled();val p=Fake();val s=TileScheduler(p,worker=worker)
        val k=key(51200,256)
        s.update(k,listOf(TileId(0,0)))
        repeat(100){s.update(k,listOf(TileId(it+1,0)))}
        assertEquals(1,worker.tasks.size);assertEquals(1,s.runningCount)
        worker.next()
        assertNotNull(s.cached(TileId(0,0))) // Completed old viewport is reusable, not stale generation.
        worker.drain()
        assertEquals(listOf(0,25600),p.calls.map{it.left})
        assertEquals(1L,s.generation);assertEquals(101L,s.viewportRevision)
        s.update(k,listOf(TileId(0,0)));assertTrue(worker.tasks.isEmpty())
        assertEquals(0,s.drawableTiles().single().outputRect.left);s.close()
    }
    @Test fun sourceParameterAlgorithmDataMappingAndGridSwitchDiscardOldResults() {
        val k=key()
        val variants=listOf(k.copy(sourceRevision="new"),k.copy(parametersRevision="new"),
            k.copy(algorithmRevision="new"),k.copy(dataRevision="new"),k.copy(displaySamplingRevision="new"),
            k.copy(orientedSourceMapping="oriented0/crop-full"),k.copy(virtualFullWidth=2048))
        variants.forEach { latest ->
            val w=Controlled();val p=Fake();val s=TileScheduler(p,worker=w)
            s.update(k,listOf(TileId(0,0)));s.update(latest,listOf(TileId(1,0)))
            w.next();assertTrue(p.results[0].bitmap.isRecycled);assertTrue(s.drawableTiles().isEmpty())
            w.drain();assertEquals(latest,s.drawableTiles().single().key)
            assertFalse(s.drawableTiles().single().bitmap.isRecycled);s.close()
        }
    }
    @Test fun byteLruEvictionDoesNotRecyclePreviouslyPublishedBitmap() {
        val w=Controlled();val p=Fake();val s=TileScheduler(p,byteBudget=258L*258*4,worker=w)
        val k=key()
        s.update(k,listOf(TileId(0,0)));w.drain()
        val retainedForCanvas=s.drawableTiles().single().bitmap
        s.update(k,listOf(TileId(1,0)));w.drain()
        assertNull(s.cached(TileId(0,0)));assertEquals(p.results.last().bitmap.allocationByteCount.toLong(),s.cacheBytes)
        assertFalse(retainedForCanvas.isRecycled)
        s.close();assertFalse(p.results.last().bitmap.isRecycled);assertEquals(0L,s.cacheBytes)
    }
    @Test fun demandLargerThanBudgetTerminatesWithoutRerenderLoop() {
        val w=Controlled();val p=Fake();val s=TileScheduler(p,byteBudget=266256,worker=w)
        s.update(key(),listOf(TileId(0,0),TileId(1,0),TileId(2,0)));w.drain()
        assertEquals(1,p.calls.size);assertEquals(TileId(0,0),s.admittedTiles().single())
        repeat(100){s.update(key(),listOf(TileId(0,0),TileId(1,0),TileId(2,0)))}
        assertTrue(w.tasks.isEmpty());assertEquals(1,p.calls.size);s.close()
    }
    @Test fun approximateRejectedUnlessExplicitlyTestOnly() {
        val w=Controlled();val p=Fake().apply{quality=TileQuality.ApproximateTestOnly}
        val s=TileScheduler(p,worker=w);s.update(key(),listOf(TileId(0,0)));w.drain()
        assertTrue(s.drawableTiles().isEmpty());assertTrue(p.results.single().bitmap.isRecycled)
        assertNotNull(s.lastError);s.close()
        val t=TileScheduler(p,worker=w,allowApproximateTestOnly=true);t.update(key(),listOf(TileId(0,0)));w.drain()
        assertEquals(TileQuality.ApproximateTestOnly,t.drawableTiles().single().quality);t.close()
    }
    @Test fun failedProviderStopsUntilNewDemandAndKeepsUiResponsive() {
        val w=Controlled()
        val s=TileScheduler(TileProvider{_,_,_->throw IllegalStateException("synthetic failure")},worker=w)
        s.update(key(),listOf(TileId(0,0)));w.drain()
        assertEquals("synthetic failure",s.lastError);assertEquals(0,s.runningCount)
        s.update(key(),emptyList());assertTrue(w.tasks.isEmpty());s.close()
    }
    @Test fun closeDuringUncooperativeWorkRecyclesOnlyUnpublishedResult() {
        val w=Controlled();val p=Fake();val s=TileScheduler(p,worker=w)
        s.update(key(),listOf(TileId(0,0)));s.close();w.drain()
        assertTrue(p.results.single().bitmap.isRecycled);assertTrue(s.drawableTiles().isEmpty())
    }
    @Test fun cancellationIsPerGenerationNotViewportAndDoesNotStartConcurrentWorkers() {
        val entered=CountDownLatch(1);val released=CountDownLatch(1)
        var token:(()->Boolean)?=null
        val p=Fake()
        val s=TileScheduler(TileProvider{k,r,c->token=c;entered.countDown();check(released.await(5,TimeUnit.SECONDS));p.render(k,r,c)})
        s.update(key(),listOf(TileId(0,0)));assertTrue(entered.await(5,TimeUnit.SECONDS))
        s.update(key(),listOf(TileId(1,0)));assertFalse(token!!())
        s.update(key().copy(parametersRevision="new"),emptyList());assertTrue(token!!())
        assertEquals(1,s.runningCount)
        released.countDown()
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(s.runningCount!=0 && System.nanoTime()<until) { Thread.sleep(5);shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(0,s.runningCount);assertTrue(p.results.single().bitmap.isRecycled);s.close()
    }
    @Test fun fixedOriginOddTinyEdgeClippingAndCenterPriority() {
        val k=key(513,257);val v=Viewport();v.size(513,257);v.sourceAspect(513.0/257)
        val tiles=v.visibleTiles(k)
        assertEquals(6,tiles.size)
        assertEquals(PixelRect(512,256,513,257),TileId(2,1).rect(k))
        assertEquals(TileId(1,0),tiles.first())
        assertEquals(listOf(TileId(0,0)),v.visibleTiles(key(1,1)))
        v.size(0,0);assertTrue(v.visibleTiles(k).isEmpty())
    }
    @Test fun pinchKeepsFocusAndPanResizeAreResolutionIndependent() {
        val v=Viewport();v.size(900,600);v.sourceAspect(1.5);v.pinch(3.0,450.0,300.0)
        val before=(200-v.left)/v.imageWidth
        v.pinch(1.3,200.0,200.0)
        assertEquals(before,(200-v.left)/v.imageWidth,1e-12)
        v.drag(-123.0,76.0)
        val cx=v.centerX;val cy=v.centerY
        v.size(600,900) // Orientation of screen changes, not source orientation.
        assertEquals(cx,v.centerX,1e-12);assertEquals(cy,v.centerY,1e-12)
        val tilesA=v.visibleTiles(key(1001,667));val tilesB=v.visibleTiles(key(2002,1334))
        assertTrue(tilesA.isNotEmpty());assertTrue(tilesB.isNotEmpty())
        assertEquals(cx,v.centerX,1e-12)
    }
    @Test fun extremeAspectNeverMovesOutsideImageAndNoNaN() {
        listOf(1.0/9999,9999.0,1.0,513.0/257).forEach { a->
            val v=Viewport();v.sourceAspect(a);v.size(700,900)
            repeat(20){v.pinch(1.2,300.0,400.0);v.drag(1e8,-1e8)}
            assertTrue(v.left.isFinite());assertTrue(v.top.isFinite())
            if(v.imageWidth>v.width){assertTrue(v.left<=1e-7);assertTrue(v.left+v.imageWidth>=v.width-1e-7)}
            if(v.imageHeight>v.height){assertTrue(v.top<=1e-7);assertTrue(v.top+v.imageHeight>=v.height-1e-7)}
        }
    }
    @Test fun underlayReplacementAndGridChangeDoNotJumpTransformOrTilePlacement() {
        val w=Controlled();val view=ProgressivePhotoView(RuntimeEnvironment.getApplication(),Fake(),w)
        view.layout(0,0,600,400)
        val k=key(1201,801)
        view.bind(k,Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888),1.5)
        view.pinch(3.0,300.0,200.0);view.pan(-100.0,20.0)
        val before=view.placement(PixelRect(0,0,1201,801),k)
        val newer=k.copy(virtualFullWidth=2402,virtualFullHeight=1602)
        view.bind(newer,Bitmap.createBitmap(301,201,Bitmap.Config.ARGB_8888),1.5)
        assertEquals(before,view.placement(PixelRect(0,0,2402,1602),newer))
        val a=view.placement(PixelRect(0,0,256,256),newer)
        val b=view.placement(PixelRect(256,0,512,256),newer)
        assertEquals(a.right,b.left,0f);view.close();w.drain()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun syntheticTileOverlaysUnderlayAtGlobalRectAndPendingRegionsStayLowRes() {
        val w=Controlled();val p=Fake();val view=ProgressivePhotoView(RuntimeEnvironment.getApplication(),p,w)
        view.layout(0,0,512,256)
        val low=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.MAGENTA)}
        view.bind(key(512,256),low,2.0)
        val screen=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(screen));assertEquals(Color.MAGENTA,screen.getPixel(300,100))
        w.next() // Only one tile delivered; the other stays low-resolution, not blank.
        screen.eraseColor(Color.BLACK);view.draw(Canvas(screen))
        val r=p.calls.single()
        assertEquals(p.results.single().bitmap.getPixel(10,10),screen.getPixel(r.left+10,10))
        val absent=if(r.left==0)300 else 100
        assertEquals(Color.MAGENTA,screen.getPixel(absent,100))
        w.drain();view.draw(Canvas(screen))
        assertNotEquals(Color.MAGENTA,screen.getPixel(absent,100))
        val out=java.io.File("build/roi-coordinate-fixture.png")
        out.outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)}
        view.close()
    }
    @Test fun fullFrameHighResolutionSubstitutionRejected() {
        val v=ProgressivePhotoView(RuntimeEnvironment.getApplication(),Fake(),Controlled())
        try { v.bind(key(),Bitmap.createBitmap(1024,768,Bitmap.Config.ARGB_8888),4.0/3);fail("highres underlay") }
        catch(expected:IllegalArgumentException){} finally{v.close()}
    }
    @Test fun malformedRectDimensionsAndRecycledResultsNeverPublish() {
        repeat(4) { variant ->
            val w=Controlled()
            var returned:Bitmap?=null
            val s=TileScheduler(TileProvider{k,r,_->
                val sample=r.sampleRect(k)
                val b=Bitmap.createBitmap(if(variant==1)1 else sample.width,sample.height,Bitmap.Config.ARGB_8888)
                returned=b
                if(variant==2)b.recycle()
                TileResult(k,if(variant==0)r.copy(left=r.left+1)else r,if(variant==3)sample.copy(left=sample.left+1)else sample,b,TileQuality.Exact)
            },worker=w)
            s.update(key(),listOf(TileId(0,0)));w.drain()
            assertTrue(s.drawableTiles().isEmpty());assertTrue(returned!!.isRecycled);assertNotNull(s.lastError);s.close()
        }
    }
    @Test fun returningToSameKeyStillRejectsPriorGeneration() {
        val w=Controlled();val p=Fake();val s=TileScheduler(p,worker=w)
        val a=key();val b=a.copy(parametersRevision="B")
        s.update(a,listOf(TileId(0,0)));s.update(b,listOf(TileId(1,0)));s.update(a,listOf(TileId(2,0)))
        w.next();assertTrue(p.results.first().bitmap.isRecycled)
        w.drain();assertEquals(512,s.drawableTiles().single().outputRect.left);assertEquals(3L,s.generation);s.close()
    }
    @Test fun sourceParameterAndGridChangesWhileWorkerIsActuallyRunning() {
        val initial=key()
        listOf(initial.copy(sourceRevision="other"),initial.copy(parametersRevision="other"),
            initial.copy(virtualFullWidth=2048,virtualFullHeight=1536)).forEach { latest ->
            val entered=CountDownLatch(1);val released=CountDownLatch(1);val p=Fake()
            var token:(()->Boolean)?=null
            val s=TileScheduler(TileProvider{k,r,c->token=c;entered.countDown();check(released.await(5,TimeUnit.SECONDS));p.render(k,r,c)})
            s.update(initial,listOf(TileId(0,0)));assertTrue(entered.await(5,TimeUnit.SECONDS))
            s.update(latest,emptyList());assertTrue(token!!());assertEquals(1,s.runningCount)
            released.countDown()
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(s.runningCount!=0 && System.nanoTime()<until){Thread.sleep(5);shadowOf(Looper.getMainLooper()).idle()}
            assertEquals(0,s.runningCount);assertTrue(p.results.single().bitmap.isRecycled)
            assertTrue(s.drawableTiles().isEmpty());s.close()
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun coordinateFixtureHasNoTileSeamOffsetAndIsExplicitlyTestOnly() {
        val w=Controlled()
        val view=ProgressivePhotoView(RuntimeEnvironment.getApplication(),CoordinateFixtureProvider(),w,true)
        view.layout(0,0,513,257)
        view.bind(key(513,257),Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888),513.0/257)
        w.drain()
        val screen=Bitmap.createBitmap(513,257,Bitmap.Config.ARGB_8888);view.draw(Canvas(screen))
        listOf(1 to 1,255 to 255,256 to 50,257 to 50,511 to 255,512 to 256).forEach{(x,y)->
            assertEquals("coordinate $x,$y",CoordinateFixtureProvider.color(x,y),screen.getPixel(x,y))
        }
        java.io.File("build/roi-coordinate-grid.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)}
        view.close()
    }

    @Test fun priorityResidentSetRetainsCenterAndIdenticalDemandDoesNoWork() {
        val w=Controlled();val p=Fake();val s=TileScheduler(p,byteBudget=266256,worker=w)
        val priority=listOf(TileId(1,0),TileId(0,0),TileId(2,0))
        s.update(key(),priority);w.drain()
        assertEquals(listOf(TileId(1,0)),s.admittedTiles())
        assertEquals(listOf(256),p.calls.map{it.left})
        assertNotNull(s.cached(TileId(1,0)))
        repeat(100){s.update(key(),priority)}
        assertTrue(w.tasks.isEmpty());assertEquals(1,p.calls.size)
        s.update(key(),listOf(TileId(2,0),TileId(1,0),TileId(3,0)));w.drain()
        assertEquals(listOf(256,512),p.calls.map{it.left})
        assertEquals(512,s.drawableTiles().single().coreRect.left)
        assertTrue(s.cacheBytes<=s.byteBudget);s.close()
    }
    @Test fun closeAfterWorkerReturnsBeforeMainPublicationDisposesUnpublishedResult() {
        val w=Controlled();val p=Fake();val s=TileScheduler(p,worker=w)
        s.update(key(),listOf(TileId(0,0)));w.runWithoutPublication()
        assertFalse(p.results.single().bitmap.isRecycled)
        s.close();shadowOf(Looper.getMainLooper()).idle()
        assertTrue(p.results.single().bitmap.isRecycled);assertTrue(s.drawableTiles().isEmpty())
        assertTrue(w.tasks.isEmpty())
    }
    @Test fun runningAToNonemptyBToNonemptyCOnlyPublishesC() {
        val entered=CountDownLatch(1);val released=CountDownLatch(1);val p=Fake()
        val active=java.util.concurrent.atomic.AtomicInteger()
        val maximum=java.util.concurrent.atomic.AtomicInteger()
        val a=key();val b=a.copy(parametersRevision="B");val c=a.copy(parametersRevision="C")
        val s=TileScheduler(TileProvider{k,r,cancel->
            val now=active.incrementAndGet();maximum.accumulateAndGet(now,Math::max)
            try {
                if(k==a){entered.countDown();check(released.await(5,TimeUnit.SECONDS))}
                p.render(k,r,cancel)
            }finally{active.decrementAndGet()}
        })
        s.update(a,listOf(TileId(0,0)));assertTrue(entered.await(5,TimeUnit.SECONDS))
        s.update(b,listOf(TileId(1,0)));s.update(c,listOf(TileId(2,0)))
        released.countDown()
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while((s.runningCount!=0 || s.drawableTiles().isEmpty()) && System.nanoTime()<until){
            Thread.sleep(5);shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(1,maximum.get());assertEquals(listOf(a,c),p.results.map{it.key})
        assertTrue(p.results.first().bitmap.isRecycled)
        assertEquals(c,s.drawableTiles().single().key);assertEquals(512,s.drawableTiles().single().coreRect.left)
        s.close()
    }
    @Test fun temporaryDetachPausesQueuedWorkReattachResumesAndHostCloseIsFinal() {
        val host=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val frame=android.widget.FrameLayout(host.get());host.get().setContentView(frame)
            val w=Controlled();val p=Fake();val view=ProgressivePhotoView(host.get(),p,w)
            frame.addView(view);view.layout(0,0,512,256)
            val low=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888)
            view.bind(key(512,256),low,2.0);assertEquals(1,w.tasks.size)
            frame.removeView(view);w.next()
            assertEquals(1,p.calls.size);assertTrue(w.tasks.isEmpty());assertFalse(low.isRecycled)
            frame.addView(view);view.layout(0,0,512,256);w.drain()
            assertEquals(2,p.calls.size);assertFalse(p.results.first().bitmap.isRecycled)
            view.close();frame.removeView(view);frame.addView(view)
            assertTrue(w.tasks.isEmpty())
            try{view.bind(key(),low,2.0);fail("disposed rebind")}catch(expected:IllegalStateException){}
            assertFalse(low.isRecycled)
        }finally{host.pause().stop().destroy()}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fractionalTilesMatchIndependentCropControlWithoutExcessSeamError() {
        val k=key(769,513)
        fun pixel(x:Int,y:Int):Int {
            val checker=if((x+y)%2==0)255 else 0
            return Color.rgb(checker,if(x%256<2 || y%256<2)255 else 0,(x*17+y*31)%256)
        }
        fun make(r:PixelRect):Bitmap {
            val pixels=IntArray(r.width*r.height){i->pixel(r.left+i%r.width,r.top+i/r.width)}
            return Bitmap.createBitmap(pixels,r.width,r.height,Bitmap.Config.ARGB_8888)
        }
        val full=make(PixelRect(0,0,k.virtualFullWidth,k.virtualFullHeight))
        val metrics=StringBuilder()

        listOf(1.3 to (.37 to -.63),1.9 to (-17.21 to 11.79),2.7 to (31.11 to -27.43),1.3 to (-10000.0 to -10000.0)).forEachIndexed { index,(zoom,pan) ->
            val w=Controlled()
            val provider=TileProvider{key,core,_->val sample=core.sampleRect(key);TileResult(key,core,sample,make(sample),TileQuality.ApproximateTestOnly)}
            val view=ProgressivePhotoView(RuntimeEnvironment.getApplication(),provider,w,true)
            view.layout(0,0,602,407)
            view.bind(k,Bitmap.createBitmap(32,21,Bitmap.Config.ARGB_8888),769.0/513)
            view.pinch(zoom,301.0,203.5);view.pan(pan.first,pan.second);w.drain()
            val tiled=Bitmap.createBitmap(602,407,Bitmap.Config.ARGB_8888)
            val mono=Bitmap.createBitmap(602,407,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(tiled))
            val canvas=Canvas(mono);val v=view.viewport
            canvas.clipRect(0,0,602,407)
            canvas.clipRect(v.left.toFloat(),v.top.toFloat(),(v.left+v.imageWidth).toFloat(),(v.top+v.imageHeight).toFloat())
            canvas.translate(v.left.toFloat(),v.top.toFloat())
            canvas.scale((v.imageWidth/k.virtualFullWidth).toFloat(),(v.imageHeight/k.virtualFullHeight).toFloat())
            canvas.drawBitmap(full,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            var maxDiff=0;var differing=0
            for(y in 0 until 407)for(x in 0 until 602){
                val a=tiled.getPixel(x,y);val b=mono.getPixel(x,y)
                if(a!=b)differing++
                for(shift in listOf(0,8,16,24))maxDiff=maxOf(maxDiff,kotlin.math.abs((a ushr shift and 255)-(b ushr shift and 255)))
            }
            // SINGLE full-height cropped monolith: no internal horizontal/vertical tiles.
            // Choose the same global x origin as the rightmost resident tile.
            val cropLeft=511
            val cropped=Bitmap.createBitmap(full,cropLeft,0,k.virtualFullWidth-cropLeft,k.virtualFullHeight)
            val control=Bitmap.createBitmap(602,407,Bitmap.Config.ARGB_8888)
            val cc=Canvas(control)
            cc.clipRect(0,0,602,407)
            cc.translate(v.left.toFloat(),v.top.toFloat())
            cc.scale((v.imageWidth/k.virtualFullWidth).toFloat(),(v.imageHeight/k.virtualFullHeight).toFloat())
            cc.drawBitmap(cropped,cropLeft.toFloat(),0f,Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            var controlMax=0;var controlCount=0;var tileControlMax=0;var seamMax=0
            for(y in 0 until 407)for(x in 0 until 602){
                val globalX=(x+.5-v.left)/v.imageWidth*k.virtualFullWidth
                if(globalX>=512 && globalX<769){
                    val a=control.getPixel(x,y);val b=mono.getPixel(x,y);val t=tiled.getPixel(x,y)
                    if(a!=b)controlCount++
                    for(shift in listOf(0,8,16,24)){
                        controlMax=maxOf(controlMax,kotlin.math.abs((a ushr shift and 255)-(b ushr shift and 255)))
                        tileControlMax=maxOf(tileControlMax,kotlin.math.abs((a ushr shift and 255)-(t ushr shift and 255)))
                    }
                }
                val globalY=(y+.5-v.top)/v.imageHeight*k.virtualFullHeight
                if((globalX>1 && globalX<k.virtualFullWidth-1 && kotlin.math.abs(globalX/256-kotlin.math.round(globalX/256))<2.0/256) ||
                   (globalY>1 && globalY<k.virtualFullHeight-1 && kotlin.math.abs(globalY/256-kotlin.math.round(globalY/256))<2.0/256)){
                    val a=tiled.getPixel(x,y);val b=mono.getPixel(x,y)
                    for(shift in listOf(0,8,16,24))seamMax=maxOf(seamMax,kotlin.math.abs((a ushr shift and 255)-(b ushr shift and 255)))
                }
            }
            metrics.append("controlCase=$index density(full/crop/tiled)=${full.density}/${cropped.density}/${tiled.density} singleCropDifference=$controlCount controlMax=$controlMax tileVsSingleCropMax=$tileControlMax seamBandMax=$seamMax\n")
            java.io.File("build/fractional-single-crop-$index.png").outputStream().use{control.compress(Bitmap.CompressFormat.PNG,100,it)}
            metrics.append("case=$index zoom=$zoom pan=$pan differingPixels=$differing maxChannelDelta=$maxDiff\n")
            java.io.File("build/fractional-tiled-$index.png").outputStream().use{tiled.compress(Bitmap.CompressFormat.PNG,100,it)}
            java.io.File("build/fractional-mono-$index.png").outputStream().use{mono.compress(Bitmap.CompressFormat.PNG,100,it)}
            java.io.File("build/fractional-comparison.txt").writeText(metrics.toString())
            // Do not assert blanket max16 tolerance. Every deviation from full-image drawing
            // must be reproduced exactly by ONE independently cropped monolith with no seams.
            assertEquals("extra differing pixels: $metrics",controlCount,differing)
            assertEquals("extra full-image error: $metrics",controlMax,maxDiff)
            assertEquals("tile vs single-crop excess: $metrics",0,tileControlMax)
            view.close()
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun bitmapAndCanvasDensityMetadataNeverChangesTileGeometry() {
        fun render(bitmapDensity:Int,canvasDensity:Int):Bitmap {
            val w=Controlled()
            val provider=TileProvider{k,r,c->
                CoordinateFixtureProvider().render(k,r,c).also{it.bitmap.density=bitmapDensity}
            }
            val view=ProgressivePhotoView(RuntimeEnvironment.getApplication(),provider,w,true)
            view.layout(0,0,513,257)
            val underlay=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888).apply{density=bitmapDensity}
            view.bind(key(513,257),underlay,513.0/257)
            view.pinch(1.3,256.5,128.5);view.pan(.37,-.63);w.drain()
            val output=Bitmap.createBitmap(513,257,Bitmap.Config.ARGB_8888).apply{density=canvasDensity}
            view.draw(Canvas(output));view.close();return output
        }
        val baseline=render(160,160)
        listOf(0 to 160,320 to 160,160 to 320,480 to 240).forEach{(bitmapDensity,canvasDensity)->
            val actual=render(bitmapDensity,canvasDensity)
            assertTrue("bitmap density $bitmapDensity, Canvas density $canvasDensity",baseline.sameAs(actual))
        }
    }

}
