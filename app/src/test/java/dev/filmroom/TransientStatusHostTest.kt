package dev.filmroom

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import dev.filmroom.ui.PhotoView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h780dp-mdpi")
class TransientStatusHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun call(a:MainActivity,name:String,s:EditorUiState)=MainActivity::class.java.getDeclaredMethod(name,EditorUiState::class.java).apply{isAccessible=true}.invoke(a,s)
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun layout(v:View){v.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));v.layout(0,0,360,780)}
    @Test fun actualToastBoundaryUsesOnlyShortLocalizedTextAndPreservesOriginalState(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            for(tag in dev.filmroom.i18n.L10n.supported){
                dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),tag)
                val raw="private-path " + "long diagnostic ".repeat(200)
                for((key,expected) in listOf(
                    null to R.string.toast_failed,
                    "text_bb8990972dce" to R.string.toast_queued,
                    "text_834295cc897b" to R.string.toast_queued_check,
                    "text_420e35e0302a" to R.string.toast_queued_check,
                    "text_f9957f03a610" to R.string.toast_background,
                    "text_9f9e68d602d6" to R.string.toast_uncertain,
                    "text_afb4ebeb2c42" to R.string.toast_cancel_requested)){
                    call(c.get(),"showStatus",EditorUiState())
                    ShadowToast.reset()
                    val state=EditorUiState(message=raw,messageUi=key?.let{dev.filmroom.i18n.UiMessage(it)},messageIsDiagnostic=key==null)
                    call(c.get(),"showStatus",state)
                    assertEquals(1,ShadowToast.shownToastCount())
                    assertEquals(dev.filmroom.i18n.L10n.text(expected),ShadowToast.getTextOfLatestToast())
                    assertEquals(raw,state.message)
                    call(c.get(),"showStatus",state);assertEquals(1,ShadowToast.shownToastCount())
                }
                call(c.get(),"showStatus",EditorUiState(engineUnavailable=raw))
                assertEquals(dev.filmroom.i18n.L10n.text(R.string.toast_engine),ShadowToast.getTextOfLatestToast())
            }
        }
    }
    @Test fun statusMessagesCannotMovePhotoOrControlsAndRepeatedBindsDoNotToast(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            call(a,"bind",EditorUiState());layout(root)
            val photo=all(root).filterIsInstance<PhotoView>().single();val before=intArrayOf(photo.left,photo.top,photo.width,photo.height)
            ShadowToast.reset()
            val s=EditorUiState(message="无法读取照片，请重新选择。")
            repeat(3){call(a,"bind",s);layout(root);assertArrayEquals(before,intArrayOf(photo.left,photo.top,photo.width,photo.height))}
            assertEquals(1,ShadowToast.shownToastCount())
            call(a,"bind",s.copy(message=null));layout(root)
            assertArrayEquals(before,intArrayOf(photo.left,photo.top,photo.width,photo.height))
        }
    }
    @Test fun recreatedActivityDoesNotRepeatAlreadyShownMessage(){
        val saved=Bundle();val s=EditorUiState(message="无法读取照片")
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            call(c.get(),"showStatus",s)
            c.saveInstanceState(saved)
        }
        ShadowToast.reset()
        Robolectric.buildActivity(MainActivity::class.java).create(saved).use{c->
            call(c.get(),"showStatus",s)
            assertEquals(0,ShadowToast.shownToastCount())
        }
    }
    @Test fun recoveryFailureKeepsExplicitRetryAndNeverBecomesToastOnly(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            ShadowToast.reset()
            val state=EditorUiState(startupReady=false,recoveryFailed=true,message="清理失败")
            call(c.get(),"showStatus",state)
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertEquals("重试清理",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
            call(c.get(),"showStatus",state);assertSame(dialog,ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(0,ShadowToast.shownToastCount())
            call(c.get(),"showStatus",EditorUiState());assertFalse(dialog.isShowing)
        }
    }
    @Test fun largeTextToolsExpandExplicitlyAndChangingToolResetsScroll(){
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try{Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val root=c.get().window.decorView
            all(root).filterIsInstance<android.widget.Button>().first{it.text.toString()=="调整"}.performClick()
            all(root).filterIsInstance<android.widget.Button>().first{it.text.toString()=="调整胶片曝光"}.performClick()
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            val scroll=all(dialog.window!!.decorView).filterIsInstance<android.widget.ScrollView>().first{all(it).any{v->v is android.widget.SeekBar}}
            assertTrue("The first adjustment is film exposure",all(dialog.window!!.decorView).filterIsInstance<android.widget.SeekBar>().single().contentDescription.toString().startsWith("胶片曝光，"))
            scroll.scrollTo(0,90)
            all(dialog.window!!.decorView).filterIsInstance<android.widget.Button>().first{it.text.toString()=="对比度"}.performClick()
            assertEquals(0,scroll.scrollY)
            // The large duplicate dialog title was deliberately removed so 2×-font
            // landscape retains a fully visible control. Verify the actual active
            // heading/control, not the tool-strip button or an obsolete title.
            val sheet=all(dialog.window!!.decorView)
            assertTrue(sheet.filterIsInstance<android.widget.TextView>().any{
                it !is android.widget.Button && it.text.toString()=="对比度"
            })
            assertTrue(sheet.any{it.contentDescription?.toString()=="对比度说明"})
            assertTrue(sheet.filterIsInstance<android.widget.SeekBar>().single().contentDescription.toString().startsWith("对比度，"))
            dialog.dismiss()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val reopen=all(root).filterIsInstance<android.widget.Button>().single{it.text.toString()=="调整对比度"}
            assertTrue(reopen.isShown)
            reopen.performClick()
            val reopened=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(all(reopened.window!!.decorView).filterIsInstance<android.widget.SeekBar>().single().contentDescription.toString().startsWith("对比度，"))
            reopened.dismiss()
        }}finally{org.robolectric.RuntimeEnvironment.setFontScale(1f)}
    }

    @Test fun undoAndRetryReportNewFailuresEvenWhenTheirTextIsIdentical(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val activity=c.get()
            val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(activity) as EditorViewModel
            @Suppress("UNCHECKED_CAST")
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
            val bitmap=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888)
            state.value=EditorUiState(photo=dev.filmroom.io.PhotoSource(java.io.File(activity.cacheDir,"retry-fixture"),"fixture.jpg","image/jpeg",4,4,100),original=bitmap)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            ShadowToast.reset()
            fun awaitFailedAttempt(){
                val looper=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                looper.idleFor(java.time.Duration.ofMillis(150))
                // Asset installation / IO dispatch can exceed three seconds under concurrent host compilation.
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while(vm.state.value.updating&&System.nanoTime()<deadline){Thread.sleep(10);looper.idle()}
                looper.idle();assertFalse(vm.state.value.updating);assertNotNull(vm.state.value.message)
            }
            vm.parameters(vm.state.value.parameters.copy(brightness=0.5f))
            awaitFailedAttempt();val error=vm.state.value.message
            assertEquals(1,ShadowToast.shownToastCount())
            vm.undo()
            assertTrue(vm.state.value.updating);assertNull(vm.state.value.message)
            awaitFailedAttempt();assertEquals(error,vm.state.value.message)
            assertEquals(2,ShadowToast.shownToastCount())
            // Stop collection across the entire retry: null-message intermediate state is not observed.
            c.pause().stop()
            vm.retry()
            assertTrue(vm.state.value.updating);assertNull(vm.state.value.message)
            awaitFailedAttempt();assertEquals(error,vm.state.value.message)
            assertEquals(2,ShadowToast.shownToastCount())
            c.start().resume()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(3,ShadowToast.shownToastCount())
            // Unchanged rebinds remain silent after each real attempt.
            call(activity,"bind",vm.state.value);assertEquals(3,ShadowToast.shownToastCount())
        }
    }

    @Test fun repeatedImportAndExportFailuresRemainDistinctWhileCollectionIsStopped(){
        for(export in listOf(false,true))Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val activity=c.get()
            val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(activity) as EditorViewModel
            @Suppress("UNCHECKED_CAST")
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
            val bitmap=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888)
            val parameters=dev.filmroom.model.EditorState()
            state.value=EditorUiState(photo=dev.filmroom.io.PhotoSource(java.io.File(activity.cacheDir,"missing-source"),"fixture.jpg","image/jpeg",4,4,100),original=bitmap,rendered=bitmap,parameters=parameters,renderedParameters=parameters)
            val looper=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            looper.idle();ShadowToast.reset()
            fun attempt(){
                val before=vm.state.value.messageAttempt
                if(export)vm.exportOriginalPng()else vm.importPhoto(android.net.Uri.parse("content://missing-provider/photo"))
                assertTrue(vm.state.value.messageAttempt>before)
                // Asset installation / IO dispatch can exceed three seconds under concurrent host compilation.
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while((vm.state.value.busy!=null||vm.state.value.exporting)&&System.nanoTime()<deadline){Thread.sleep(10);looper.idle()}
                looper.idle()
                assertTrue("Timed out: busy=${vm.state.value.busy}, exporting=${vm.state.value.exporting}, progress=${vm.state.value.exportProgress}",vm.state.value.busy==null&&!vm.state.value.exporting)
                assertNull(vm.state.value.busy);assertFalse(vm.state.value.exporting);assertNotNull(vm.state.value.message)
            }
            attempt();val failure=vm.state.value.message;assertEquals(1,ShadowToast.shownToastCount())
            c.pause().stop();attempt();assertEquals(failure,vm.state.value.message);assertEquals(1,ShadowToast.shownToastCount())
            c.start().resume();looper.idle();assertEquals(2,ShadowToast.shownToastCount())
            call(activity,"bind",vm.state.value);assertEquals(2,ShadowToast.shownToastCount())
        }
    }

}
