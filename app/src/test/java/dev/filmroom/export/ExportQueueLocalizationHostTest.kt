package dev.filmroom.export

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import dev.filmroom.i18n.UiMessage
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ExportQueueLocalizationHostTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private fun choose(tag:String)=L10n.choose(app,tag)
    @After fun resetLanguage(){choose("zh")}
    private fun job()=ExportJob(ExportJobStore.newId(),1L,EditorState(),2,2,ExportFormat.PNG,95,state=ExportJobState.FAILED)
    private fun temp(block:(File)->Unit){val dir=Files.createTempDirectory("queue-locale").toFile();try{block(dir)}finally{dir.deleteRecursively()}}

    @Test fun stableDescriptorSurvivesSaveReadAndEveryLanguageSwitch()=temp {dir->
        choose("zh")
        val descriptor=UiMessage.of(R.string.text_c42a34760d85)
        val original=job().copy(message=descriptor.render(),messageUi=descriptor)
        val store=ExportJobStore(dir);store.update(original)
        val persisted=JSONObject(File(store.directory(original.id),"manifest.json").readText())
        assertEquals("text_c42a34760d85",persisted.getJSONObject("messageUi").getString("key"))
        val messages=L10n.supported.map {tag->
            choose(tag)
            val loaded=ExportJobStore(dir).get(original.id)!!
            assertEquals(original.message,loaded.message)
            assertEquals(descriptor,loaded.messageUi)
            assertEquals(L10n.text(R.string.text_c42a34760d85),loaded.localizedMessage)
            assertEquals(original.message,loaded.originalDiagnostics)
            loaded.localizedMessage
        }
        assertEquals(4,messages.toSet().size)
    }
    @Test fun oldUnknownAndBadArityDescriptorsKeepRawErrorWithoutGuessingText()=temp {dir->
        val store=ExportJobStore(dir)
        val original=job().copy(message="private codec: status=913 / old diagnostic")
        store.update(original)
        val file=File(store.directory(original.id),"manifest.json")
        val base=JSONObject(file.readText())
        val bad=listOf<Any?>(null,JSONObject().put("key","future_unknown").put("args",JSONArray()),
            JSONObject().put("key","text_c42a34760d85").put("args",JSONArray().put("wrong arity")),"not an object")
        bad.forEach{descriptor->
            val json=JSONObject(base.toString());if(descriptor==null)json.remove("messageUi") else json.put("messageUi",descriptor)
            file.writeText(json.toString())
            L10n.supported.forEach {tag->
                choose(tag)
                val loaded=ExportJobStore(dir).get(original.id)!!
                assertEquals(ExportJobState.FAILED,loaded.state)
                assertEquals(L10n.text(R.string.text_6e4445df6957),loaded.localizedMessage)
                assertEquals(original.message,loaded.originalDiagnostics)
            }
        }
    }
    @Test fun formatArgumentsPersistWithoutFreezingTranslatedTemplate()=temp {dir->
        choose("zh")
        val descriptor=UiMessage.of(R.string.text_183e988f1288,"25","4000 × 3000")
        val original=job().copy(message=descriptor.render(),messageUi=descriptor)
        ExportJobStore(dir).update(original)
        L10n.supported.forEach {tag->
            choose(tag)
            val loaded=ExportJobStore(dir).get(original.id)!!
            assertEquals(descriptor,loaded.messageUi)
            assertEquals(L10n.text(R.string.text_183e988f1288,"25","4000 × 3000"),loaded.localizedMessage)
        }
    }
    @Test fun recoveredJobsStayPausedWithLocalizedReasonAndFrozenParameters()=temp {dir->
        choose("zh")
        val original=job().copy(state=ExportJobState.RUNNING,parameters=EditorState().copy(filmExposure=1.25f))
        val store=ExportJobStore(dir);store.update(original)
        val checkpoint=File(store.directory(original.id),"checkpoint").apply{mkdirs()}
        File(checkpoint,"plane").writeText("durable")
        val recovered=ExportJobStore(dir)
        assertNull(recovered.next())
        L10n.supported.forEach {tag->
            choose(tag)
            val loaded=recovered.get(original.id)!!
            assertEquals(ExportJobState.PAUSED,loaded.state)
            assertEquals(original.parameters,loaded.parameters)
            assertEquals(L10n.text(R.string.text_9652b13c1fd3),loaded.localizedMessage)
            assertEquals("durable",File(checkpoint,"plane").readText())
        }
    }
    @Test fun metadataAndCustomInterruptedDiagnosticsAreNeverDiscarded()=temp {dir->
        choose("en")
        val original=job().copy(state=ExportJobState.COMPLETED,message="old saved",messageUi=UiMessage.of(R.string.text_1bd91a7d0c53),metadataWarning="EXIF failure code=12")
        val store=ExportJobStore(dir);store.update(original)
        val loaded=ExportJobStore(dir).get(original.id)!!
        assertTrue(loaded.localizedMessage!!.contains(L10n.text(R.string.queue_metadata_warning)))
        assertTrue(loaded.originalDiagnostics!!.contains("EXIF failure code=12"))
        val running=job().copy(state=ExportJobState.RUNNING);store.update(running)
        store.interruptedInMemory(running.id,"custom storage diagnosis")
        val paused=store.get(running.id)!!
        assertEquals(ExportJobState.PAUSED,paused.state)
        assertEquals("custom storage diagnosis",paused.originalDiagnostics)
        assertEquals(L10n.text(R.string.text_eb0c326b60ae),paused.localizedMessage)
    }
}
