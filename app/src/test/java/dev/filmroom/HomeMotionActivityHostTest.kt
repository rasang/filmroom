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
    @Test fun pausedLiveRestoresMagnificationAndPositionOnlyForSamePhotoAndMode(){withReady{a->
        val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
        val state=vm.state.value
        fun player()=dev.filmroom.effects.HomeMotionPreview(a,EffectKind.LIGHT_TABLE,
            dev.filmroom.effects.HomeMotionSnapshot.capture(state,EffectKind.LIGHT_TABLE),false){}
        val saved=dev.filmroom.effects.HomeMotionViewport.State(2.5f,.12f,-.08f)
        set(a,"homeMotionRefreshPosted",true) // Keep native preparation outside this host lifecycle test.
        fun pause(){
            val p=player();p.restoreViewport(saved)
            set(a,"homeMotion",p);set(a,"homeMotionPhotoKey",state.photo!!.cacheKey)
            MainActivity::class.java.getDeclaredMethod("onPause").apply{isAccessible=true}.invoke(a)
            MainActivity::class.java.getDeclaredMethod("onResume").apply{isAccessible=true}.invoke(a)
            assertNull(field(a,"homeMotion"));assertNotNull(field(a,"pausedMotionViewport"))
        }
        val restore=MainActivity::class.java.getDeclaredMethod("restoreHomeMotionViewport",dev.filmroom.effects.HomeMotionPreview::class.java,String::class.java,EffectKind::class.java).apply{isAccessible=true}
        pause()
        val bundle=Bundle()
        MainActivity::class.java.getDeclaredMethod("savePausedMotionViewport",Bundle::class.java).apply{isAccessible=true}.invoke(a,bundle)
        set(a,"pausedMotionViewport",null)
        MainActivity::class.java.getDeclaredMethod("readPausedMotionViewport",Bundle::class.java).apply{isAccessible=true}.invoke(a,bundle)
        assertNotNull(field(a,"pausedMotionViewport"))
        val same=player()
        try{restore.invoke(a,same,state.photo!!.cacheKey,EffectKind.LIGHT_TABLE);assertEquals(saved,same.saveViewport());assertNull(field(a,"pausedMotionViewport"))}finally{same.close()}
        pause()
        val other=player()
        try{restore.invoke(a,other,"different-photo",EffectKind.LIGHT_TABLE);assertEquals(1f,other.saveViewport().scale,0f)}finally{other.close()}
        pause()
        val differentMode=player()
        try{restore.invoke(a,differentMode,state.photo!!.cacheKey,EffectKind.OPTICAL_LEAK);assertEquals(1f,differentMode.saveViewport().scale,0f)}finally{differentMode.close()}
    }}
    @Test fun hiddenPhotoRefinementDoesNotLeakSpinnerOrFailureIntoLive(){withReady{a->
        val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
        vm.localDetailEnabled=true
        set(a,"homeMotionRefreshPosted",true)
        val update=MainActivity::class.java.getDeclaredMethod("updateDetailStatus",dev.filmroom.roi.DetailStatus::class.java).apply{isAccessible=true}
        val refining=dev.filmroom.roi.DetailStatus(dev.filmroom.roi.DetailPhase.Refining)
        val spinner=field(a,"detailLoading") as View
        update.invoke(a,refining);assertEquals(View.VISIBLE,spinner.visibility)
        val player=dev.filmroom.effects.HomeMotionPreview(a,EffectKind.LIGHT_TABLE,
            dev.filmroom.effects.HomeMotionSnapshot.capture(vm.state.value,EffectKind.LIGHT_TABLE),false){}
        set(a,"homeMotion",player)
        try {
            update.invoke(a,refining);assertEquals(View.INVISIBLE,spinner.visibility)
            val feedback=field(a,"detailFeedback") as dev.filmroom.ui.DetailFeedback
            val before=feedback.savedKeys()
            update.invoke(a,dev.filmroom.roi.DetailStatus(dev.filmroom.roi.DetailPhase.Fallback,failure=dev.filmroom.roi.DetailFailure.RenderFailed))
            assertEquals(before,feedback.savedKeys()) // Hidden failure was not consumed/toasted.
            vm.compare();update.invoke(a,refining)
            assertEquals(View.INVISIBLE,spinner.visibility) // Original comparison never refines.
            vm.compare();set(a,"homeMotion",null)
            update.invoke(a,refining);assertEquals(View.VISIBLE,spinner.visibility)
        }finally{set(a,"homeMotion",null);player.close();vm.localDetailEnabled=false}
    }}
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
    @Test fun liveOwnsZoomDemandButComparisonReturnsItToPhotoView(){withReady{a->
        val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
        val snapshot=dev.filmroom.effects.HomeMotionSnapshot.capture(vm.state.value,EffectKind.LIGHT_TABLE)
        val player=dev.filmroom.effects.HomeMotionPreview(a,EffectKind.LIGHT_TABLE,snapshot,false){}
        set(a,"homeMotion",player);set(a,"homeMotionState",vm.state.value);set(a,"homeMotionPhotoKey",vm.state.value.photo?.cacheKey)
        set(a,"homeMotionRefreshPosted",true)
        val photo=field(a,"photo") as dev.filmroom.ui.PhotoView
        val controller=field(a,"zoomFrames")!!
        val zoom=controller.javaClass.getDeclaredField("zoom").apply{isAccessible=true}
        zoom.setDouble(controller,2.5)
        photo.onZoomViewportChanged?.invoke(1.0,false)
        assertEquals(2.5,zoom.getDouble(controller),0.0)
        vm.compare()
        photo.onZoomViewportChanged?.invoke(1.75,false)
        assertEquals(1.75,zoom.getDouble(controller),0.0)
        touch(a,MotionEvent.ACTION_DOWN)
        assertNull(field(a,"homeMotionHold"))
        assertFalse(field(a,"homeMotionHeld") as Boolean)
        vm.compare()
        zoom.setDouble(controller,2.5)
        photo.onZoomViewportChanged?.invoke(1.0,false)
        assertEquals(2.5,zoom.getDouble(controller),0.0)
    }}
    @Test fun selectedKindAndAutoSourceAreSaved(){withReady{a->
        set(a,"homeMotionAutoSource","already-played")
        val out=Bundle()
        MainActivity::class.java.getDeclaredMethod("onSaveInstanceState",Bundle::class.java).apply{isAccessible=true}.invoke(a,out)
        assertEquals("LIGHT_TABLE",out.getString("homeMotionKind"));assertEquals("already-played",out.getString("homeMotionAutoSource"))
    }}
    /** Host View/state regression only; no GLES player or device frame timing is exercised. */
    @Test fun compareRetainsLiveStageForBothModesAndDuringParameterUpdates(){
        for(kind in listOf(EffectKind.LIGHT_TABLE,EffectKind.OPTICAL_LEAK))withReady{a->
            val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
            val frozen=vm.state.value
            val stage=View(a)
            val parent=a.findViewById<ViewGroup>(android.R.id.content)
            parent.addView(stage)
            val stageIndex=parent.indexOfChild(stage)
            set(a,"homeMotionKind",kind);set(a,"homeMotionStage",stage);set(a,"homeMotionState",frozen)
            // Avoid scheduling native preparation: exercise the synchronous bind contract.
            set(a,"homeMotionRefreshPosted",true)
            val bind=MainActivity::class.java.getDeclaredMethod("bind",EditorUiState::class.java).apply{isAccessible=true}
            bind.invoke(a,frozen.copy(compare=true))
            assertSame(stage,field(a,"homeMotionStage"));assertSame(parent,stage.parent)
            assertEquals(View.VISIBLE,stage.visibility);assertEquals(-1f,stage.translationZ,0f);assertEquals(stageIndex,parent.indexOfChild(stage));assertSame(frozen.original,field(a,"lastBitmap"))
            bind.invoke(a,frozen)
            assertSame(stage,field(a,"homeMotionStage"));assertEquals(View.VISIBLE,stage.visibility);assertEquals(0f,stage.translationZ,0f);assertEquals(stageIndex,parent.indexOfChild(stage))
            val pending=frozen.copy(parameters=frozen.parameters.copy(brightness=.3f),updating=true,previewRefining=true)
            bind.invoke(a,pending)
            assertSame(stage,field(a,"homeMotionStage"));assertEquals(View.VISIBLE,stage.visibility);assertEquals(0f,stage.translationZ,0f);assertEquals(stageIndex,parent.indexOfChild(stage))
            bind.invoke(a,pending.copy(compare=true))
            assertSame(stage,field(a,"homeMotionStage"));assertEquals(View.VISIBLE,stage.visibility);assertEquals(-1f,stage.translationZ,0f);assertEquals(stageIndex,parent.indexOfChild(stage))
            bind.invoke(a,pending)
            assertSame(stage,field(a,"homeMotionStage"));assertEquals(View.VISIBLE,stage.visibility);assertEquals(0f,stage.translationZ,0f);assertEquals(stageIndex,parent.indexOfChild(stage))
            assertSame(frozen,field(a,"homeMotionState"))
        }
    }
    @Test fun compareWithoutLiveKeepsOrdinaryPreviewBehavior(){withReady{a->
        val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
        val state=vm.state.value
        val result=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888)
        try {
            set(a,"homeMotionKind",null)
            val bind=MainActivity::class.java.getDeclaredMethod("bind",EditorUiState::class.java).apply{isAccessible=true}
            bind.invoke(a,state.copy(rendered=result,compare=true))
            assertNull(field(a,"homeMotionStage"));assertSame(state.original,field(a,"lastBitmap"))
            bind.invoke(a,state.copy(rendered=result,compare=false))
            assertNull(field(a,"homeMotionStage"));assertSame(result,field(a,"lastBitmap"))
        }finally{result.recycle()}
    }}
    @Test fun homeChooserHasOrderedIconsAndExactlyOneSelectedRow(){withReady{a->
        val choices=listOf(null,EffectKind.LIGHT_TABLE,EffectKind.OPTICAL_LEAK)
        val names=listOf(R.string.home_motion_none,R.string.home_motion_table,R.string.home_motion_finder)
        val choose=MainActivity::class.java.getDeclaredMethod("chooseHomeMotion").apply{isAccessible=true}
        val kindField=dev.filmroom.effects.EffectPickerIcon::class.java.getDeclaredField("kind").apply{isAccessible=true}
        val tintField=dev.filmroom.effects.EffectPickerIcon::class.java.getDeclaredField("tint").apply{isAccessible=true}
        for(selected in choices){
            set(a,"homeMotionKind",selected)
            choose.invoke(a)
            val popup=field(a,"homeMotionChooser") as android.widget.PopupWindow
            val rows=popup.contentView as ViewGroup
            assertEquals(3,rows.childCount)
            for(i in choices.indices){
                val row=rows.getChildAt(i) as android.widget.TextView
                assertEquals(dev.filmroom.i18n.L10n.text(names[i]),row.text.toString())
                assertEquals(choices[i]==selected,row.isSelected)
                val icon=row.compoundDrawablesRelative[0]
                assertTrue(icon is dev.filmroom.effects.EffectPickerIcon)
                assertEquals(choices[i],kindField.get(icon))
                val expectedTint=field(a,if(choices[i]==selected)"accent" else "ink") as Int
                assertEquals(expectedTint,row.currentTextColor);assertEquals(expectedTint,tintField.get(icon))
                assertEquals(28,icon.bounds.width());assertEquals(28,icon.bounds.height())
                assertEquals(12,row.compoundDrawablePadding)
            }
            popup.dismiss();assertNull(field(a,"homeMotionChooser"))
        }
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
