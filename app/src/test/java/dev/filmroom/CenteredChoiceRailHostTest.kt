package dev.filmroom

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.filmroom.ui.CenteredChoiceRail
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class CenteredChoiceRailHostTest {
    @org.junit.Before fun deterministicVsync() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    private fun waitMs(ms:Long) {
        // A paused vsync receiver produces one frame per clock advance, not every
        // frame inside a single large idleFor jump. Step at the simulated refresh rate.
        var remaining=ms
        while(remaining>0) {
            val step=minOf(16L,remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining-=step
        }
    }
    private fun layout(v:View,width:Int=360) {
        // Keep the component at its intended toolbar height during window traversals.
        v.layoutParams=android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,90)
        val host=(v.parent as? View) ?: v
        host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(90,View.MeasureSpec.EXACTLY))
        host.layout(0,0,width,90)
    }
    private fun rail(a:Activity,changes:MutableList<Int>):CenteredChoiceRail = CenteredChoiceRail(a).apply {
        repeat(8) { i -> items.addView(TextView(a).apply {
            text="选项 $i";textSize=18f;gravity=android.view.Gravity.CENTER;setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(48+i*15,65,80))
            setOnClickListener { select(i) }
        },LinearLayout.LayoutParams(100,90)) }
        configure(100,0){changes.add(it)}
    }
    private fun touch(v:View,action:Int,x:Float,time:Long) {
        MotionEvent.obtain(0,time,action,x,40f,0).also{v.dispatchTouchEvent(it);it.recycle()}
    }
    // Draw through the actual parent, as Android does. Direct draw() of the scrolled
    // view omits the parent's scroll transform and is not a valid viewport screenshot.
    private fun capture(v:View) = Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also{(v.parent as View).draw(Canvas(it))}

    @Test(timeout=30000) fun animatedSnapHasIntermediateFramesAndExactlyOneCommit() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            val changes=mutableListOf<Int>();val v=rail(c.get(),changes);c.get().setContentView(v);layout(v)
            v.scrollTo(133,0);v.select(3)
            assertEquals(133,v.scrollX);assertTrue(changes.isEmpty())
            val frames=File("build/ui-host-renders/centered-rail/animation").apply{mkdirs()}
            val positions=mutableListOf(v.scrollX)
            repeat(5) { frame ->
                waitMs(16);positions.add(v.scrollX)
                val image=capture(v)
                File(frames,"frame-${frame.toString().padStart(2,'0')}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
            File(frames,"positions.txt").writeText(positions.joinToString(","))
            assertTrue("Actual intermediate scroll position, not a hard cut: ${v.scrollX}",v.scrollX in 134..299)
            assertTrue(changes.isEmpty())
            waitMs(400)
            assertEquals(300,v.scrollX);assertEquals(listOf(3),changes)
            v.select(3);waitMs(400);assertEquals(listOf(3),changes)
        }
    }
    @Test(timeout=30000) fun fingerReleaseAnimatesToNearestItemRatherThanJumping() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            val changes=mutableListOf<Int>();val v=rail(c.get(),changes);c.get().setContentView(v);layout(v)
            touch(v,MotionEvent.ACTION_DOWN,240f,0)
            touch(v,MotionEvent.ACTION_MOVE,180f,50)
            v.scrollTo(145,0)
            touch(v,MotionEvent.ACTION_UP,180f,600)
            waitMs(180)
            assertTrue("Snap has started but not jumped: ${v.scrollX}",v.scrollX in 101..145)
            assertTrue(changes.isEmpty())
            waitMs(320)
            assertEquals(100,v.scrollX);assertEquals(listOf(1),changes)
        }
    }
    @Test(timeout=30000) fun touchInterruptsSnapAndCancelledGestureCannotCommitOldTarget() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            val changes=mutableListOf<Int>();val v=rail(c.get(),changes);c.get().setContentView(v);layout(v)
            v.select(7);waitMs(64);val mid=v.scrollX
            touch(v,MotionEvent.ACTION_DOWN,180f,100)
            waitMs(500)
            assertEquals(mid,v.scrollX);assertTrue(changes.isEmpty())
            touch(v,MotionEvent.ACTION_CANCEL,180f,700);waitMs(500)
            assertTrue(changes.isEmpty());assertEquals(0,v.scrollX)
            v.select(4);waitMs(50);v.isEnabled=false;waitMs(500)
            assertTrue(changes.isEmpty());assertEquals(0,v.scrollX)
        }
    }
    @Test(timeout=30000) fun centerFrameIsStationaryAndContentFadesAtBothEdges() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            val v=rail(c.get(),mutableListOf());c.get().setContentView(v);layout(v)
            val target=File("build/ui-host-renders/centered-rail").apply{mkdirs()}
            v.scrollTo(200,0);val a=capture(v)
            v.scrollTo(240,0);val b=capture(v)
            // Fixed top stroke is at the viewport's center before and during the drag.
            fun strokePixels(bitmap:Bitmap)=(0 until bitmap.width).filter { x ->
                val p=bitmap.getPixel(x,2)
                // The 1.5px stroke is antialiased over the card; do not expect opaque ink RGB.
                Color.red(p)>170 && Color.green(p) in 110..170 && Color.blue(p)<120
            }
            File(target,"before.png").outputStream().use{a.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(target,"during.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
            val before=strokePixels(a);val during=strokePixels(b)
            assertTrue(before.isNotEmpty());assertEquals(before,during)
            assertTrue(kotlin.math.abs((before.first()+before.last())/2-180)<=1)
            assertTrue("Left fades rather than clips",Color.alpha(b.getPixel(3,45)) in 1..90)
            assertTrue("Right fades rather than clips",Color.alpha(b.getPixel(356,45)) in 1..90)
            assertEquals(255,Color.alpha(b.getPixel(100,45)))
            File(target,"before.png").outputStream().use{a.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(target,"during.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
            a.recycle();b.recycle()
        }
    }
    @Test(timeout=30000) fun disabledSystemAnimationsSnapImmediatelyWithoutDuplicateCommit() {
        val method=android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}
        method.invoke(null,0f)
        try {
            Robolectric.buildActivity(Activity::class.java).setup().use { c ->
                val changes=mutableListOf<Int>();val v=rail(c.get(),changes);c.get().setContentView(v);layout(v)
                v.select(5)
                assertEquals(500,v.scrollX);assertEquals(listOf(5),changes)
                waitMs(500);assertEquals(listOf(5),changes)
            }
        } finally { method.invoke(null,1f) }
    }
    @Test(timeout=30000) fun firstLastStayCenteredAcrossResizeAndLayoutNeverCommits() {
        Robolectric.buildActivity(Activity::class.java).setup().use { c ->
            val changes=mutableListOf<Int>();val v=rail(c.get(),changes);c.get().setContentView(v)
            for(index in listOf(0,7)) for(width in listOf(320,361,740)) {
                v.configure(100,index){changes.add(it)};layout(v,width)
                val item=v.items.getChildAt(index)
                assertTrue(kotlin.math.abs(item.left+item.width/2-v.scrollX-v.width/2)<=1)
                assertTrue(item.left-v.scrollX>=0 && item.right-v.scrollX<=v.width)
            }
            assertTrue(changes.isEmpty())
        }
    }
}
