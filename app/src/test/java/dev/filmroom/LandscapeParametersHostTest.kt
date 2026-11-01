package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.i18n.L10n
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.CropMode
import dev.filmroom.model.EditorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w780dp-h360dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapeParametersHostTest {
    @org.junit.Before fun setup(){resetAndroidViewModelFactoryForHostTest();val app=RuntimeEnvironment.getApplication();L10n.choose(app,"zh");app.getSharedPreferences("local-session",0).edit().clear().commit()}
    private fun field(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun vm(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun root(a:MainActivity)=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))}
    private fun await(done:()->Boolean){val deadline=System.nanoTime()+8_000_000_000L;while(!done()&&System.nanoTime()<deadline){idle();Thread.sleep(5)};assertTrue("Bounded editor state wait",done())}
    private fun layout(a:MainActivity,w:Int=780,h:Int=360){val root=root(a);repeat(2){root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);idle()}}
    private fun section(a:MainActivity,id:String)=all(root(a)).single{it.tag=="parameter-section:$id"} as LinearLayout
    private fun scroll(a:MainActivity)=all(root(a)).single{it.tag=="landscape-parameters-scroll"} as ScrollView
    private fun category(a:MainActivity,id:String){all(field(a,"categoryBar") as View).single{it.tag==id}.performClick();layout(a)}
    @Suppress("UNCHECKED_CAST") private fun fixture(a:MainActivity,render:Boolean=false){
        val vm=vm(a);await{(EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}.get(vm) as? Job)?.isCompleted==true}
        vm.localDetailEnabled=false;vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        vm.renderPreview={source,_,_,_->source.copy(Bitmap.Config.ARGB_8888,false)};vm.detailPreviewEdge={96}
        val source=Bitmap.createBitmap(96,72,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
        (EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>).value=EditorUiState(photo=PhotoSource(File(a.cacheDir,"landscape-controls-fixture"),"fixture.jpg","image/jpeg",640,480,0),original=source,rendered=if(render)null else source,parameters=EditorState())
        if(render){vm.parameters(EditorState());await{vm.state.value.canExport}}
        layout(a)
    }
    @Test(timeout=60000) fun sectionsReplaceOnlyLandscapeToolTabsAndKeepCategoryScrollAndStableViews(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a)
            assertNotNull(section(a,"filmStock"));assertNotNull(section(a,"composition"));assertNotNull(section(a,"filmFormatMm"))
            assertFalse(all(root(a)).any{it.tag=="strip:tools:film"})
            val categoryView=field(a,"categoryBar") as View;val vertical=scroll(a)
            assertTrue((categoryView.parent as View).bottom<=vertical.top)
            val stock=section(a,"filmStock").getChildAt(1)
            vm(a).parameters(vm(a).state.value.parameters.copy(brightness=.2f));layout(a)
            assertSame("Unrelated values never rebuild film cards",stock,section(a,"filmStock").getChildAt(1))
            vertical.scrollTo(0,83);val filmY=vertical.scrollY;assertTrue(filmY>0)
            category(a,"adjust");assertTrue(all(scroll(a)).filterIsInstance<SeekBar>().size>3)
            scroll(a).scrollTo(0,170);val adjustY=scroll(a).scrollY;assertTrue(adjustY>0)
            category(a,"film");assertEquals(filmY,scroll(a).scrollY)
            category(a,"adjust");assertEquals(adjustY,scroll(a).scrollY)
            val saved=Bundle();c.saveInstanceState(saved)
            assertEquals(adjustY,saved.getBundle("landscapePositions")!!.getInt("adjust"))
            assertEquals(filmY,saved.getBundle("landscapePositions")!!.getInt("film"))
        }
    }
    @Test(timeout=60000) fun sliderGestureRetainsItsViewAndUnrelatedSectionsAndDoesNotJumpScroll(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a);category(a,"adjust")
            val target=section(a,"filmExposure");val slider=all(target).filterIsInstance<SeekBar>().single()
            val other=section(a,"brightness").getChildAt(1);val viewport=scroll(a);val y=viewport.scrollY
            val down=android.os.SystemClock.uptimeMillis()
            fun event(action:Int,x:Float,time:Long){android.view.MotionEvent.obtain(down,time,action,x,slider.height/2f,0).also{slider.dispatchTouchEvent(it);it.recycle()}}
            event(android.view.MotionEvent.ACTION_DOWN,slider.width*.5f,down)
            event(android.view.MotionEvent.ACTION_MOVE,slider.width*.75f,down+40);idle();layout(a)
            assertSame(slider,all(section(a,"filmExposure")).filterIsInstance<SeekBar>().single())
            assertSame(other,section(a,"brightness").getChildAt(1));assertEquals(y,viewport.scrollY)
            event(android.view.MotionEvent.ACTION_UP,slider.width*.75f,down+80);layout(a)
            assertTrue(vm(a).state.value.parameters.filmExposure>0f)
            assertSame(other,section(a,"brightness").getChildAt(1));assertEquals(y,viewport.scrollY)
        }
    }
    @Test(timeout=60000) fun accessibilitySliderUpdatesDependentSectionsWithoutLosingFocusedControl(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a);category(a,"grain")
            val model=vm(a);model.parameters(model.state.value.parameters.copy(grain=1f));layout(a)
            val slider=all(section(a,"grain")).filterIsInstance<SeekBar>().single()
            assertTrue(all(section(a,"grainSize")).filterIsInstance<SeekBar>().single().isEnabled)
            slider.isFocusableInTouchMode=true // Establish focus in the host touch-mode fixture.
            slider.requestFocus();assertTrue("Keyboard target accepts focus",slider.hasFocus())
            assertTrue(slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply{putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,0f)}))
            layout(a)
            assertSame(slider,all(section(a,"grain")).filterIsInstance<SeekBar>().single())
            assertTrue("Accessibility updates retain focus",slider.hasFocus())
            assertEquals(0f,model.state.value.parameters.grain,0f)
            assertFalse("Dependent size is unavailable when grain is off",all(section(a,"grainSize")).filterIsInstance<SeekBar>().single().isEnabled)
        }
    }
    @Test(timeout=60000) fun compositionCommitsOnCategoryChangeAndBackCancelsWithoutLosingOtherSections(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();fixture(a,true);val model=vm(a);val before=model.state.value.parameters
            (field(a,"compositionButton") as Button).performClick();await{model.state.value.compositionEditing};layout(a)
            assertNotNull(section(a,"filmStock"));assertNotNull(section(a,"filmFormatMm"))
            all(section(a,"composition")).single{it.tag=="crop-mode:SQUARE"}.performClick();layout(a)
            category(a,"adjust");await{!model.state.value.compositionEditing && model.state.value.canExport}
            assertEquals(CropMode.SQUARE,model.state.value.parameters.crop.mode)
            model.undo();await{model.state.value.parameters.crop==before.crop};layout(a)
            category(a,"film");await{model.state.value.canExport}
            (field(a,"compositionButton") as Button).performClick();await{model.state.value.compositionEditing};layout(a)
            all(section(a,"composition")).single{it.tag=="crop-mode:RATIO_16_9"}.performClick();layout(a)
            a.onBackPressedDispatcher.onBackPressed();await{!model.state.value.compositionEditing};layout(a)
            assertEquals(before.crop,model.state.value.parameters.crop)
            assertNotNull(section(a,"composition"));assertNotNull(section(a,"filmStock"))
        }
    }
    @Test(timeout=120000) fun fourLanguagesNormalAndLargeTextHaveReadableScrollableSectionsAndScreens(){
        val app=RuntimeEnvironment.getApplication()
        try{for(language in L10n.supported)for((w,h) in listOf(780 to 360,640 to 320))for(font in listOf(1f,2f)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(font);L10n.choose(app,language)
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val a=c.get();fixture(a,true);layout(a,w,h)
                val viewport=scroll(a);assertTrue(viewport.height>0);assertTrue(viewport.getChildAt(0).height>viewport.height)
                assertNull("No large-font modal replaces landscape sections",field(a,"expandedTools"))
                for(id in listOf("filmStock","composition","filmFormatMm"))assertTrue(section(a,id).width<=viewport.width)
                val out=File("build/landscape-parameter-renders").apply{mkdirs()}
                fun capture(suffix:String){val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root(a).draw(Canvas(image));File(out,"$language-${w}x$h-font$font-$suffix.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
                capture("film-top");viewport.scrollTo(0,viewport.getChildAt(0).height);layout(a,w,h);capture("film-bottom")
                val last=(viewport.getChildAt(0) as ViewGroup).let{it.getChildAt(it.childCount-1)};val bounds=android.graphics.Rect();assertTrue("Last section reachable $language $w font$font",last.getGlobalVisibleRect(bounds));assertTrue(bounds.height()>0)
                category(a,"adjust");layout(a,w,h);capture("adjust")
                val exposure=section(a,"filmExposure")
                if(font>1.35f){
                    val heading=exposure.getChildAt(0) as LinearLayout
                    assertEquals("Large-text headings separate title from value",LinearLayout.VERTICAL,heading.orientation)
                    val title=heading.getChildAt(0) as TextView
                    assertTrue("Title keeps the whole section width",title.width>=exposure.width)
                    assertEquals("Film exposure remains readable, not broken into fragments",1,title.lineCount)
                }
                val categoryParent=(field(a,"categoryBar") as View).parent as View
                assertTrue(categoryParent.bottom<=scroll(a).top)
            }
        }}finally{RuntimeEnvironment.setFontScale(1f);L10n.choose(app,"zh")}
    }
}
