package dev.filmroom.effects

import android.app.Application
import android.view.View
import android.widget.Switch
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual Activity's motion ownership setter, shared by every load/page path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class EffectOriginalAudioVisibilityTest {
    private fun fixture():Pair<PhotoEffectActivity,Switch> {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val activity=PhotoEffectActivity()
        val control=Switch(context).apply{isChecked=false;visibility=View.GONE}
        PhotoEffectActivity::class.java.getDeclaredField("liveOriginalAudioSwitch").apply{isAccessible=true}.set(activity,control)
        return activity to control
    }
    private fun input(activity:PhotoEffectActivity,file:File?) {
        PhotoEffectActivity::class.java.getDeclaredMethod("setSourceMotionFile",File::class.java).apply{isAccessible=true}.invoke(activity,file)
    }
    @Test fun ordinaryOrFailedExtractionStaysHidden(){
        val (activity,control)=fixture()
        input(activity,null)
        assertEquals(View.GONE,control.visibility)
    }
    @Test fun successfullyPreparedLiveShowsControl(){
        val (activity,control)=fixture()
        input(activity,File("extracted-video.mp4"))
        assertEquals(View.VISIBLE,control.visibility)
    }
    @Test fun liveToStillPageHidesAndNextLiveShowsWithoutChangingMuteChoice(){
        val (activity,control)=fixture()
        input(activity,File("one.mp4"));input(activity,null)
        assertEquals(View.GONE,control.visibility)
        input(activity,File("two.mp4"))
        assertEquals(View.VISIBLE,control.visibility)
        assertFalse(control.isChecked)
    }
    @Test fun clearOrReselectFailureHidesPreviouslyVisibleSwitch(){
        val (activity,control)=fixture()
        input(activity,File("old.mp4"));input(activity,null)
        assertEquals(View.GONE,control.visibility)
    }
    @Test fun replayDoesNotChangeIdentifiedInputOrPreference(){
        val (activity,control)=fixture()
        val movie=File("current.mp4")
        input(activity,movie);input(activity,movie)
        assertEquals(View.VISIBLE,control.visibility)
        assertFalse(control.isChecked)
    }
}
