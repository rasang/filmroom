package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import dev.filmroom.ui.ExportPreviewView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactExportThumbnailHostTest {
 private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
 private fun job(state:ExportJobState)=ExportJob(UUID.randomUUID().toString(),1,EditorState(),4000,3000,ExportFormat.JPEG,95,state,ExportDisplayProgress(8,1,1),mediaUri=if(state==ExportJobState.COMPLETED)"content://media/external/images/media/42" else null)
 private fun render(a:ExportQueueActivity,j:ExportJob){
  // These are terminal-layout fixtures, not a live three-image presentation.
  a.previewSequenceEnabled=false
  ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(a,listOf(j))}
 private fun layout(a:ExportQueueActivity,w:Int,h:Int):View {
  val v=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
  v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);return v
 }
 private fun setup(w:Int,h:Int,font:Float){resetAndroidViewModelFactoryForHostTest();RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if(w>h)"land" else "port"}-mdpi");RuntimeEnvironment.setFontScale(font);dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
 private fun capture(root:View,name:String){
  val photo=javaClass.getResourceAsStream("/queue/public-source.jpg").use{BitmapFactory.decodeStream(it)}
  views(root).filterIsInstance<ExportPreviewView>().forEach{it.show(photo,"public-layout-fixture");(it.parent as ViewGroup).let{p->(0 until p.childCount).map(p::getChildAt).filterIsInstance<ImageView>().forEach{v->v.visibility=View.GONE}}}
  val out=File("/workspace/shared/compact-thumbnail18/host").apply{mkdirs()}
  val b=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(b));File(out,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
 }
 @Test fun capturePortraitCompleted(){setup(360,780,1f);try{Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->render(c.get(),job(ExportJobState.COMPLETED).copy(metadataWarning="fixture",message="已保存\n部分照片元信息未能完整保留，详情见原始诊断。"));capture(layout(c.get(),360,780),"portrait")}}finally{RuntimeEnvironment.setFontScale(1f)}}
 @Test fun compactStatesHaveInsetAndRetainTextWidthAcrossWindowsAndFonts(){
  try{for((w,h) in listOf(320 to 720,360 to 780,740 to 360)){for(font in listOf(1f,1.8f)){setup(w,h,font)
   for(state in listOf(ExportJobState.COMPLETED,ExportJobState.PENDING,ExportJobState.RETRY,ExportJobState.CANCELLED)){
    Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
     val j=job(state);render(c.get(),j);val root=layout(c.get(),w,h)
     val card=views(root).single{it.tag=="export-job:${j.id}"} as ViewGroup
     val image=views(card).filterIsInstance<ExportPreviewView>().single();val frame=image.parent as FrameLayout;val lp=frame.layoutParams as LinearLayout.LayoutParams
     assertEquals(12,lp.marginStart);assertEquals(12,lp.topMargin);assertTrue(frame.clipToOutline)
     val horizontal=w>=600 || font<1.5f
     assertEquals(if(horizontal)0 else 12,lp.marginEnd)
     if(horizontal){assertEquals(92,frame.width);assertEquals(100,frame.height)}
     assertTrue("Bottom clearance",card.height-frame.bottom>=12)
     val info=(frame.parent as ViewGroup).getChildAt(1);assertTrue(info.width>=w-32-104 || !horizontal)
     views(info).filterIsInstance<Button>().filter{it.visibility==View.VISIBLE}.forEach{assertTrue(it.width>0);assertTrue(it.left>=0);assertTrue(it.right<= (it.parent as View).width)}
     if(state==ExportJobState.COMPLETED)capture(root,"${w}x$h-font$font")
    }
   }
  }}}finally{RuntimeEnvironment.setFontScale(1f);RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")}
 }
 @Test fun savedMetadataWarningIsDetailsOnlyWhileFailuresRemainVisible(){
  val raw="Metadata fixture: GPSVersionID omitted; original capture fields retained"
  try{for(font in listOf(1f,1.8f)){setup(320,720,font)
   Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
    val a=c.get();val j=job(ExportJobState.COMPLETED).copy(metadataWarning=raw,message="Saved fixture")
    render(a,j);val root=layout(a,320,720)
    val card=views(root).single{it.tag=="export-job:${j.id}"} as ViewGroup
    fun texts()=views(card).filterIsInstance<TextView>()
    val generic=dev.filmroom.i18n.L10n.text(R.string.queue_metadata_warning)
    fun warning()=texts().single{it.text.toString().contains(generic)}
    val diagnostic=texts().single{it.text.toString().contains(raw)}
    assertEquals(View.GONE,warning().visibility);assertEquals(View.GONE,diagnostic.visibility)
    assertTrue(texts().any{it.text.toString()==dev.filmroom.i18n.L10n.text(R.string.text_1bd91a7d0c53) && it.visibility==View.VISIBLE})
    val frame=views(card).filterIsInstance<ExportPreviewView>().single().parent as View
    assertTrue(card.height-frame.bottom>=12)
    render(a,j.copy(completionViewed=true));layout(a,320,720);assertEquals(View.GONE,warning().visibility)
    views(card).filterIsInstance<Button>().single{it.text=="详情"}.performClick();layout(a,320,720)
    assertEquals(View.VISIBLE,diagnostic.visibility);assertTrue(diagnostic.text.toString().endsWith(raw));assertEquals(View.VISIBLE,warning().visibility)
    views(card).filterIsInstance<Button>().single{it.text=="收起详情"}.performClick();layout(a,320,720);assertEquals(View.GONE,warning().visibility)
    render(a,j.copy(state=ExportJobState.FAILED,message="codec failure fixture"));layout(a,320,720)
    assertEquals(View.VISIBLE,warning().visibility)
    assertTrue(warning().text.contains(dev.filmroom.i18n.L10n.text(R.string.text_6e4445df6957)))
    assertEquals(raw,j.metadataWarning) // Presentation never mutates retained metadata diagnostics.
   }
  }}finally{RuntimeEnvironment.setFontScale(1f)}
 }
 @Test fun runningAndExpandedLayoutsResetCompactInsets(){setup(360,780,1f)
  Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
   val j=job(ExportJobState.COMPLETED);render(c.get(),j);layout(c.get(),360,780)
   val frame=views(c.get().window.decorView).filterIsInstance<ExportPreviewView>().single().parent as FrameLayout
   val before=(frame.layoutParams as LinearLayout.LayoutParams);assertEquals(12,before.marginStart)
   views(c.get().window.decorView).filterIsInstance<Button>().single{it.text=="详情"}.performClick();layout(c.get(),360,780)
   assertEquals(0,(frame.layoutParams as LinearLayout.LayoutParams).marginStart)
   views(c.get().window.decorView).filterIsInstance<Button>().single{it.text=="收起详情"}.performClick();layout(c.get(),360,780)
   assertEquals(12,(frame.layoutParams as LinearLayout.LayoutParams).marginStart)
   render(c.get(),j.copy(state=ExportJobState.RUNNING,progress=ExportDisplayProgress(3,1,10)));layout(c.get(),360,780)
   val lp=frame.layoutParams as LinearLayout.LayoutParams;assertEquals(0,lp.marginStart);assertEquals(0,lp.topMargin);assertEquals(176,frame.height)
   render(c.get(),j);layout(c.get(),360,780);assertEquals(12,(frame.layoutParams as LinearLayout.LayoutParams).marginStart)
   val card=views(c.get().window.decorView).single{it.tag=="export-job:${j.id}"} as ViewGroup
   card.layoutDirection=View.LAYOUT_DIRECTION_RTL;layout(c.get(),360,780)
   assertEquals("Relative inset follows RTL leading edge",12,card.width-frame.right)
  }
 }
}
