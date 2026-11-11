package dev.filmroom.effects

import android.app.Application
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class EffectPreviewStageTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private fun layout(view:View,width:Int,height:Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
    }
    @Test fun legacySizeChangedFixArrivesAfterChildMeasurement() {
        val stage=object:android.widget.FrameLayout(context) {
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
                super.onSizeChanged(w,h,oldw,oldh)
                getChildAt(0).layoutParams=LayoutParams(w,(w/(4f/3)).toInt(),android.view.Gravity.CENTER)
            }
        }
        val page=View(context)
        stage.addView(page,android.widget.FrameLayout.LayoutParams(-1,-1))
        layout(stage,1000,1400)
        // The old code requests 750, but this traversal still lays out 1400 pixels.
        assertEquals(750,page.layoutParams.height)
        assertEquals(1400,page.height)
    }
    @Test fun landscapeFirstLayoutInsideWeightedPortraitScreen() {
        val root=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        root.addView(View(context),LinearLayout.LayoutParams(-1,100))
        val stage=EffectPreviewStage(context)
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(View(context),LinearLayout.LayoutParams(-1,300))
        val page=View(context)
        stage.addView(page,EffectPreviewStage.PageParams(4096f/3072))
        layout(root,1000,1800)
        assertEquals(1000,page.width)
        assertEquals(750,page.height)
        assertEquals((stage.height-page.height)/2,page.top)
    }
    @Test fun changesInAvailableHeightAndAspectAreAppliedInSameTraversal() {
        val stage=EffectPreviewStage(context)
        val page=View(context)
        stage.addView(page,EffectPreviewStage.PageParams(4f/3))
        layout(stage,1000,1400)
        assertEquals(750,page.height)
        // Audio/pairing controls, rotation and split-screen change stage bounds.
        layout(stage,1000,450)
        assertEquals(600,page.width);assertEquals(450,page.height)
        page.layoutParams=EffectPreviewStage.PageParams(3f/4)
        layout(stage,1000,1400)
        assertEquals(1000,page.width);assertEquals(1333,page.height)
        page.layoutParams=EffectPreviewStage.PageParams(4f/3)
        layout(stage,1000,1400)
        assertEquals(1000,page.width);assertEquals(750,page.height)
    }
    @Test fun incomingPageKeepsOwnAspectDuringNavigation() {
        val stage=EffectPreviewStage(context)
        val outgoing=View(context);val incoming=View(context)
        stage.addView(outgoing,EffectPreviewStage.PageParams(4f/3))
        stage.addView(incoming,EffectPreviewStage.PageParams(3f/4))
        layout(stage,1000,1400)
        assertEquals(750,outgoing.height);assertEquals(1333,incoming.height)
        stage.removeView(outgoing)
        layout(stage,1000,900)
        assertEquals(675,incoming.width);assertEquals(900,incoming.height)
    }
}
