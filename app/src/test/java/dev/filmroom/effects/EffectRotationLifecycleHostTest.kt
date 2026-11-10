package dev.filmroom.effects

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/** Real Activity/manifest/lifecycle regression. Codecs and MediaStore need device tests. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35], application=Application::class)
class EffectRotationLifecycleHostTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun prepare(){Dispatchers.setMain(dispatcher)}
    @After fun cleanup(){Dispatchers.resetMain()}

    private fun field(activity:PhotoEffectActivity,name:String):Any? =
        PhotoEffectActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun set(activity:PhotoEffectActivity,name:String,value:Any) {
        PhotoEffectActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.set(activity,value)
    }

    @Test fun manifestHandlesOnlySizeChangesAndDoesNotLockOrientation() {
        val app=RuntimeEnvironment.getApplication()
        val info=app.packageManager.getActivityInfo(ComponentName(app,PhotoEffectActivity::class.java),0)
        val expected=ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_SCREEN_LAYOUT
        assertEquals(expected,info.configChanges and expected)
        assertEquals(0,info.configChanges and ActivityInfo.CONFIG_LOCALE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,info.screenOrientation)
    }

    @Test fun videoOwnerSurvivesRepeatedRotationAndCompletesOnce() = rotateWhileWorking(false)
    @Test fun liveOwnerSurvivesRepeatedRotationAndCompletesOnce() = rotateWhileWorking(true)

    private fun rotateWhileWorking(live:Boolean) {
        Robolectric.buildActivity(PhotoEffectActivity::class.java).setup().use { controller ->
            val activity=controller.get()
            // Suppress photo I/O and codec capability checks; test their actual lifecycle owner.
            dispatcher.scheduler.runCurrent() // Register lifecycle cancellation before stopping startup jobs.
            activity.lifecycleScope.coroutineContext.cancelChildren()
            dispatcher.scheduler.runCurrent()
            set(activity,"working",true);set(activity,"liveExport",live)
            val cancel=field(activity,"cancelled") as AtomicBoolean
            val status=field(activity,"status") as TextView
            status.text="Exporting 35%"
            val release=CompletableDeferred<Unit>()
            var completed=0;var cleaned=0
            val job=activity.lifecycleScope.launch(start=CoroutineStart.UNDISPATCHED) {
                try { release.await();completed++;status.text="saved" }
                finally {cleaned++}
            }
            for((w,h) in listOf(800 to 400,400 to 800,800 to 400,400 to 800)) {
                controller.configurationChange(Configuration(activity.resources.configuration).apply {
                    orientation=if(w>h)Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp=w;screenHeightDp=h
                })
                dispatcher.scheduler.runCurrent()
                assertSame("Rotation must not replace the export owner",activity,controller.get())
                assertTrue(job.isActive);assertFalse(cancel.get())
                assertEquals(true,field(activity,"working"));assertEquals(live,field(activity,"liveExport"))
                assertSame(status,field(activity,"status"));assertEquals("Exporting 35%",status.text.toString())
                assertEquals(0,completed);assertEquals(0,cleaned)
            }
            release.complete(Unit);dispatcher.scheduler.runCurrent()
            assertTrue(job.isCompleted);assertFalse(job.isCancelled)
            assertEquals(1,completed);assertEquals(1,cleaned);assertEquals("saved",status.text.toString())
            set(activity,"working",false)
        }
    }

    @Test fun actualDestructionStillCancelsAndCleansUp() {
        val controller=Robolectric.buildActivity(PhotoEffectActivity::class.java).setup()
        val activity=controller.get()
        dispatcher.scheduler.runCurrent()
        activity.lifecycleScope.coroutineContext.cancelChildren();dispatcher.scheduler.runCurrent()
        val cancel=field(activity,"cancelled") as AtomicBoolean
        var cleaned=0;var saved=false
        val job=activity.lifecycleScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation();saved=true } finally {cleaned++}
        }
        controller.pause().stop().destroy();dispatcher.scheduler.runCurrent()
        assertTrue("Activity destruction sets its cancellation signal",cancel.get())
        assertTrue("Destroyed lifecycle cancels its export job",job.isCancelled)
        assertFalse(saved);assertEquals(1,cleaned)
    }
}
