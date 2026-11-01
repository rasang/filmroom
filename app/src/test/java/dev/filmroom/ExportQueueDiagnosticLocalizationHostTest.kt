package dev.filmroom

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportJobStore
import dev.filmroom.i18n.L10n
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportQueueDiagnosticLocalizationHostTest {
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    @After fun resetLanguage(){L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
    @Test fun legacyFailureHasLocalizedSummaryAndAccessibleUnchangedDiagnosticInEveryLanguage(){
        val raw="codec-original: failure 913"
        val job=ExportJob(ExportJobStore.newId(),1,EditorState(),2,2,ExportFormat.PNG,95,state=ExportJobState.FAILED,message=raw)
        L10n.supported.forEach {tag->
            L10n.choose(RuntimeEnvironment.getApplication(),tag)
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use {controller->
                val activity=controller.get()
                ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(activity,listOf(job))
                val card=views(activity.window.decorView).single{it.tag=="export-job:${job.id}"}
                val texts=views(card).filterIsInstance<TextView>()
                assertTrue(texts.any{it.text.toString()==L10n.text(R.string.text_6e4445df6957) && it.visibility==View.VISIBLE})
                val diagnostic=texts.single{it.text.contains(raw)}
                assertTrue(diagnostic.text.contains(L10n.text(R.string.queue_original_details)))
                assertEquals(View.GONE,diagnostic.visibility)
                // Details is the existing card action; no locale-specific text matching.
                views(card).filterIsInstance<Button>().single{it.contentDescription==L10n.text(R.string.text_a9e8a732c910,1)}.performClick()
                assertEquals(View.VISIBLE,diagnostic.visibility)
                assertTrue(diagnostic.text.toString().endsWith(raw))
            }
        }
    }
}
