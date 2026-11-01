package dev.filmroom

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.view.View
import dev.filmroom.ui.ExportStageRail
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportStageRailHostTest {
    private fun value(rail:ExportStageRail,name:String):Any?=ExportStageRail::class.java.getDeclaredField(name).apply{isAccessible=true}.get(rail)
    @Test fun pausingAtSameProgressStopsMotionAndChangesSemanticState() {
        Robolectric.buildActivity(Activity::class.java).setup().use{controller->
            val rail=ExportStageRail(controller.get());controller.get().setContentView(rail)
            rail.layout(0,0,300,4)
            rail.bind(5,50,false,true);rail.bind(5,60,false,true)
            rail.bind(5,60,false,false)
            assertEquals(false,value(rail,"running"))
            assertFalse((value(rail,"animator") as? ValueAnimator)?.isRunning ?: false)
            assertEquals(.6f,value(rail,"fraction"))
        }
    }
    @Test fun offscreenAndStoppedRailsSettleWithoutBackgroundMotion() {
        Robolectric.buildActivity(Activity::class.java).setup().use{controller->
            val rail=ExportStageRail(controller.get());controller.get().setContentView(rail)
            rail.layout(0,0,300,4);rail.bind(5,50,false,true)
            rail.setViewportVisible(false);rail.bind(5,80,false,true)
            assertEquals(.8f,value(rail,"fraction"))
            assertFalse((value(rail,"animator") as? ValueAnimator)?.isRunning ?: false)
            rail.setViewportVisible(true);rail.bind(5,90,false,true);rail.pause()
            assertEquals(.9f,value(rail,"fraction"))
            assertFalse((value(rail,"animator") as? ValueAnimator)?.isRunning ?: false)
        }
    }
    @Test fun reducedMotionAndUnknownWorkDoNotInventProgress() {
        val durationScale=ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}
        durationScale.invoke(null,0f)
        try {
            Robolectric.buildActivity(Activity::class.java).setup().use{controller->
                val rail=ExportStageRail(controller.get());controller.get().setContentView(rail)
                rail.layout(0,0,300,4);rail.bind(5,50,false,true);rail.bind(5,80,false,true)
                assertEquals(.8f,value(rail,"fraction"));assertNull(value(rail,"animator"))
                rail.bind(6,null,false,true)
                assertEquals(0f,value(rail,"fraction"));assertEquals(false,value(rail,"measured"));assertNull(value(rail,"animator"))
            }
        }finally{durationScale.invoke(null,1f)}
    }
}
