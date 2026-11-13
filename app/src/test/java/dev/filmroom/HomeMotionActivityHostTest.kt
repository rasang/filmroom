package dev.filmroom

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.filmroom.effects.EffectKind
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h780dp-port-mdpi")
class HomeMotionActivityHostTest {
    private fun field(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun set(a:MainActivity,n:String,v:Any?)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.set(a,v)
    private fun touch(a:MainActivity,action:Int,x:Float=10f){
        val e=MotionEvent.obtain(0,0,action,x,10f,0)
        try{MainActivity::class.java.getDeclaredMethod("handleHomeMotionTouch",MotionEvent::class.java).apply{isAccessible=true}.invoke(a,e)}finally{e.recycle()}
    }
    private fun withReady(block:(MainActivity)->Unit){
        CameraLayoutRenderHostTest().chosenLanguageForLegacyFixture()
        val image=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888)
        try{Robolectric.buildActivity(MainActivity::class.java).setup().use{host->
            val a=host.get()
            CameraLayoutRenderHostTest::class.java.getDeclaredMethod("installFixture",MainActivity::class.java,Bitmap::class.java,EditorState::class.java).apply{isAccessible=true}.invoke(CameraLayoutRenderHostTest(),a,image,EditorState())
            shadowOf(Looper.getMainLooper()).idle()
            set(a,"homeMotionKind",EffectKind.LIGHT_TABLE)
            block(a)
        }}finally{image.recycle()}
    }
    @Test fun entryIsBetweenRecipeAndCompare(){withReady{a->
        val recipe=field(a,"recipeButton") as View;val live=field(a,"homeMotionButton") as View;val compare=field(a,"compare") as View
        fun margin(v:View)=(v.layoutParams as ViewGroup.MarginLayoutParams).rightMargin
        assertTrue(margin(recipe)>margin(live));assertTrue(margin(live)>margin(compare))
    }}
    @Test fun shortTapCancelsPendingHold(){withReady{a->
        touch(a,MotionEvent.ACTION_DOWN);assertNotNull(field(a,"homeMotionHold"))
        touch(a,MotionEvent.ACTION_UP);assertNull(field(a,"homeMotionHold"));assertFalse(field(a,"homeMotionHeld") as Boolean)
    }}
    @Test fun dragAndMultitouchCancelPendingHold(){withReady{a->
        touch(a,MotionEvent.ACTION_DOWN);touch(a,MotionEvent.ACTION_MOVE,300f);assertNull(field(a,"homeMotionHold"))
        touch(a,MotionEvent.ACTION_DOWN);touch(a,MotionEvent.ACTION_POINTER_DOWN);assertNull(field(a,"homeMotionHold"))
    }}
    @Test fun selectedKindAndAutoSourceAreSaved(){withReady{a->
        set(a,"homeMotionAutoSource","already-played")
        val out=Bundle()
        MainActivity::class.java.getDeclaredMethod("onSaveInstanceState",Bundle::class.java).apply{isAccessible=true}.invoke(a,out)
        assertEquals("LIGHT_TABLE",out.getString("homeMotionKind"));assertEquals("already-played",out.getString("homeMotionAutoSource"))
    }}
    /** Host lifecycle only: this does not execute a device GLES video renderer. */
    @Test fun liveSelectionKeepsVisibleRefinementAndLifecycleControlsIt(){
        CameraLayoutRenderHostTest().chosenLanguageForLegacyFixture()
        val host=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a=host.get()
            val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
            val visible=EditorViewModel::class.java.getDeclaredField("previewForeground").apply{isAccessible=true}
            assertTrue(visible.getBoolean(vm))
            set(a,"homeMotionKind",EffectKind.LIGHT_TABLE)
            // Mount's idle-work action must not turn off the visible frame's refinement.
            vm.setPrewarmForeground(false);assertTrue(visible.getBoolean(vm))
            host.pause();assertFalse(visible.getBoolean(vm))
            host.resume();assertTrue(visible.getBoolean(vm))
            set(a,"homeMotionKind",null)
            MainActivity::class.java.getDeclaredMethod("stopHomeMotion").apply{isAccessible=true}.invoke(a)
            assertTrue(visible.getBoolean(vm))
        }finally{host.pause().stop().destroy()}
    }
}
