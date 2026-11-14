package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.MotionEvent
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.roi.EditorDetailControllerTest.FakeProvider
import dev.filmroom.ui.PhotoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Real MainActivity + PhotoView host lifecycle/gesture coverage; not a device/GPU test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class LocalDetailActivityHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    private fun photo(a:MainActivity)=MainActivity::class.java.getDeclaredField("photo").apply{isAccessible=true}.get(a) as PhotoView
    @Suppress("UNCHECKED_CAST") private fun state(m:EditorViewModel)=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(m) as MutableStateFlow<EditorUiState>
    private fun await(test:()->Boolean){repeat(1000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(test())return;Thread.sleep(2)};error("Timed out")}
    private fun source(name:String)=PhotoSource(File(RuntimeEnvironment.getApplication().cacheDir,name).apply{writeText("source preserved")},name,"image/heic",4001,3001,1)
    @Test fun pauseResumeWithoutStopRestoresDetailBeforeAnyExport() {
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi")
        RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a=controller.get();val vm=model(a);val view=photo(a)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            val fake=FakeProvider();view.configureDetail(fake);view.layout(0,0,400,300)
            val image=Bitmap.createBitmap(512,384,Bitmap.Config.ARGB_8888)
            val params=EditorState(useGpu=false)
            state(vm).value=EditorUiState(photo=source("resume.heic"),original=image,rendered=image,parameters=params,renderedParameters=params)
            await{view.bitmap===image}
            // Permissions/transparent activities can pause and resume without onStop/onStart.
            // First zoom happens only after that round trip, before any export.
            controller.pause().resume()
            assertNotEquals(dev.filmroom.roi.DetailPhase.Paused,view.detailStatus.phase)
            view.layout(0,0,400,300)
            view.pinch(3.0,200.0,150.0)
            await{view.detailCacheBytes>0}
            assertFalse(vm.state.value.exporting)
            assertEquals(1,fake.openCalls)
            val zoom=view.transform()[0]
            repeat(2) {
                controller.pause();await{view.detailKey==null}
                controller.resume();view.layout(0,0,400,300);await{view.detailCacheBytes>0}
                assertEquals(zoom,view.transform()[0],.001f)
            }
            assertEquals(3,fake.openCalls)
        } finally {controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")}
    }
    @Test fun actualActivityGesturesCompareParametersSourceRotationBackgroundAndExportCoexist(){
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi")
        RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0).edit().clear().commit()
        org.robolectric.shadows.ShadowStatFs.registerStats(RuntimeEnvironment.getApplication().filesDir.path,4096,1_000_000,1_000_000)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get();val vm=model(activity);val state=state(vm)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            assertTrue(vm.localDetailEnabled)
            val fake=FakeProvider();vm.localDetailEnabled=true;vm.detailProviderFactory={fake}
            var view=photo(activity);view.configureDetail(fake);view.layout(0,0,400,300)
            val original=Bitmap.createBitmap(768,576,Bitmap.Config.ARGB_8888)
            val underlay=Bitmap.createBitmap(512,384,Bitmap.Config.ARGB_8888)
            val src=source("one.heic");val params=EditorState(useGpu=false) // CPU retains the ROI route; GPU uses full-frame refinement.
            state.value=EditorUiState(photo=src,original=original,rendered=underlay,parameters=params,renderedParameters=params)
            await{view.bitmap===underlay};shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertTrue(fake.sessions.isEmpty())
            // Actual pinch events exercise ScaleGestureDetector, followed by one-pointer drag.
            val t=1000L
            fun event(action:Int,time:Long,x0:Float,x1:Float):MotionEvent {
                val properties=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                val coords=arrayOf(MotionEvent.PointerCoords().apply{x=x0;y=150f;pressure=1f;size=1f},MotionEvent.PointerCoords().apply{x=x1;y=150f;pressure=1f;size=1f})
                return MotionEvent.obtain(t,time,action,2,properties,coords,0,0,1f,1f,0,0,0,0)
            }
            fun single(action:Int,time:Long,x:Float){MotionEvent.obtain(t,time,action,x,150f,0).let{view.onTouchEvent(it);it.recycle()}}
            single(MotionEvent.ACTION_DOWN,t,170f)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),t+10,170f,230f).let{view.onTouchEvent(it);it.recycle()}
            for(i in 1..8) event(MotionEvent.ACTION_MOVE,t+10+i*20,170f-i*14,230f+i*14).let{view.onTouchEvent(it);it.recycle()}
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),t+200,58f,342f).let{view.onTouchEvent(it);it.recycle()}
            single(MotionEvent.ACTION_MOVE,t+220,78f);single(MotionEvent.ACTION_UP,t+240,78f)
            assertTrue("pinch zoom=${view.transform()[0]}, density=${view.resources.displayMetrics.density}",view.transform()[0]>1.15f);await{view.detailCacheBytes>0}
            val first=fake.sessions.single();val calls=first.calls.size
            view.pan(35.0,0.0);await{view.detailCacheBytes>0};assertEquals(1,fake.sessions.size);assertTrue(first.calls.size>=calls)
            val cachedBeforeCompare=view.detailCacheBytes
            vm.compare();await{view.bitmap===original && view.detailKey==null}
            assertFalse(first.closed);assertTrue(view.detailCacheBytes>=cachedBeforeCompare)
            vm.compare();await{view.bitmap===underlay && view.detailKey!=null}
            assertSame(first,fake.sessions.single());assertTrue(view.detailCacheBytes>=cachedBeforeCompare)
            // Worker calls may grow while compare resumes. Both sides must inspect one
            // immutable snapshot, not distinct(snapshot N) versus live size N+1.
            val compareCalls=first.calls.toList()
            assertEquals("Completed cells must not rerender for compare: $compareCalls",compareCalls.distinct().size,compareCalls.size)
            vm.detailPreviewEdge={768}
            val ordinaryCalls=mutableListOf<Boolean>()
            vm.renderPreview={_,_,_,detail->ordinaryCalls+=detail;Bitmap.createBitmap(if(detail)768 else 512,if(detail)576 else 384,Bitmap.Config.ARGB_8888)}
            val next=params.copy(brightness=.25f);vm.parameters(next)
            await{vm.state.value.renderedParameters==next && view.detailKey?.parametersRevision==next.json()}
            assertEquals(listOf(false,true),ordinaryCalls);assertEquals(768,vm.state.value.rendered!!.width)
            assertSame(src,vm.state.value.photo);assertSame(original,vm.state.value.original)
            // Source change follows the same real bind path as import state adoption and resets zoom.
            val second=source("two.heic");state.value=state.value.copy(photo=second)
            await{view.transform()[0]==1f && view.detailKey==null};assertTrue(second.localFile.exists())
            view.pinch(3.0,200.0,150.0);await{view.detailCacheBytes>0}
            val transform=view.transform()
            RuntimeEnvironment.setQualifiers("w780dp-h360dp-land-mdpi")
            controller.recreate();view=photo(controller.get());view.layout(0,0,600,300)
            await{view.detailKey!=null};assertSame(vm,model(controller.get()));assertEquals(transform[0],view.transform()[0],.001f)
            controller.pause().stop();await{view.detailKey==null && fake.sessions.last().closed}
            controller.start().resume().visible();await{view.detailKey!=null}
            // Actual ViewModel export admission receives untouched original source, not local tiles.
            var exported:PhotoSource?=null;var thumbnail:Bitmap?=null
            vm.beginExportIntake={java.io.Closeable{}}
            vm.submitExport={s,_,_,_,_,thumb,_,durable->exported=s;thumbnail=thumb;durable()}
            vm.export(dev.filmroom.io.ExportFormat.PNG)
            await{exported!=null && !vm.state.value.exporting}
            assertSame(second,exported);assertSame(original,thumbnail);assertTrue(second.localFile.exists());assertTrue(vm.state.value.canExport)
            // Exercise actual repository import while a detail owner may still be retiring.
            val incoming=File(RuntimeEnvironment.getApplication().cacheDir,"incoming.png")
            incoming.outputStream().use{out->Bitmap.createBitmap(901,601,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,out)}
            val uri=android.net.Uri.parse("content://local-detail-test/incoming.png")
            shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri,incoming.inputStream())
            vm.importPhoto(uri)
            await{vm.state.value.photo?.cacheKey!=second.cacheKey && vm.state.value.busy==null}
            assertEquals(901,vm.state.value.photo!!.originalWidth);assertEquals(601,vm.state.value.photo!!.originalHeight)
            await{view.transform()[0]==1f && view.detailKey==null}
            assertTrue(incoming.exists());assertNotSame(underlay,vm.state.value.original)
        } finally {controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")}
    }
    @Test fun enabledDefaultMissingAbiRetainsOrdinary768AndWaitsForPreviewIdle(){
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi")
        RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val vm=model(controller.get());val state=state(vm);val view=photo(controller.get())
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true};assertTrue(vm.localDetailEnabled)
            var attempted=0
            view.configureDetail(dev.filmroom.roi.EditorDetailProvider { _,_->
                assertFalse("Detail must wait for ordinary preview refinement",vm.state.value.previewRefining)
                attempted++;throw UnsatisfiedLinkError("missing ABI for /data/user/0/private-user/private-photo.heic")
            })
            view.layout(0,0,400,300)
            val original=Bitmap.createBitmap(768,576,Bitmap.Config.ARGB_8888)
            state.value=EditorUiState(photo=source("fallback.heic"),original=original)
            await{view.bitmap===original}
            vm.detailPreviewEdge={768};val calls=mutableListOf<Boolean>()
            vm.renderPreview={_,_,_,detail->calls+=detail;Bitmap.createBitmap(if(detail)768 else 512,if(detail)576 else 384,Bitmap.Config.ARGB_8888)}
            val frameHeightBefore=view.height
            org.robolectric.shadows.ShadowToast.reset()
            vm.retry();view.pinch(3.0,200.0,150.0)
            await{attempted==1 && !vm.state.value.previewRefining}
            assertEquals(listOf(false,true),calls);assertEquals(768,view.bitmap!!.width);assertNull(view.detailKey)
            assertEquals(frameHeightBefore,view.height)
            assertEquals(dev.filmroom.roi.DetailFailure.EngineUnavailable,view.detailStatus.failure)
            val spinner=MainActivity::class.java.getDeclaredField("detailLoading").apply{isAccessible=true}.get(controller.get()) as android.widget.ProgressBar
            assertEquals(android.view.View.INVISIBLE,spinner.visibility)
            assertEquals(1,org.robolectric.shadows.ShadowToast.shownToastCount())
            assertFalse(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("private-user"))
            val info=MainActivity::class.java.getDeclaredField("infoButton").apply{isAccessible=true}.get(controller.get()) as android.widget.Button
            info.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val message=dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("引擎不可用"));assertFalse(message.contains("private-photo"));dialog.dismiss()
            repeat(20){view.pan(1.0,0.0)};shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,attempted);assertEquals(1,org.robolectric.shadows.ShadowToast.shownToastCount())
            vm.compare();await{view.bitmap===original};vm.compare();await{view.bitmap===vm.state.value.rendered}
            assertEquals(768,view.bitmap!!.width);assertTrue(vm.state.value.canExport)
        }finally{controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")}
    }

}
