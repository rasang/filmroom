package dev.filmroom

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.i18n.L10n
import dev.filmroom.ui.EditorLayoutPolicy
import dev.filmroom.ui.EditorSystemBars
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Host window adaptation only: not physical hinge/posture or phone display verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,31,35],qualifiers="w600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoldableWindowLayoutHostTest {
    @Before fun setup(){
        resetAndroidViewModelFactoryForHostTest();val app=RuntimeEnvironment.getApplication();L10n.choose(app,"zh")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
    }
    @org.junit.After fun cleanup(){android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,1f);RuntimeEnvironment.setFontScale(1f)}
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))}
    private fun root(a:MainActivity)=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun layout(a:MainActivity,w:Int,h:Int){repeat(2){root(a).measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root(a).layout(0,0,w,h);idle()}}
    private fun wide(a:MainActivity)=all(root(a)).any{it.tag=="landscape-parameters-scroll"}
    @Test fun boundaryAndNarrowMultiwindowPolicy(){
        assertFalse(EditorLayoutPolicy.usesTwoPanes(599,800));assertTrue(EditorLayoutPolicy.usesTwoPanes(600,800));assertTrue(EditorLayoutPolicy.usesTwoPanes(601,800))
        assertTrue(EditorLayoutPolicy.usesTwoPanes(840,900));assertFalse(EditorLayoutPolicy.usesTwoPanes(393,852))
        assertTrue(EditorLayoutPolicy.usesTwoPanes(480,320));assertFalse(EditorLayoutPolicy.usesTwoPanes(480,320,true));assertTrue(EditorLayoutPolicy.usesTwoPanes(640,800,true))
    }
    @Test fun unfoldedPortraitUsesRealSectionsAndKeepsStatusBar(){
        for((w,h) in listOf(599 to 800,600 to 800,601 to 800,840 to 900,393 to 852)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi")
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val a=c.get();layout(a,w,h);assertEquals(w>=600,wide(a));assertFalse(EditorSystemBars.hidesStatusBar(a))
                if(w>=600){
                    val section=all(root(a)).single{it.tag=="parameter-section:filmStock"}
                    val photo=MainActivity::class.java.getDeclaredField("photo").apply{isAccessible=true}.get(a) as View
                    val photoXY=IntArray(2);val sectionXY=IntArray(2);photo.getLocationInWindow(photoXY);section.getLocationInWindow(sectionXY)
                    assertTrue("Preview retains usable width",photo.width>=w/2)
                    assertTrue("Controls are beside, not below preview",sectionXY[0]>=photoXY[0]+photo.width)
                    val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root(a).draw(Canvas(image))
                    File("build/fold-window-renders").apply{mkdirs()}.resolve("api${android.os.Build.VERSION.SDK_INT}-${w}x$h.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                }
            }
        }
    }
    @Test fun actualMultiwindowUsesCurrentWindowWidthNotPhysicalDevice(){
        for((w,h) in listOf(640 to 800,480 to 320,393 to 852)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi")
            Robolectric.buildActivity(MainActivity::class.java).use{c->
                Shadows.shadowOf(c.get()).setInMultiWindowMode(true);c.setup();layout(c.get(),w,h)
                assertEquals(w>=600,wide(c.get()));assertFalse(EditorSystemBars.hidesStatusBar(c.get()))
            }
        }
    }
    @Test fun foldUnfoldRetainsPhotoParametersAndCompositionDraft(){
        RuntimeEnvironment.setQualifiers("w600dp-h800dp-mdpi")
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();val vm=ViewModelProvider(a)[EditorViewModel::class.java]
            val deadline=System.nanoTime()+10_000_000_000L;while(!vm.state.value.startupReady && System.nanoTime()<deadline){idle();Thread.sleep(5)};assertTrue(vm.state.value.startupReady)
            @Suppress("UNCHECKED_CAST") val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
            val photo=dev.filmroom.io.PhotoSource(File(a.cacheDir,"fold-state-fixture"),"fixture","image/png",64,48,0)
            val parameters=vm.state.value.parameters.copy(brightness=1.5f,crop=dev.filmroom.model.CropSettings(mode=dev.filmroom.model.CropMode.SQUARE))
            state.value=EditorUiState(photo=photo,parameters=parameters,compositionEditing=true,canUndo=true);idle()
            for((w,h) in listOf(393 to 852,840 to 900,600 to 800)){
                c.configurationChange(Configuration(c.get().resources.configuration).apply{screenWidthDp=w;screenHeightDp=h;orientation=Configuration.ORIENTATION_PORTRAIT}).visible();idle();layout(c.get(),w,h)
                assertSame(vm,ViewModelProvider(c.get())[EditorViewModel::class.java]);assertSame(photo,vm.state.value.photo);assertEquals(parameters,vm.state.value.parameters)
                assertTrue(vm.state.value.compositionEditing);assertTrue(vm.state.value.canUndo);assertEquals(w>=600,wide(c.get()));assertFalse(EditorSystemBars.hidesStatusBar(c.get()))
            }
            state.value=state.value.copy(compositionEditing=false);idle()
            val categories=MainActivity::class.java.getDeclaredField("categoryBar").apply{isAccessible=true}.get(c.get()) as View
            all(categories).single{it.tag=="grain"}.performClick();layout(c.get(),600,800)
            val scroll=all(root(c.get())).single{it.tag=="landscape-parameters-scroll"} as ScrollView
            scroll.scrollTo(0,100);val savedY=scroll.scrollY;assertTrue("Actual scroll establishes restoration precondition",savedY>0)
            for((w,h) in listOf(393 to 852,600 to 800)){
                c.configurationChange(Configuration(c.get().resources.configuration).apply{screenWidthDp=w;screenHeightDp=h;orientation=Configuration.ORIENTATION_PORTRAIT}).visible();idle();layout(c.get(),w,h)
            }
            assertEquals(savedY,(all(root(c.get())).single{it.tag=="landscape-parameters-scroll"} as ScrollView).scrollY)
            assertEquals(parameters,vm.state.value.parameters);assertSame(photo,vm.state.value.photo)
        }
    }
}
