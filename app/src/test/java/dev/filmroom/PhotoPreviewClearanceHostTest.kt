package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PhotoView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoPreviewClearanceHostTest {
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun layout(root:View,width:Int,height:Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        root.layout(0,0,width,height)
    }
    @Test fun fullPhotoViewportAvoidsUnchangedControlsInPortraitLandscapeAndAtZoom() {
        try {
            for((width,height) in listOf(320 to 640,740 to 320)) for(scale in listOf(1f,2f)) {
                RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-mdpi")
                RuntimeEnvironment.setFontScale(scale)
                val fixtures=CameraLayoutRenderHostTest().apply{chosenLanguageForLegacyFixture()}
                val image=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
                Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
                    val activity=host.get()
                    CameraLayoutRenderHostTest::class.java.getDeclaredMethod("installFixture",MainActivity::class.java,Bitmap::class.java,EditorState::class.java)
                        .apply{isAccessible=true}.invoke(fixtures,activity,image,EditorState())
                    val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    layout(root,width,height)
                    val photo=field(activity,"photo") as PhotoView
                    val compare=field(activity,"compare") as Button
                    val info=field(activity,"infoButton") as Button
                    assertTrue(photo.height>0)
                    assertTrue(photo.bottom+4<=compare.top)
                    assertTrue(photo.bottom+4<=info.top)
                    assertEquals(48,compare.width);assertEquals(48,compare.height)
                    val originalIcon=compare.compoundDrawables.filterNotNull().single()
                    val normalBounds=Rect(photo.left,photo.top,photo.right,photo.bottom)
                    photo.pinch(2.0,photo.width/2.0,photo.height/2.0)
                    val zoom=photo.transform().copyOf()
                    repeat(2) {
                        compare.performClick();shadowOf(Looper.getMainLooper()).idle();layout(root,width,height)
                        assertTrue(compare.isSelected)
                        assertSame(originalIcon,compare.compoundDrawables.filterNotNull().single())
                        compare.performClick();shadowOf(Looper.getMainLooper()).idle();layout(root,width,height)
                        assertFalse(compare.isSelected)
                        assertEquals(normalBounds,Rect(photo.left,photo.top,photo.right,photo.bottom))
                    }
                    assertArrayEquals(zoom,photo.transform(),.001f)
                    // A cancelled touch is still not a click or a mode change.
                    val down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,24f,24f,0)
                    val cancel=MotionEvent.obtain(0,10,MotionEvent.ACTION_CANCEL,24f,24f,0)
                    compare.dispatchTouchEvent(down);compare.dispatchTouchEvent(cancel)
                    down.recycle();cancel.recycle();shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(compare.isSelected)
                    // Loading visibility does not shrink the safety band or move the zoom anchor.
                    compare.visibility=View.GONE;info.visibility=View.GONE;layout(root,width,height)
                    assertEquals(normalBounds,Rect(photo.left,photo.top,photo.right,photo.bottom))
                    assertArrayEquals(zoom,photo.transform(),.001f)
                }
                image.recycle()
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }
}
