package dev.filmroom

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.filmroom.i18n.L10n
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Host Android/Skia renders only: never phone screenshots or touch-performance evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocaleLayoutHostTest {
    @org.junit.After fun restoreChineseFixture(){L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
    @org.junit.Before fun isolateFactory(){resetAndroidViewModelFactoryForHostTest()}
    private fun await(condition:()->Boolean){
        val deadline=System.nanoTime()+10_000_000_000L
        do{shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));if(condition())return;Thread.sleep(5)}while(System.nanoTime()<deadline)
        fail("Locale layout fixture did not settle")
    }
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun capture(a:Activity,tag:String,name:String,w:Int,h:Int){
        val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
        if(name=="composition")all(root).filterIsInstance<TextView>().filter{it.tag?.toString()?.startsWith("crop-")==true}.forEach{v->
            println("LAYOUT $tag $w tag=${v.tag} text=${v.text} alpha=${v.alpha} color=${v.currentTextColor} bounds=${v.width}x${v.height} layout=${v.layout?.width} lines=${v.lineCount} scroll=${v.scrollX},${v.scrollY} paint=${v.paint.alpha} size=${v.textSize}")
            if(v is android.widget.Button)assertTrue("Empty composition action ${v.tag}",v.text.isNotBlank())
        }
        if(name=="main" || name=="composition")all(root).filterIsInstance<android.widget.Button>().filter{it.text.isNotBlank() && it.width>0}.forEach{v->
            assertTrue("Unbounded text layout $tag $name ${v.text}: ${v.layout?.width}",(v.layout?.width ?: 0)<100000)
            assertTrue("Offscreen text scroll $tag $name ${v.text}: ${v.scrollX}",kotlin.math.abs(v.scrollX)<=v.width)
            val available=v.width-v.compoundPaddingLeft-v.compoundPaddingRight
            for(line in 0 until v.lineCount)assertTrue("Clipped label $tag $name ${v.text}: ${v.layout.getLineWidth(line)} > $available",v.layout.getLineWidth(line)<=available+1f)
        }
        val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image))
        val target=File("build/locale-host-renders").apply{mkdirs()}
        File(target,"$tag-$name-${w}x$h.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        assertTrue(all(root).filterIsInstance<TextView>().any{it.text.isNotBlank()})
    }
    @Test(timeout=120000) fun fourLocalesHaveActualMainSettingsAndQueuePortraitLandscapeScreens(){
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
        val app=RuntimeEnvironment.getApplication()
        for(tag in L10n.supported)for((w,h) in listOf(320 to 640,740 to 320)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi")
            L10n.choose(app,tag)
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val a=c.get()
                val model=androidx.lifecycle.ViewModelProvider(a)[EditorViewModel::class.java]
                val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
                await{(operation.get(model) as? kotlinx.coroutines.Job)?.isCompleted==true}
                model.localDetailEnabled=false
                model.borderMemorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
                model.renderPreview={b,_,_,_->b.copy(Bitmap.Config.ARGB_8888,false)};model.detailPreviewEdge={96}
                dev.filmroom.border.BorderCatalog.initialize(app.assets){model.borderMemorySnapshot()}
                val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(84,106,115))}
                val p=dev.filmroom.model.EditorState(filmBorder=true)
                val state=EditorUiState(photo=dev.filmroom.io.PhotoSource(File(app.cacheDir,"locale-layout.png"),"fixture","image/png",96,64,1),original=bitmap)
                @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
                flow.value=state
                model.parameters(p);await{flow.value.canExport}
                MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.set(a,"filmStock")
                MainActivity::class.java.getDeclaredMethod("bind",EditorUiState::class.java).apply{isAccessible=true}.invoke(a,flow.value)
                MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}.invoke(a)
                capture(a,tag,"main",w,h)
                val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val composition=all(root).filterIsInstance<android.widget.Button>().firstOrNull{it.tag=="crop-adjust" || it.tag=="composition"}
                assertNotNull("Composition tool missing in $tag",composition)
                assertTrue("Composition tool disabled in $tag: ${flow.value.message}",composition!!.isEnabled)
                composition.performClick()
                assertTrue(model.state.value.compositionEditing)
                MainActivity::class.java.getDeclaredMethod("bind",EditorUiState::class.java).apply{isAccessible=true}.invoke(a,model.state.value)
                capture(a,tag,"composition",w,h)
            }
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
                capture(c.get(),tag,"settings",w,h)
                assertNotNull(c.get().findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("language-setting"))
            }
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
                val jobs=listOf(
                    dev.filmroom.export.ExportJob(dev.filmroom.export.ExportJobStore.newId(),3,dev.filmroom.model.EditorState(),96,64,dev.filmroom.io.ExportFormat.PNG,95,state=dev.filmroom.export.ExportJobState.FAILED,message="Original diagnostic: codec 913"),
                    dev.filmroom.export.ExportJob(dev.filmroom.export.ExportJobStore.newId(),2,dev.filmroom.model.EditorState(),96,64,dev.filmroom.io.ExportFormat.PNG,95,state=dev.filmroom.export.ExportJobState.PAUSED),
                    dev.filmroom.export.ExportJob(dev.filmroom.export.ExportJobStore.newId(),1,dev.filmroom.model.EditorState(),96,64,dev.filmroom.io.ExportFormat.PNG,95,state=dev.filmroom.export.ExportJobState.COMPLETED)
                )
                ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(c.get(),jobs)
                capture(c.get(),tag,"queue",w,h)
            }
        }
    }
}
