package dev.filmroom

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ExportQueueActivityHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun render(a:ExportQueueActivity,jobs:List<ExportJob>){ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(a,jobs)}
    private fun job(state:ExportJobState)=ExportJob(UUID.randomUUID().toString(),0,EditorState(),4000,3000,ExportFormat.JPEG,95,state,ExportDisplayProgress(5,65,100))
    private fun cards(a:ExportQueueActivity)=views(a.window.decorView).filter{it.tag?.toString()?.startsWith("export-job:")==true}
    @Test fun newestAddedFirstWithStableTiesAndNoMovementOnRetryProgressOrRead(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            val jobs=listOf(job(ExportJobState.FAILED).copy(createdAt=1),job(ExportJobState.RUNNING).copy(createdAt=2),job(ExportJobState.COMPLETED).copy(createdAt=2))
            render(c.get(),jobs)
            val expected=listOf(jobs[1],jobs[2],jobs[0]).map{"export-job:${it.id}"}
            val original=cards(c.get())
            assertEquals(expected,original.map{it.tag})
            val updated=jobs.mapIndexed{index,j->when(index){0->j.copy(state=ExportJobState.RETRY);1->j.copy(progress=ExportDisplayProgress(5,90,100));else->j.copy(completionViewed=true)}}
            render(c.get(),updated)
            assertEquals(expected,cards(c.get()).map{it.tag})
            original.zip(cards(c.get())).forEach{(before,after)->assertSame(before,after)}
            render(c.get(),updated.map{it.copy(completionViewed=true)})
            assertEquals(expected,cards(c.get()).map{it.tag})
            assertEquals(listOf(1L,2L,2L),jobs.map{it.createdAt}) // Presentation never mutates FIFO input.
        }
    }
    @Test fun addingNewestAtTopKeepsScrolledVisualAnchorAndExistingCards(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            val jobs=(0..9).map{job(ExportJobState.PAUSED).copy(createdAt=it.toLong())}
            val a=c.get();render(a,jobs)
            val root=a.findViewById<ViewGroup>(android.R.id.content)
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)}
            layout();val scroll=views(root).filterIsInstance<android.widget.ScrollView>().single()
            scroll.scrollTo(0,1200)
            val anchor=cards(a).first{it.bottom>scroll.scrollY};val offset=scroll.scrollY-anchor.top
            val newest=job(ExportJobState.PENDING).copy(createdAt=10)
            render(a,jobs+newest);layout()
            assertEquals("export-job:${newest.id}",cards(a).first().tag)
            assertSame(anchor,cards(a).first{it.bottom>scroll.scrollY})
            assertEquals(offset,scroll.scrollY-anchor.top)
            // At the top, newly added work should be visible without scrolling.
            scroll.scrollTo(0,0)
            val next=job(ExportJobState.PENDING).copy(createdAt=11)
            render(a,jobs+newest+next);layout()
            assertEquals(0,scroll.scrollY)
            assertEquals("export-job:${next.id}",cards(a).first().tag)
        }
    }
    @Test fun persistentEntryOpensQueueDirectlyBesideExport(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val controls=views(c.get().window.decorView)
            val entry=controls.filterIsInstance<dev.filmroom.ui.ExportQueueEntry>().single()
            val export=controls.filterIsInstance<Button>().single{it.contentDescription=="导出"}
            assertTrue(entry.isShown)
            assertSame(export.parent,entry.parent)
            entry.performClick()
            assertEquals(ExportQueueActivity::class.java.name,org.robolectric.Shadows.shadowOf(c.get()).nextStartedActivity.component!!.className)
        }
    }
    @Test fun compactProgressSurvivesDetailToggleAndRowsAreReused(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            val a=c.get();a.previewSequenceEnabled=false;val job=job(ExportJobState.RUNNING);render(a,listOf(job))
            val before=views(a.window.decorView).filterIsInstance<TextView>().single{it.text.toString()=="生成颗粒\n第 5/7 步 · 65%"}
            views(a.window.decorView).filterIsInstance<Button>().single{it.text=="详情"}.performClick()
            assertTrue(before.isShown)
            views(a.window.decorView).filterIsInstance<Button>().single{it.text=="收起详情"}.performClick()
            render(a,listOf(job.copy(progress=ExportDisplayProgress(5,70,100))))
            assertSame(before,views(a.window.decorView).filterIsInstance<TextView>().single{it.text.toString()=="生成颗粒\n第 5/7 步 · 70%"})
        }
    }
    @Test fun scrolledQueueProgressKeepsPositionAndSameJobCard(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            val jobs=(0..9).map{job(ExportJobState.RUNNING)};render(c.get(),jobs)
            val root=c.get().findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)
            val scroll=views(root).filterIsInstance<android.widget.ScrollView>().single()
            scroll.scrollTo(0,1000)
            val before=scroll.scrollY;assertTrue(before>0)
            val target=views(root).single{it.tag=="export-job:${jobs[3].id}"}
            render(c.get(),jobs.map{it.copy(progress=ExportDisplayProgress(5,70,100))})
            assertEquals(before,scroll.scrollY)
            assertSame(target,views(root).single{it.tag=="export-job:${jobs[3].id}"})
        }
    }
    @Test fun rotationRestoresVisibleJobAnchorRatherThanAnUnrelatedPixelOffset(){
        val jobs=(0..9).map{job(ExportJobState.PAUSED).copy(createdAt=it.toLong())}
        val saved=android.os.Bundle();var expected:String?=null;var portraitEvidence=""
        fun layout(a:ExportQueueActivity,w:Int,h:Int):ViewGroup {
            val root=a.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
            return root
        }
        try {
            org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
                render(c.get(),jobs);val root=layout(c.get(),360,780)
                val scroll=views(root).filterIsInstance<android.widget.ScrollView>().single();scroll.scrollTo(0,1300)
                expected=views(root).first{it.tag?.toString()?.startsWith("export-job:")==true && it.bottom>scroll.scrollY}.tag.toString()
                c.saveInstanceState(saved)
                portraitEvidence="portraitY=${scroll.scrollY}; rows="+cards(c.get()).joinToString{ "${it.tag}[${it.top},${it.bottom}]" }
            }
            org.robolectric.RuntimeEnvironment.setQualifiers("w740dp-h360dp-land-mdpi")
            Robolectric.buildActivity(ExportQueueActivity::class.java).create(saved).start().resume().visible().use{c->
                render(c.get(),jobs);val root=layout(c.get(),740,360)
                val scroll=views(root).filterIsInstance<android.widget.ScrollView>().single()
                val actual=views(root).first{it.tag?.toString()?.startsWith("export-job:")==true && it.bottom>scroll.scrollY}.tag.toString()
                val evidence="$portraitEvidence; savedY=${saved.getInt("scrollY")}; savedAnchor=${saved.getString("scrollAnchor")}; savedOffset=${saved.getInt("scrollOffset")}; landscapeY=${scroll.scrollY}; rows="+cards(c.get()).joinToString{ "${it.tag}[${it.top},${it.bottom}]" }
                assertEquals(evidence,expected,actual)
            }
        }finally{org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")}
    }
    @Test fun pausedAndCancelledRowsExposeOnlySafeActions(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            render(c.get(),listOf(job(ExportJobState.PAUSED)))
            var buttons=views(c.get().window.decorView).filterIsInstance<Button>().filter{it.visibility==View.VISIBLE}
            assertTrue(buttons.any{it.text=="继续"});assertTrue(buttons.any{it.text=="取消"})
            render(c.get(),listOf(job(ExportJobState.CANCELLED)))
            buttons=views(c.get().window.decorView).filterIsInstance<Button>().filter{it.visibility==View.VISIBLE}
            assertFalse(buttons.any{it.text=="继续"||it.text=="重试"||it.text=="取消"})
        }
    }
    private fun applyPublicReference(root:View) {
        // Public CC0 camera scene; this is a source reference, not fabricated engine output.
        val photo=javaClass.getResourceAsStream("/queue/public-source.jpg").use{android.graphics.BitmapFactory.decodeStream(it)}
        views(root).filterIsInstance<dev.filmroom.ui.ExportPreviewView>().filter{it.tag?.toString()?.startsWith("job-thumbnail:")==true}.forEach{
            it.show(photo,"host-reference");it.contentDescription="原图 · 非处理结果"
            (it.parent as ViewGroup).let{frame->(0 until frame.childCount).map(frame::getChildAt).filterIsInstance<android.widget.ImageView>().forEach{v->v.visibility=View.GONE}}
        }
    }
    @Test fun stateVariantsUseContextualActionsAndShowFailures() {
        val out=java.io.File("build/ui-host-renders/queue/states").apply{mkdirs()}
        org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
        for(state in ExportJobState.entries) {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
                val job=job(state).let{if(state==ExportJobState.FAILED)it.copy(progress=ExportDisplayProgress(7),message="导出未完成，保留任务以便重试")else if(state==ExportJobState.COMPLETED)it.copy(progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")else it}
                render(c.get(),listOf(job))
                val root=c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)
                applyPublicReference(root)
                if(state==ExportJobState.FAILED) {
                    // Legacy persisted prose is diagnostic data, not a translatable status.
                    // Keep a localized failure summary visible and prove the original remains
                    // accessible through the existing details action, without rewriting it.
                    val summary=views(root).filterIsInstance<TextView>().single{it.text.toString()==dev.filmroom.i18n.L10n.text(R.string.text_6e4445df6957)}
                    assertTrue(summary.isShown)
                    val diagnostic=views(root).filterIsInstance<TextView>().single{it.text.toString().contains("导出未完成，保留任务以便重试")}
                    assertTrue(diagnostic.text.toString().contains(dev.filmroom.i18n.L10n.text(R.string.queue_original_details)))
                    assertTrue(diagnostic.text.toString().endsWith("导出未完成，保留任务以便重试"))
                    assertFalse(diagnostic.isShown)
                    val detail=views(root).filterIsInstance<Button>().single{it.contentDescription==dev.filmroom.i18n.L10n.text(R.string.text_a9e8a732c910,1)}
                    detail.performClick()
                    assertTrue(diagnostic.isShown)
                    assertTrue(summary.isShown)
                    assertTrue(views(root).filterIsInstance<Button>().single{it.text=="重试"}.isShown)
                    detail.performClick()
                    assertFalse(diagnostic.isShown)
                    assertTrue(diagnostic.text.toString().endsWith("导出未完成，保留任务以便重试"))
                }
                if(state==ExportJobState.COMPLETED)assertTrue(views(root).filterIsInstance<Button>().single{it.text=="查看照片"}.isShown)
                val bitmap=android.graphics.Bitmap.createBitmap(360,780,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(out,"${state.name.lowercase()}.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        }
    }
    @Test fun portraitLandscapeAndLargeTextKeepActionsReadable(){
        val out=java.io.File("build/ui-host-renders/queue").apply{mkdirs()}
        try {
            for((w,h) in listOf(320 to 640,360 to 780,740 to 320))for(scale in listOf(1f,2f)){
                org.robolectric.RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi")
                org.robolectric.RuntimeEnvironment.setFontScale(scale)
                Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
                    c.get().previewSequenceEnabled=false
                    render(c.get(),listOf(job(ExportJobState.RUNNING),job(ExportJobState.PENDING),job(ExportJobState.COMPLETED).copy(progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")))
                    val root=c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
                    applyPublicReference(root)
                    assertFalse(views(root).filterIsInstance<TextView>().any{it.text.toString() in setOf("原图参考","负片示意","导出成片","暂无预览")})
                    for(text in views(root).filterIsInstance<TextView>().filter{it.visibility==View.VISIBLE && it.layout!=null}){
                        for(line in 0 until text.layout.lineCount)assertEquals("Truncated ${text.text}",0,text.layout.getEllipsisCount(line))
                    }
                    if(w>h){
                        val progress=views(root).filterIsInstance<TextView>().first{it.text.toString().startsWith("生成颗粒")}
                        val visible=android.graphics.Rect();assertTrue("Landscape progress must not hide below the thumbnail",progress.getGlobalVisibleRect(visible));assertTrue(visible.height()>30)
                    }
                    for(button in views(root).filterIsInstance<Button>().filter{it.visibility==View.VISIBLE})assertTrue(button.width>=48)
                    val bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    java.io.File(out,"${w}x${h}-font${scale.toInt()}.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                }
            }
        } finally {org.robolectric.RuntimeEnvironment.setFontScale(1f);org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")}
    }
    @Test fun progressTicksPreserveTheGapBeforeVisibleCard() {
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            val jobs=(0..9).map{job(ExportJobState.RUNNING)};render(c.get(),jobs)
            val root=c.get().findViewById<ViewGroup>(android.R.id.content)
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)}
            layout();val scroll=views(root).filterIsInstance<android.widget.ScrollView>().single()
            val next=views(root).single{it.tag=="export-job:${jobs[3].id}"}
            scroll.scrollTo(0,next.top-8);val before=scroll.scrollY
            render(c.get(),jobs.map{it.copy(progress=ExportDisplayProgress(5,70,100))});layout()
            assertEquals(before,scroll.scrollY)
        }
    }
    @Test fun compactStateTransitionPreservesVisibleAnchorAndDetailsCanExpandPhoto() {
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            val jobs=(0..9).map{job(ExportJobState.RUNNING)}
            render(c.get(),jobs)
            val root=c.get().findViewById<ViewGroup>(android.R.id.content)
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)}
            layout()
            val scroll=views(root).filterIsInstance<android.widget.ScrollView>().single();scroll.scrollTo(0,1200)
            val anchored=views(root).first{it.tag?.toString()?.startsWith("export-job:")==true && it.bottom>scroll.scrollY}
            val offset=scroll.scrollY-anchored.top
            render(c.get(),jobs.mapIndexed{index,j->if(index<2)j.copy(state=ExportJobState.COMPLETED)else j});layout()
            assertEquals(offset,scroll.scrollY-anchored.top)
            val completed=views(root).single{it.tag=="export-job:${jobs[0].id}"}
            val thumbnail=views(completed).single{it.tag=="job-thumbnail:${jobs[0].id}"}
            val before=thumbnail.height
            views(completed).filterIsInstance<Button>().single{it.text=="详情"}.performClick();layout()
            assertTrue(thumbnail.height>before)
            assertTrue(views(completed).filterIsInstance<Button>().any{it.text=="收起详情"})
        }
    }
    @Test fun notificationJobOpensWithDetailsExpanded(){
        val job=job(ExportJobState.PAUSED)
        val intent=Intent().putExtra(ExportQueueActivity.EXTRA_JOB_ID,job.id)
        Robolectric.buildActivity(ExportQueueActivity::class.java,intent).setup().use{c->
            render(c.get(),listOf(job))
            assertTrue(views(c.get().window.decorView).filterIsInstance<Button>().any{it.text=="收起详情"})
        }
    }
}
