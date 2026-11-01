package dev.filmroom

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import android.widget.TextView
import dev.filmroom.model.EditHistory
import dev.filmroom.ui.SettledChoiceSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], qualifiers="w360dp-h780dp-mdpi")
class SettledChoiceSelectorHostTest {
    private fun layout(view: View, width: Int = 360) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.AT_MOST))
        view.layout(0,0,width,view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun touch(view: View, action: Int, x: Float, time: Long) {
        MotionEvent.obtain(0,time,action,x,24f,0).also { view.dispatchTouchEvent(it); it.recycle() }
    }
    @Test fun swipeSettlesWithoutTapAndCommitsOneHistoryEntry() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            val history=EditHistory(dev.filmroom.model.EditorState(metering="average")); val changes=mutableListOf<Int>()
            val selector=SettledChoiceSelector(c.get()).apply {
                bind("测光方式",listOf("平均","中位数","中央重点","局部"),0) {
                    changes.add(it); history.update(history.current.copy(metering=listOf("average","median","center_weighted","partial")[it]))
                }
            }
            c.get().setContentView(selector);layout(selector)
            val rail=selector.getChildAt(1) as HorizontalScrollView
            val item=((rail.getChildAt(0) as ViewGroup).getChildAt(0)).width
            touch(rail,MotionEvent.ACTION_DOWN,220f,0)
            touch(rail,MotionEvent.ACTION_MOVE,170f,30)
            rail.scrollTo(item,0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertTrue("Dragging is not a committed change",changes.isEmpty())
            touch(rail,MotionEvent.ACTION_UP,170f,500)
            // A fling can advance through multiple options. Only its final resting option commits.
            rail.scrollTo(item*3,0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            rail.scrollTo(item*2,0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(listOf(2),changes)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1,changes.size)
            assertTrue(history.canUndo);history.undo();assertFalse(history.canUndo)
        }
    }
    @Test fun layoutRestoreAndRotationDoNotCommit() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            var changes=0
            val selector=SettledChoiceSelector(c.get()).apply {bind("测光方式",listOf("平均","中位数","中央重点"),2){changes++}}
            c.get().setContentView(selector);layout(selector);layout(selector,740)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0,changes)
            assertTrue(selector.contentDescription.contains("中央重点"))
        }
    }
    @Test fun accessibleActionsAndArrowsAreAlternativesAndDisabledMeansDisabled() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            val changes=mutableListOf<Int>()
            val selector=SettledChoiceSelector(c.get()).apply {bind("自动曝光",listOf("关闭","开启"),0){changes.add(it)}}
            c.get().setContentView(selector);layout(selector)
            assertTrue(selector.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(listOf(1),changes)
            (selector.getChildAt(0) as TextView).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(listOf(1,0),changes)
            selector.isEnabled=false
            selector.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null)
            (selector.getChildAt(2) as TextView).performClick()
            assertEquals(listOf(1,0),changes)
        }
    }
    @Test fun removingActiveSelectorDiscardsPendingGesture() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            var changes=0
            val selector=SettledChoiceSelector(c.get()).apply {bind("测光方式",listOf("平均","中位数"),0){changes++}}
            c.get().setContentView(selector);layout(selector)
            val rail=selector.getChildAt(1) as HorizontalScrollView
            touch(rail,MotionEvent.ACTION_DOWN,160f,0);touch(rail,MotionEvent.ACTION_UP,60f,500)
            rail.scrollTo(88,0)
            c.get().setContentView(View(c.get()))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0,changes)
        }
    }
    @Test fun cancelAndDisableDiscardUnsettledChanges() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            var changes=0
            val selector=SettledChoiceSelector(c.get()).apply {bind("测光方式",listOf("平均","中位数"),0){changes++}}
            c.get().setContentView(selector);layout(selector)
            val rail=selector.getChildAt(1) as HorizontalScrollView
            touch(rail,MotionEvent.ACTION_DOWN,160f,0);rail.scrollTo(88,0)
            touch(rail,MotionEvent.ACTION_CANCEL,60f,500)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0,changes)
            touch(rail,MotionEvent.ACTION_DOWN,160f,0);touch(rail,MotionEvent.ACTION_UP,60f,500);rail.scrollTo(88,0)
            selector.isEnabled=false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0,changes)
        }
    }

    @Test fun twoChoiceFirstLayoutMeasuresPaddingBeforeChildren() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            val selector=SettledChoiceSelector(c.get()).apply {bind("自动曝光",listOf("关闭","开启"),0){fail("layout must be silent")}}
            c.get().setContentView(selector)
            // Check the first traversal itself, not a second traversal that hides stale geometry.
            selector.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.AT_MOST))
            selector.layout(0,0,360,selector.measuredHeight)
            val viewport=selector.getChildAt(1) as HorizontalScrollView
            val strip=viewport.getChildAt(0) as ViewGroup
            assertEquals("Rail measurement must include both centering insets",strip.paddingLeft+strip.paddingRight+(0 until strip.childCount).sumOf{strip.getChildAt(it).measuredWidth},strip.measuredWidth)
            assertTrue("Selected item must fit inside the measured rail",strip.getChildAt(0).right<=strip.width)
        }
    }

    @Test fun fewChoicesRemainCenteredAcrossDensityResizeAndRebinding() {
        for(density in listOf(160,440,480)) {
            org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h780dp-${density}dpi")
            Robolectric.buildActivity(Activity::class.java).setup().use { c ->
                var changes=0
                val selector=SettledChoiceSelector(c.get())
                c.get().setContentView(selector)
                val factor=c.get().resources.displayMetrics.density
                for(options in listOf(listOf("关闭"),listOf("关闭","开启"),listOf("平均","中央重点","局部"),List(7){"选项$it"})) {
                    for(index in options.indices) {
                        selector.bind("选择",options,index){changes++}
                        for(widthDp in listOf(360,411,740,360)) {
                            layout(selector,(widthDp*factor).toInt())
                            val viewport=selector.getChildAt(1) as HorizontalScrollView
                            val strip=viewport.getChildAt(0) as ViewGroup
                            val item=strip.getChildAt(index) as TextView
                            assertEquals(strip.paddingLeft+strip.paddingRight+(0 until strip.childCount).sumOf{strip.getChildAt(it).measuredWidth},strip.measuredWidth)
                            assertTrue(item.left>=0 && item.right<=strip.width)
                            assertTrue("Selected label must fit density=$density widthDp=$widthDp options=$options index=$index: item=${item.left}..${item.right}, scroll=${viewport.scrollX}, viewport=${viewport.width}, inset=${strip.paddingLeft}/${strip.paddingRight}",item.left-viewport.scrollX>=0 && item.right-viewport.scrollX<=viewport.width)
                            assertTrue("Selected label must be centered",kotlin.math.abs(item.left+item.width/2-viewport.scrollX-viewport.width/2)<=1)
                            assertEquals(options[index],item.text.toString())
                            assertTrue("Text itself fits",item.layout.getLineWidth(0)<=item.width-item.paddingLeft-item.paddingRight)
                        }
                    }
                }
                assertEquals(0,changes)
            }
        }
    }

    @Test fun multilingualLabelsUseFiniteMeasuredTextLayouts() {
        val sets=listOf(listOf("平均","中央重点","高光重点"),listOf("Average","Center-weighted","Highlight-weighted"),listOf("平均測光","中央重点測光","ハイライト重点測光"),listOf("평균 측광","중앙부 중점 측광","하이라이트 중점 측광"))
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            for(labels in sets)for(width in listOf(320,740)){
                val selector=SettledChoiceSelector(c.get()).apply{bind("Metering",labels,1){}}
                c.get().setContentView(selector);layout(selector,width)
                val items=(selector.getChildAt(1) as HorizontalScrollView).getChildAt(0) as ViewGroup
                for(i in 0 until items.childCount){
                    val item=items.getChildAt(i) as TextView
                    assertEquals(labels[i],item.text.toString())
                    assertTrue("Unbounded selector label ${item.text}",item.layout.width<100000)
                    assertTrue("Offscreen selector text ${item.text}",kotlin.math.abs(item.scrollX)<=item.width)
                    assertTrue("Clipped selector label ${item.text}",item.layout.getLineWidth(0)<=item.width-item.compoundPaddingLeft-item.compoundPaddingRight+1f)
                }
            }
        }
    }
}
