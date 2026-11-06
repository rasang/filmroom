package dev.filmroom.engine

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class IdlePrewarmScheduleHostTest {
    @Test fun fastDecodeDoesNotStartOrDuplicateRefinement()=runTest {
        val app=ApplicationProvider.getApplicationContext<Application>();var started=0
        val scheduler=IdlePreviewPrewarmer(app,this,{_,_->started++;"published"},{},{true})
        scheduler.setForeground(true);scheduler.begin(EditorState());advanceTimeBy(127);scheduler.end();runCurrent()
        assertEquals(0,started);scheduler.close()
    }
    @Test fun oneDecodeWindowUsesExactSelectedStateAndNeverPolls()=runTest {
        val app=ApplicationProvider.getApplicationContext<Application>();val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(true);shadowOf(power).setIsPowerSaveMode(false)
        val seen=mutableListOf<EditorState>();val selected=EditorState().copy(filmStock="kodak_ektar_100")
        val scheduler=IdlePreviewPrewarmer(app,this,{p,valid->assertTrue(valid());seen+=p;"published"},{},{true})
        scheduler.setForeground(true);scheduler.begin(selected);advanceTimeBy(128);runCurrent()
        assertEquals(listOf(selected),seen);advanceTimeBy(10_000);runCurrent();assertEquals(1,seen.size);scheduler.close()
    }
    @Test fun backgroundCloseAndSupersededDecodeRejectLateWork()=runTest {
        val app=ApplicationProvider.getApplicationContext<Application>();val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(true);var activeCheck:(()->Boolean)?=null;var started=0
        val scheduler=IdlePreviewPrewarmer(app,this,{_,valid->started++;activeCheck=valid;awaitCancellation()},{},{true})
        scheduler.setForeground(true);scheduler.begin(EditorState());advanceTimeBy(128);runCurrent();assertEquals(1,started)
        scheduler.setForeground(false);runCurrent();assertFalse(activeCheck!!.invoke())
        scheduler.begin(EditorState().copy(filmStock="kodak_ektar_100"));scheduler.close();advanceUntilIdle();assertEquals(1,started)
    }
    @Test fun powerSaveScreenAndExportOrInteractiveWorkPreventAdmission()=runTest {
        val app=ApplicationProvider.getApplicationContext<Application>();val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(true);shadowOf(power).setIsPowerSaveMode(true);var started=0;var idle=true
        val scheduler=IdlePreviewPrewarmer(app,this,{_,_->started++;"published"},{},{idle})
        scheduler.setForeground(true);scheduler.begin(EditorState());advanceTimeBy(128);runCurrent();assertEquals(0,started)
        shadowOf(power).setIsPowerSaveMode(false);idle=false;scheduler.begin(EditorState());advanceTimeBy(128);runCurrent();assertEquals(0,started)
        idle=true;scheduler.begin(EditorState());app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF));shadowOf(android.os.Looper.getMainLooper()).idle();advanceTimeBy(128);runCurrent();assertEquals(0,started)
        scheduler.close()
    }
    @Test fun queueActivityBlocksTimerWhileLeavingScientificExportInactive()=runTest {
        val app=ApplicationProvider.getApplicationContext<Application>();val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(true);shadowOf(power).setIsPowerSaveMode(false)
        var starts=0
        val scheduler=IdlePreviewPrewarmer(app,this,{_,_->starts++;"published"},{},{true})
        try {
            scheduler.setForeground(true)
            ExportWorkAdmissions.withQueueActivity {
                assertFalse(ExportWorkAdmissions.active.value)
                scheduler.begin(EditorState());advanceTimeBy(128);runCurrent();assertEquals(0,starts)
            }
            scheduler.begin(EditorState());advanceTimeBy(128);runCurrent();assertEquals(1,starts)
        } finally {scheduler.close()}
        assertFalse(ExportWorkAdmissions.prewarmBlocked)
    }
    @Test fun allowedCallbackRechecksQueueThatStartedAfterTimerAdmission()=runTest {
        val app=ApplicationProvider.getApplicationContext<Application>();val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(true);shadowOf(power).setIsPowerSaveMode(false)
        val entered=CompletableDeferred<Unit>();val checkAgain=CompletableDeferred<Unit>();var latest:Boolean?=null
        val scheduler=IdlePreviewPrewarmer(app,this,{_,allowed->
            assertTrue(allowed());entered.complete(Unit);checkAgain.await();latest=allowed();"cancelled"
        },{},{true})
        try {
            scheduler.setForeground(true);scheduler.begin(EditorState());advanceTimeBy(128);runCurrent();assertTrue(entered.isCompleted)
            ExportWorkAdmissions.withQueueActivity {
                checkAgain.complete(Unit);runCurrent();assertEquals(false,latest)
            }
        } finally {checkAgain.complete(Unit);scheduler.close()}
    }
    @Test fun thermalAndLowMemoryCallbacksCancelActiveWork()=runTest {
        val app=ApplicationProvider.getApplicationContext<Application>();val power=app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(true);shadowOf(power).setIsPowerSaveMode(false)
        var activeCheck:(()->Boolean)?=null;var started=0
        val scheduler=IdlePreviewPrewarmer(app,this,{_,valid->started++;activeCheck=valid;awaitCancellation()},{},{true})
        scheduler.setForeground(true);scheduler.begin(EditorState());advanceTimeBy(128);runCurrent();assertEquals(1,started)
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_LIGHT);shadowOf(android.os.Looper.getMainLooper()).idle();runCurrent();assertFalse(activeCheck!!.invoke())
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
        scheduler.begin(EditorState());advanceTimeBy(128);runCurrent();assertEquals(2,started)
        app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);runCurrent();assertFalse(activeCheck!!.invoke());scheduler.close()
    }

}
