package dev.filmroom

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h780dp-mdpi")
class FilmCarouselPositionHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun root(a:MainActivity)=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun layout(a:MainActivity,w:Int=360,h:Int=780){val root=root(a);root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);shadowOf(android.os.Looper.getMainLooper()).idle()}
    private fun strip(a:MainActivity,key:String)=all(root(a)).filterIsInstance<HorizontalScrollView>().single{it.tag=="strip:$key"}
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    @Suppress("UNCHECKED_CAST") private fun fixture(a:MainActivity,parameters:EditorState=EditorState()){
        val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model(a)) as MutableStateFlow<EditorUiState>
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        flow.value=EditorUiState(photo=PhotoSource(File(a.cacheDir,"carousel-fixture"),"fixture.jpg","image/jpeg",8,8,100),original=bitmap,parameters=parameters)
        shadowOf(android.os.Looper.getMainLooper()).idle();layout(a)
    }
    private fun selectedVisible(a:MainActivity):Boolean{val scroll=strip(a,"films");val cards=scroll.getChildAt(0) as ViewGroup;val chosen=(0 until cards.childCount).map{cards.getChildAt(it)}.single{it.isSelected};return chosen.left>=scroll.scrollX&&chosen.right<=scroll.scrollX+scroll.width}
    private fun assertSelectedCard(a:MainActivity,expectedId:String){
        val viewport=strip(a,"films");val cards=viewport.getChildAt(0) as ViewGroup
        val chosen=(0 until cards.childCount).map{cards.getChildAt(it)}.single{it.isSelected} as Button
        assertEquals(expectedId,chosen.tag)
        assertEquals(expectedId,model(a).state.value.parameters.filmStock)
        assertTrue("The whole chosen card remains visible",selectedVisible(a))
        assertEquals("Film browser only constrains card bounds, with no snap rail",dev.filmroom.ui.FilmStockScrollView::class.java,viewport.javaClass)
        assertNotNull("Selected frame is attached to the card",chosen.background)
        assertTrue("Selected card has laid-out text",chosen.lineCount>0)
        for(line in 0 until chosen.lineCount)assertTrue("Card text is not clipped",chosen.layout.getLineWidth(line)<=chosen.width-chosen.compoundPaddingLeft-chosen.compoundPaddingRight+1)
    }
    @Test fun selectingLastFilmPreservesBrowsingOffsetThroughRenderAndCategoryRoundTrip(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a)
            val carousel=strip(a,"films");val cards=carousel.getChildAt(0) as ViewGroup
            val chosen=cards.getChildAt(cards.childCount-1) as Button;val expectedId=chosen.tag as String
            // Scrolling only browses; a tap selects without moving the viewport.
            carousel.scrollTo((cards.width-carousel.width).coerceAtLeast(0),0)
            assertTrue(chosen.left>=carousel.scrollX && chosen.right<=carousel.scrollX+carousel.width)
            val x=carousel.scrollX;assertTrue(x>0)
            chosen.performClick();layout(a)
            assertEquals("Clicking the last item must keep the exact browse position",x,strip(a,"films").scrollX)
            assertSelectedCard(a,expectedId)
            val looper=shadowOf(android.os.Looper.getMainLooper());looper.idleFor(java.time.Duration.ofMillis(150))
            val deadline=System.nanoTime()+3_000_000_000L
            while(model(a).state.value.updating&&System.nanoTime()<deadline){Thread.sleep(10);looper.idle()}
            layout(a);assertFalse(model(a).state.value.updating)
            assertEquals(x,strip(a,"films").scrollX);assertSelectedCard(a,expectedId)
            all(root(a)).filterIsInstance<Button>().first{it.text.toString()=="调整"}.performClick();layout(a)
            all(root(a)).filterIsInstance<Button>().first{it.text.toString()=="胶片"}.performClick();layout(a)
            assertEquals(x,strip(a,"films").scrollX);assertSelectedCard(a,expectedId)
        }
    }
    private fun touch(view:View,action:Int,x:Float,y:Float,down:Long,time:Long){
        android.view.MotionEvent.obtain(down,time,action,x,y,0).also{view.dispatchTouchEvent(it);it.recycle()}
    }
    @Test fun realDragOnlyBrowsesAndDoesNotCommitOrSnapWhileRealTapSelects(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a);val carousel=strip(a,"films")
            carousel.scrollTo(0,0);val before=model(a).state.value.parameters
            val beforeUndo=model(a).state.value.canUndo
            val y=carousel.height/2f;val right=carousel.width-16f;val down=android.os.SystemClock.uptimeMillis()
            touch(carousel,android.view.MotionEvent.ACTION_DOWN,right,y,down,down)
            repeat(8){i->touch(carousel,android.view.MotionEvent.ACTION_MOVE,right-(right-16f)*(i+1)/8,y,down,down+40L*(i+1))}
            touch(carousel,android.view.MotionEvent.ACTION_UP,16f,y,down,down+360)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
            assertTrue("Native drag actually scrolls the list",carousel.scrollX>0)
            assertEquals("Browse drag never changes the film or parameters",before,model(a).state.value.parameters)
            assertEquals(beforeUndo,model(a).state.value.canUndo)
            // Stop inertia at an intentionally non-card-aligned browse position; it must stay there.
            carousel.fling(0);carousel.computeScroll();carousel.scrollTo(173,0)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
            assertEquals("No centered snap after browsing",173,carousel.scrollX)
            val cards=carousel.getChildAt(0) as ViewGroup
            val target=(0 until cards.childCount).map{cards.getChildAt(it)}.first{it.left>=carousel.scrollX&&it.right<=carousel.scrollX+carousel.width}
            val targetId=target.tag as String;assertNotEquals(before.filmStock,targetId)
            val x=target.left-carousel.scrollX+target.width/2f;val now=android.os.SystemClock.uptimeMillis()
            touch(carousel,android.view.MotionEvent.ACTION_DOWN,x,y,now,now)
            touch(carousel,android.view.MotionEvent.ACTION_UP,x,y,now,now+80)
            shadowOf(android.os.Looper.getMainLooper()).idle();layout(a)
            assertEquals("Tapping selects without repositioning",173,strip(a,"films").scrollX)
            assertSelectedCard(a,targetId)
            model(a).undo();layout(a)
            assertEquals("Undo restores the previous chosen stock",before.filmStock,model(a).state.value.parameters.filmStock)
            assertSelectedCard(a,before.filmStock)
        }
    }
    @Test fun unrelatedParameterRebindPreservesBrowsedOffsetAndSelectedItem(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a);val chosen=model(a).state.value.parameters.filmStock
            val carousel=strip(a,"films");carousel.scrollTo(137,0)
            model(a).parameters(model(a).state.value.parameters.copy(brightness=.2f));layout(a)
            assertEquals(137,strip(a,"films").scrollX)
            val cards=strip(a,"films").getChildAt(0) as ViewGroup
            assertEquals(chosen,(0 until cards.childCount).map{cards.getChildAt(it)}.single{it.isSelected}.tag)
            assertEquals(.2f,model(a).state.value.parameters.brightness,0f)
        }
    }
    @Test fun savedPositionRestoresOnRecreationAndWidthChangeKeepsSelectedFilmVisible(){
        val saved=Bundle();lateinit var parameters:EditorState;var x=0
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a);val scroll=strip(a,"films");scroll.fullScroll(View.FOCUS_RIGHT);layout(a)
            val cards=scroll.getChildAt(0) as ViewGroup;cards.getChildAt(cards.childCount-1).performClick();layout(a)
            parameters=model(a).state.value.parameters;x=strip(a,"films").scrollX;c.saveInstanceState(saved)
        }
        Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible().use{c->
            fixture(c.get(),parameters);layout(c.get());assertEquals(x,strip(c.get(),"films").scrollX);assertTrue(selectedVisible(c.get()))
        }
        RuntimeEnvironment.setQualifiers("w740dp-h320dp-mdpi")
        try{Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible().use{c->
            fixture(c.get(),parameters);layout(c.get(),740,320);assertTrue(strip(c.get(),"films").scrollX>0);assertTrue(selectedVisible(c.get()))
        }}finally{RuntimeEnvironment.setQualifiers("w360dp-h780dp-mdpi")}
    }
    @Test fun filmClicksPreserveExplicitRouteAndCorrectionControlsExplainTemporaryDisable(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();val original=EditorState(brightness=.6f,grain=.2f,scanWhiteCorrection=true,scanBlackCorrection=true).selectOutputRoute("direct")
            fixture(a,original)
            fun clickFilm(id:String){all(strip(a,"films")).filterIsInstance<Button>().single{it.tag==id}.performClick();layout(a)}
            clickFilm("kodak_ektachrome_100")
            assertEquals("direct",model(a).state.value.parameters.outputRoute)
            assertEquals("direct",model(a).state.value.parameters.preferredOutputRoute)
            clickFilm("kodak_gold_200")
            val current=model(a).state.value.parameters
            assertEquals("direct",current.outputRoute);assertEquals("direct",current.preferredOutputRoute)
            assertEquals(.6f,current.brightness,0f);assertEquals(.2f,current.grain,0f)
            assertTrue(current.scanWhiteCorrection);assertTrue(current.scanBlackCorrection)
            MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true}.set(a,"scan")
            MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.set(a,"scanWhiteCorrection")
            MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}.invoke(a);layout(a)
            val control=all(root(a)).filterIsInstance<dev.filmroom.ui.SettledChoiceSelector>().single()
            assertFalse("Unsupported control must be visibly disabled",control.isEnabled)
            assertTrue("It shows effective OFF, not a misleading enabled choice",control.contentDescription.toString().contains("关闭"))
            assertTrue(all(root(a)).filterIsInstance<android.widget.TextView>().any{it.text.toString()==a.getString(R.string.route_correction_preference_retained)})
            assertTrue("Stored user preference was not erased",model(a).state.value.parameters.scanWhiteCorrection)
        }
    }
    @Test fun parameterToolStripDoesNotRecenterWhenItsValueChanges(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a);layout(a,320,640)
            all(root(a)).filterIsInstance<Button>().first{it.text.toString()=="调整"}.performClick();layout(a,320,640)
            val tools=strip(a,"tools:adjust");tools.fullScroll(View.FOCUS_RIGHT);layout(a,320,640)
            // Film exposure now leads the adjustment row. Select the actual last tool
            // instead, retaining the original nonzero-scroll/no-jump regression purpose.
            all(tools).filterIsInstance<Button>().single{it.tag=="saturation"}.performClick();layout(a,320,640)
            val scrolled=strip(a,"tools:adjust");val x=scrolled.scrollX;assertTrue("strip=${scrolled.width}, content=${scrolled.getChildAt(0).width}",x>0)
            val before=model(a).state.value.parameters
            model(a).parameters(before.copy(saturation=1.2f));layout(a,320,640)
            assertEquals(1.2f,model(a).state.value.parameters.saturation,0f)
            assertEquals(before.filmExposure,model(a).state.value.parameters.filmExposure,0f)
            assertEquals("saturation",all(strip(a,"tools:adjust")).filterIsInstance<Button>().single{it.isSelected}.tag)
            assertEquals(x,strip(a,"tools:adjust").scrollX)
        }
    }

}
