package dev.filmroom

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.filmroom.ui.ProcessingAnimationView
import dev.filmroom.engine.ExportDisplayProgress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Host dialog/state checks only; no device rendering or processing success claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ExportProgressDialogHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)
        (0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList())
    private fun update(activity:MainActivity,state:EditorUiState){
        MainActivity::class.java.getDeclaredMethod("updateExportProgress",EditorUiState::class.java).apply{isAccessible=true}.invoke(activity,state)
    }
    @Test fun stageUpdatesReuseDialogAndCompletionDismissesIt(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val activity=controller.get()
            update(activity,EditorUiState(exporting=true,busy="读取照片数据 · 可取消"))
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertEquals(1,descendants(dialog.window!!.decorView).filterIsInstance<ProcessingAnimationView>().size)
            update(activity,EditorUiState(exporting=true,busy="正在编码并保存照片 · 可取消",exportProgress=ExportDisplayProgress(7)))
            assertSame(dialog,ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any{it.text.toString()=="保存照片\n第 7/7 步"})
            update(activity,EditorUiState(exporting=false,busy=null,message="处理未完成"))
            assertFalse(dialog.isShowing)
        }
    }
    @Test fun cancellingKeepsVisibleStatusDisablesRepeatCancelAndRetainsLogs(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            update(controller.get(),EditorUiState(exporting=true,cancelling=true,busy="正在取消并清理临时文件…"))
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            val buttons=descendants(dialog.window!!.decorView).filterIsInstance<Button>()
            assertFalse(buttons.first{it.text.toString()=="正在清理"}.isEnabled)
            assertTrue(buttons.first{it.text.toString()=="诊断日志"}.isEnabled)
            assertTrue(dialog.isShowing)
        }
    }
    @Test fun stoppingActivityDismissesProgressWindow(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            update(controller.get(),EditorUiState(exporting=true,busy="读取照片数据 · 可取消"))
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            controller.pause().stop()
            assertFalse(dialog.isShowing)
        }
    }
    @Test fun technicalBudgetIsCollapsedUntilDetailsRequested(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val note="本次预算：约 12.34 GiB 可用空间；算法暂存 10.00 GiB"
            update(c.get(),EditorUiState(exporting=true,busy="印相柔光",exportSpaceNote=note,exportProgress=ExportDisplayProgress(6,35,100)))
            val labels=descendants(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<TextView>()
            val details=labels.single{it.text.contains(note)}
            assertEquals(View.GONE,details.visibility)
            assertTrue(labels.any{it.text.toString()=="印相和扫描\n第 6/7 步 · 35%"})
            labels.filterIsInstance<Button>().single{it.text.toString()=="详情"}.performClick()
            assertEquals(View.VISIBLE,details.visibility)
            assertTrue(details.text.contains("印相柔光"))
        }
    }

    @Test fun collapsedProgressStaysVisibleAcrossPassChangesAndDetailsToggle(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            update(c.get(),EditorUiState(exporting=true,busy="生成颗粒 · 本遍 100%",exportProgress=ExportDisplayProgress(5,45,100)))
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            val labels=descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
            val main=labels.single{it.text.toString()=="生成颗粒\n第 5/7 步 · 45%"}
            assertTrue(main.isShown)
            val details=labels.filterIsInstance<Button>().single{it.text.toString()=="详情"}
            details.performClick()
            update(c.get(),EditorUiState(exporting=true,busy="形成颗粒云 · 本遍 2%",exportProgress=ExportDisplayProgress(5,46,100)))
            assertEquals("生成颗粒\n第 5/7 步 · 46%",main.text.toString())
            assertEquals(dev.filmroom.ui.ProcessingVisualStage.GRAIN,labels.let{descendants(dialog.window!!.decorView).filterIsInstance<ProcessingAnimationView>().single().stage})
            details.performClick()
            assertTrue(main.isShown)
            assertSame(dialog,ShadowAlertDialog.getLatestAlertDialog())
            val root=dialog.window!!.decorView
            fun height():Int {
                root.measure(View.MeasureSpec.makeMeasureSpec(328,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST))
                root.layout(0,0,328,root.measuredHeight)
                return root.height
            }
            val before=height()
            update(c.get(),EditorUiState(exporting=true,busy="生成颗粒",exportProgress=ExportDisplayProgress(5,100,100)))
            assertEquals(before,height())
        }
    }

    @Test fun renderCompactProgressAtNormalAndLargeText(){
        val target=java.io.File("build/ui-host-renders").apply{mkdirs()}
        for(scale in listOf(1f,2f)){
            org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h780dp-mdpi")
            org.robolectric.RuntimeEnvironment.setFontScale(scale)
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val bitmap=android.graphics.Bitmap.createBitmap(384,512,android.graphics.Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(bitmap)
                val paint=android.graphics.Paint().apply{shader=android.graphics.LinearGradient(0f,0f,384f,512f,intArrayOf(0xff244658.toInt(),0xffc99a61.toInt(),0xff334536.toInt()),null,android.graphics.Shader.TileMode.CLAMP)}
                canvas.drawPaint(paint)
                val preview=System.getenv("FILMROOM_UI_PHOTO")?.let{android.graphics.BitmapFactory.decodeFile(it,android.graphics.BitmapFactory.Options().apply{inSampleSize=8})} ?: bitmap
                for(stage in listOf("读取照片数据","显影扩散 · 本遍74% · 可取消","正在编码并保存照片")){
                    update(c.get(),EditorUiState(exporting=true,busy=stage,exportProgress=ExportDisplayProgress(if(stage.contains("显影"))4 else if(stage.contains("保存"))7 else 1,74,100),rendered=preview,exportSpaceNote="算法暂存 1.56 GiB"))
                    val root=ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
                    root.measure(View.MeasureSpec.makeMeasureSpec(328,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST))
                    root.layout(0,0,328,root.measuredHeight)
                    assertTrue("Compact dialog became a wall of text",root.height<500)
                    val output=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(output))
                    java.io.File(target,"progress-font${scale.toInt()}-${dev.filmroom.ui.ProcessingVisualStage.fromMessage(stage)}.png").outputStream().use{output.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }
            }
        }
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
    }

}
