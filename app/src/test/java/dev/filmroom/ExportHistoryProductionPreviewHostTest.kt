package dev.filmroom
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
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
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w360dp-h780dp-480dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportHistoryProductionPreviewHostTest {
 private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
 private fun layout(v:View){v.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2340,View.MeasureSpec.EXACTLY));v.layout(0,0,1080,2340)}
 private fun capture(v:View,file:File,dialog:android.app.AlertDialog?=null){layout(v);val b=Bitmap.createBitmap(1080,2340,Bitmap.Config.ARGB_8888);val c=Canvas(b);v.draw(c)
 if(dialog!=null){shadowOf(android.os.Looper.getMainLooper()).idle();all(dialog.window!!.decorView).forEach{it.setScrollIndicators(0)};c.drawColor(0x99000000.toInt());val d=dialog.window!!.decorView;d.measure(View.MeasureSpec.makeMeasureSpec(936,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2100,View.MeasureSpec.AT_MOST));d.layout(0,0,936,d.measuredHeight);c.save();c.translate(72f,(2340-d.height)/2f);d.draw(c);c.restore()}
 file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 @Test fun renderRealUiWithoutConfirmingDeletion(){
  val outputPath=System.getenv("HISTORY_OUT");val photoPath=System.getenv("HISTORY_PREVIEW_PHOTO")
  org.junit.Assume.assumeTrue("Opt-in UI capture requires HISTORY_OUT and HISTORY_PREVIEW_PHOTO",!outputPath.isNullOrBlank() && !photoPath.isNullOrBlank())
  val photoFile=File(checkNotNull(photoPath));require(photoFile.isFile){"Missing explicit preview fixture"}
  dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),"zh")
  RuntimeEnvironment.getApplication().getSharedPreferences("export-history-preferences",0).edit().clear().commit()
  RuntimeEnvironment.setQualifiers("w360dp-h780dp-480dpi");RuntimeEnvironment.setFontScale(1f)
  val target=File(checkNotNull(outputPath)).apply{mkdirs()}
  Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{ctl->
   val a=ctl.get();val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
   shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
   val jobs=listOf(ExportJobState.FAILED,ExportJobState.COMPLETED).mapIndexed{i,state->ExportJob(UUID.randomUUID().toString(),i.toLong(),EditorState(),4000,3000,ExportFormat.JPEG,95,state,ExportDisplayProgress(if(state==ExportJobState.COMPLETED)8 else 5,100,100),mediaUri=if(state==ExportJobState.COMPLETED)"content://review-only/photo" else null,message=if(state==ExportJobState.FAILED)"Review fixture failure" else null)}
   val render=ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}
   var queue:ExportQueue?=null
   for(attempt in 0 until 300){shadowOf(android.os.Looper.getMainLooper()).idle();queue=ExportQueueActivity::class.java.getDeclaredField("queue").apply{isAccessible=true}.get(a) as? ExportQueue;if(queue!=null)break;Thread.sleep(5)}
   checkNotNull(queue);jobs.forEach(queue!!.store::update)
   shadowOf(android.os.Looper.getMainLooper()).idle();render.invoke(a,jobs);layout(root)
   // Public CC0 photo placed only in UI fixture; no jobs/files are deleted.
   val photo=android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath,android.graphics.BitmapFactory.Options().apply{inSampleSize=16})
   fun photo(){all(root).filterIsInstance<dev.filmroom.ui.ExportPreviewView>().forEach{view->val parent=view.parent as android.widget.FrameLayout;parent.addView(android.widget.ImageView(a).apply{setImageBitmap(photo);scaleType=android.widget.ImageView.ScaleType.CENTER_CROP},android.widget.FrameLayout.LayoutParams(-1,-1))}}
   photo();capture(root,File(target,"completed-failed.png"))
   val clear=all(root).filterIsInstance<Button>().firstOrNull{it.tag=="history-clear"}
   if(clear!=null){assertTrue(clear.isEnabled);clear.performClick();val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();capture(root,File(target,"clear-confirmation.png"),dialog);all(dialog.window!!.decorView).filterIsInstance<android.widget.CheckBox>().single().apply{isChecked=true;jumpDrawablesToCurrentState()};capture(root,File(target,"clear-remember.png"),dialog);dialog.dismiss()
    val deletes=all(root).filterIsInstance<Button>().filter{it.tag?.toString()?.startsWith("history-delete:")==true};assertEquals(2,deletes.size);assertTrue(deletes.all{it.isEnabled})
    val failedDelete=deletes.single{it.tag=="history-delete:${jobs[0].id}"};failedDelete.performClick();val d=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();capture(root,File(target,"failed-confirmation.png"),d);d.dismiss()
   }
   val active=jobs[0].copy(state=ExportJobState.RUNNING,message=null,progress=ExportDisplayProgress(5,65,100));render.invoke(a,listOf(active));photo();capture(root,File(target,"active-protected.png"))
   if(clear!=null){assertFalse(clear.isEnabled);assertFalse(all(root).filterIsInstance<Button>().single{it.tag?.toString()?.startsWith("history-delete:")==true}.isEnabled)}
  }
 }
}
