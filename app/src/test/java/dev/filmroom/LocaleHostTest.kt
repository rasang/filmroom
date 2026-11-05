package dev.filmroom

import android.app.AlertDialog
import android.os.Looper
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.i18n.L10n
import dev.filmroom.i18n.LanguagePicker
import dev.filmroom.diagnostics.NativePhase
import dev.filmroom.ui.ProcessingVisualStage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], application=FilmroomApplication::class, qualifiers="en-rUS-w360dp-h780dp-mdpi")
class LocaleHostTest {
    @org.junit.After fun restoreChineseFixture(){L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
    private val app get()=RuntimeEnvironment.getApplication() as FilmroomApplication
    @Before fun clearChoice(){
        resetAndroidViewModelFactoryForHostTest()
        app.getSharedPreferences("filmroom-language",0).edit().clear().commit()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        L10n.initialize(app)
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
    }
    private fun verifyFreshChinesePicker(){
        assertFalse(L10n.hasChosen(app));assertEquals("zh",L10n.language(app))
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            assertEquals("取消",L10n.text(R.string.text_2cd0f3be8738))
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing);assertEquals(0,dialog.listView.checkedItemPosition)
        }
    }
    @Test @Config(qualifiers="ja-rJP-w360dp-h780dp-mdpi") fun japaneseSystemFirstEntryStillDefaultsChinese(){verifyFreshChinesePicker()}
    @Test @Config(qualifiers="ko-rKR-w360dp-h780dp-mdpi") fun koreanSystemFirstEntryStillDefaultsChinese(){verifyFreshChinesePicker()}
    @Test fun defaultIsChineseIndependentOfSystemAndUpgradeDoesNotSkipChoice(){
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h780dp-mdpi")
        app.getSharedPreferences("local-session",0).edit().putString("source","existing-photo").commit()
        assertEquals("zh",L10n.language(app));assertFalse(L10n.hasChosen(app))
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing);assertEquals(0,dialog.listView.checkedItemPosition)
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK))
            assertTrue(dialog.isShowing);assertFalse(L10n.hasChosen(app))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(L10n.hasChosen(app));assertEquals("zh",L10n.language(app))
        }
    }
    @Test fun firstChoiceRotationKeepsDraftAndConfirmedRestartDoesNotAskAgain(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val first=ShadowAlertDialog.getLatestAlertDialog()
            shadowOf(Looper.getMainLooper()).idle()
            first.listView.performItemClick(first.listView.getChildAt(3),3,3)
            assertFalse(L10n.hasChosen(app))
            controller.recreate()
            val rotated=ShadowAlertDialog.getLatestAlertDialog()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3,rotated.listView.checkedItemPosition)
            rotated.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("ko",L10n.language(app));assertTrue(L10n.hasChosen(app))
        }
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertNull(MainActivity::class.java.getDeclaredField("languageDialog").apply{isAccessible=true}.get(controller.get()))
            assertEquals("ko",controller.get().resources.configuration.locales[0].language)
        }
    }
    @Test fun allFourLanguagesPersistAndResolveActualResourcesAndNativeLabels(){
        val labels=mutableSetOf<String>()
        for(tag in L10n.supported){
            L10n.choose(app,tag);assertTrue(L10n.hasChosen(app));assertEquals(tag,L10n.language(app))
            L10n.initialize(app)
            val manual=android.content.res.Configuration(app.resources.configuration).apply{setLocale(java.util.Locale.SIMPLIFIED_CHINESE)}
            println("CONTEXT app=${app.javaClass.name} pkg=${app.packageName} base=${app.baseContext.javaClass.name} baseLocale=${app.baseContext.resources.configuration.locales} directCN=${app.createConfigurationContext(manual).resources.configuration.locales} baseCN=${app.baseContext.createConfigurationContext(manual).resources.configuration.locales}")
            println("LOCALE requested=$tag wrapped=${L10n.wrap(app).resources.configuration.locales} app=${app.resources.configuration.locales} cancel=${L10n.text(R.string.text_2cd0f3be8738)}")
            assertEquals(tag,L10n.wrap(app).resources.configuration.locales[0].language)
            labels+=L10n.text(R.string.text_2cd0f3be8738)
            NativePhase.entries.forEach { phase ->
                assertEquals(ProcessingVisualStage.fromNativePhase(phase),ProcessingVisualStage.fromMessage("${phase.chinese} · 50%"))
            }
            assertEquals("Filmroom",L10n.wrap(app).getString(R.string.app_name))
        }
        assertEquals(4,labels.size)
    }
    @Test fun gpuCompletionUncertainMessageSurvivesLocaleAndDescriptorRoundTrip(){
        val error=dev.filmroom.engine.EngineException(dev.filmroom.engine.EngineFailure.GPU_COMPLETION_UNCERTAIN)
        val message=error.uiMessage
        val restored=checkNotNull(dev.filmroom.i18n.UiMessage.fromJson(message.toJson()))
        val instructions=mapOf("zh" to "完全关闭并重新打开", "en" to "Fully close and reopen",
            "ja" to "完全に終了してから開き直し", "ko" to "완전히 종료한 뒤 다시 열어")
        val rendered=mutableSetOf<String>()
        for(tag in L10n.supported){
            L10n.choose(app,tag)
            val text=checkNotNull(restored.render())
            assertTrue("Restart instruction missing in $tag: $text",text.contains(checkNotNull(instructions[tag])))
            assertEquals(L10n.text(R.string.gpu_completion_uncertain),text)
            assertEquals(text,error.uiMessage.render())
            rendered+=text
        }
        assertEquals(4,rendered.size)
    }
    @Test fun retainedTypedMessagesAndMalformedDescriptorsAreSafe(){
        val message=dev.filmroom.i18n.UiMessage.of(R.string.text_1663134e88db)
        val state=EditorUiState(busy="读取照片",busyUi=message,message="读取照片",messageUi=message)
        val rendered=mutableSetOf<String>()
        for(tag in L10n.supported){L10n.choose(app,tag);rendered+=state.busyDisplay!!;assertEquals(message.render(),state.messageDisplay)}
        assertEquals(4,rendered.size)
        assertEquals(message,dev.filmroom.i18n.UiMessage.fromJson(message.toJson()))
        assertNull(dev.filmroom.i18n.UiMessage("future-key").render())
        assertNull(dev.filmroom.i18n.UiMessage(message.key,listOf("extra")).render())
    }
    @Test fun deviceConfigurationUpdatesNeverOverrideChosenAppLanguage(){
        L10n.choose(app,"zh")
        for(device in listOf("en-rUS","ja-rJP","ko-rKR")){
            RuntimeEnvironment.setQualifiers("$device-w360dp-h780dp-mdpi")
            println("CONFIG device=$device pref=${L10n.language(app)} scoped=${L10n.wrap(app).resources.configuration.locales} text=${L10n.text(R.string.text_2cd0f3be8738)}")
            assertEquals("取消",L10n.text(R.string.text_2cd0f3be8738))
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
                assertEquals("取消",L10n.text(R.string.text_2cd0f3be8738))
            }
        }
    }
    @Test fun retainedRawAndHeicSourceNotesFollowLanguageAndKnownCodesRebuild(){
        val metadata=org.json.JSONObject().put("ok",true).put("schemaVersion",1).put("format","DNG")
            .put("outputOrientation",1).put("orientationApplied",true).put("width",8).put("height",6).put("outputBytes",576)
            .put("warnings",org.json.JSONArray().put(org.json.JSONObject().put("code","DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED"))
                .put(org.json.JSONObject().put("code","DNG_CAMERA_TONE_CURVE_NOT_APPLIED")))
        val parsed=dev.filmroom.codec.RawMetadata.parse(metadata)
        val raw=dev.filmroom.io.PhotoSource(java.io.File(app.cacheDir,"retained.dng"),"retained.dng","image/x-adobe-dng",8,6,1,developmentMessages=parsed.displayMessages)
        val heic=dev.filmroom.io.PhotoSource(java.io.File(app.cacheDir,"retained.heic"),"retained.heic","image/heif",8,6,1,developmentMessages=listOf(dev.filmroom.i18n.UiMessage.of(R.string.text_99d3398d25b4)))
        val rawNotes=mutableSetOf<String>();val heicNotes=mutableSetOf<String>()
        for(tag in L10n.supported){
            L10n.choose(app,tag)
            rawNotes+=checkNotNull(raw.developmentWarning);heicNotes+=checkNotNull(heic.developmentWarning)
            assertEquals(dev.filmroom.codec.RawMetadata.parse(metadata).displayWarning,raw.developmentWarning)
            assertEquals(L10n.text(R.string.text_99d3398d25b4),heic.developmentWarning)
        }
        assertEquals(4,rawNotes.size);assertEquals(4,heicNotes.size)
        val legacy=dev.filmroom.io.PhotoSource(java.io.File(app.cacheDir,"legacy"),"legacy","image/png",8,6,1,developmentWarning="legacy diagnostic")
        assertEquals("legacy diagnostic",legacy.developmentWarning)
    }
    @Test @Config(sdk=[35]) fun api35ActivitiesUseChosenLocaleWithoutChangingApplicationLocale(){
        val systemLanguage=app.resources.configuration.locales[0].language
        for(tag in L10n.supported){
            L10n.choose(app,tag)
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
                val activity=controller.get()
                assertEquals(tag,activity.resources.configuration.locales[0].language)
                assertEquals(L10n.text(R.string.text_2cd0f3be8738),activity.getString(R.string.text_2cd0f3be8738))
                assertEquals(systemLanguage,app.resources.configuration.locales[0].language)
            }
        }
    }
    @Test fun typedPhotoErrorsKeepClassificationCauseAndDiagnosticSeparationAcrossLanguages(){
        L10n.choose(app,"zh")
        val cause=java.io.IOException("PRIVATE cause path must not appear in the friendly message")
        val error=dev.filmroom.io.PhotoException(dev.filmroom.io.PhotoException.Reason.UNSUPPORTED,
            dev.filmroom.i18n.UiMessage.of(R.string.text_0105eb1c06a7),cause,true,
            dev.filmroom.diagnostics.CodecErrorCode.UNSUPPORTED_FORMAT,dev.filmroom.diagnostics.CodecStage.PROBE)
        val original=error.message
        val state=EditorUiState(message=original,messageUi=error.uiMessage)
        val displays=mutableSetOf<String>()
        for(tag in L10n.supported){L10n.choose(app,tag);displays+=state.messageDisplay!!;assertFalse(state.messageDisplay!!.contains("PRIVATE"))}
        assertEquals(4,displays.size);assertEquals(original,error.message);assertSame(cause,error.cause)
        assertEquals(dev.filmroom.io.PhotoException.Reason.UNSUPPORTED,error.reason);assertTrue(error.cleanupUncertain)
        assertEquals(dev.filmroom.diagnostics.CodecErrorCode.UNSUPPORTED_FORMAT,error.codecCode)
        assertEquals(dev.filmroom.diagnostics.CodecStage.PROBE,error.codecStage)
        val legacy=dev.filmroom.io.PhotoException(dev.filmroom.io.PhotoException.Reason.UNREADABLE,"external diagnostic 913")
        assertNull(legacy.uiMessage)
        val raw=EditorUiState(message=legacy.message,messageIsDiagnostic=true)
        assertEquals(L10n.text(R.string.queue_original_details)+"\nexternal diagnostic 913",raw.messageDisplay)
        assertEquals(raw.messageDisplay,raw.messageDisplay);assertEquals("external diagnostic 913",raw.message)
    }
    @Test fun settingsPickerCancelDoesNotCommitDraftAndConfirmCommits(){
        L10n.choose(app,"zh")
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val a=controller.get()
            val canceled=LanguagePicker.show(a,false){}
            shadowOf(Looper.getMainLooper()).idle()
            canceled.listView.performItemClick(canceled.listView.getChildAt(1),1,1)
            canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals("zh",L10n.language(app))
            var applied=false
            val confirmed=LanguagePicker.show(a,false){applied=true}
            shadowOf(Looper.getMainLooper()).idle()
            confirmed.listView.performItemClick(confirmed.listView.getChildAt(2),2,2)
            confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(applied);assertEquals("ja",L10n.language(app))
        }
    }
    @Test fun languageRecreationKeepsEditorViewModelAndParameters(){
        L10n.choose(app,"zh")
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val before=ViewModelProvider(controller.get())[EditorViewModel::class.java]
            val deadline=System.nanoTime()+5_000_000_000L
            while(!before.state.value.startupReady && System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
            val photo=dev.filmroom.io.PhotoSource(java.io.File(app.cacheDir,"locale-photo.png"),"fixture","image/png",64,96,1)
            val params=dev.filmroom.model.EditorState(brightness=1.5f,filmBorder=true,composition=dev.filmroom.model.CompositionTransform(.4,.6,1.5),crop=dev.filmroom.model.CropSettings(dev.filmroom.model.CropMode.RATIO_2_3))
            @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(before) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
            flow.value=flow.value.copy(photo=photo,parameters=params)
            val state=before.state.value
            L10n.choose(app,"en")
            controller.recreate()
            val after=ViewModelProvider(controller.get())[EditorViewModel::class.java]
            assertSame(before,after);assertSame(photo,after.state.value.photo);assertEquals(state.parameters,after.state.value.parameters)
            assertEquals("en",controller.get().resources.configuration.locales[0].language)
            val importButton=MainActivity::class.java.getDeclaredField("importButton").apply{isAccessible=true}.get(controller.get()) as android.widget.Button
            assertEquals(L10n.text(R.string.text_576d81bb0631),importButton.text.toString().ifBlank{importButton.contentDescription.toString()})
        }
    }
    private fun retainedConfigurationFixture(vm:EditorViewModel):Pair<dev.filmroom.io.PhotoSource,dev.filmroom.model.EditorState>{
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        val deadline=System.nanoTime()+10_000_000_000L
        while((!vm.state.value.startupReady || (operation.get(vm) as? kotlinx.coroutines.Job)?.isCompleted==false) && System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));Thread.sleep(5)}
        assertTrue(vm.state.value.startupReady);assertTrue((operation.get(vm) as? kotlinx.coroutines.Job)?.isCompleted!=false)
        // Retained-state fixture only; real PNG decoding is covered separately by LocaleImportRetentionHostTest.
        val photo=dev.filmroom.io.PhotoSource(java.io.File(app.cacheDir,"configuration-photo.png"),"fixture","image/png",64,96,1)
        val params=dev.filmroom.model.EditorState(brightness=1.25f,crop=dev.filmroom.model.CropSettings(dev.filmroom.model.CropMode.SQUARE,dev.filmroom.model.CompositionTransform(.4,.6,1.4)))
        @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
        flow.value=flow.value.copy(photo=photo,parameters=params)
        return photo to params
    }
    private fun assertChosenChineseActivity(activity:MainActivity){
        assertEquals("zh",L10n.language(app));assertTrue(L10n.hasChosen(app))
        assertEquals("zh",activity.resources.configuration.locales[0].language)
        assertEquals("取消",activity.getString(R.string.text_2cd0f3be8738))
        val button=MainActivity::class.java.getDeclaredField("importButton").apply{isAccessible=true}.get(activity) as android.widget.Button
        assertTrue("Chinese import action must remain localized",button.text.toString().ifBlank{button.contentDescription.toString()} in setOf("导入照片","导入"))
        fun descendants(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
        assertTrue("Visible Film category must remain Chinese",descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString()=="胶片"})
    }
    @Test fun orientationOnlyConfigurationPreservesChosenLocaleAndRetainedEditor(){
        L10n.choose(app,"zh")
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val oldActivity=controller.get();val vm=ViewModelProvider(oldActivity)[EditorViewModel::class.java]
            val (photo,parameters)=retainedConfigurationFixture(vm);assertChosenChineseActivity(oldActivity)
            val rotation=android.content.res.Configuration(oldActivity.resources.configuration)
            rotation.orientation=if(rotation.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
            controller.configurationChange(rotation)
            repeat(2){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))}
            assertNotSame(oldActivity,controller.get());assertSame(vm,ViewModelProvider(controller.get())[EditorViewModel::class.java])
            assertEquals(rotation.orientation,controller.get().resources.configuration.orientation)
            assertSame(photo,vm.state.value.photo);assertEquals(parameters,vm.state.value.parameters);assertChosenChineseActivity(controller.get())
        }
    }
    @Test fun systemLocaleChangesThenLifecycleRecreationPreserveExistingChosenLanguage(){
        L10n.choose(app,"zh")
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val vm=ViewModelProvider(controller.get())[EditorViewModel::class.java]
            val (photo,parameters)=retainedConfigurationFixture(vm);assertChosenChineseActivity(controller.get())
            for(device in listOf("ja-rJP","ko-rKR","en-rUS")){
                val oldActivity=controller.get()
                // Change the host's actual system/Application configuration, then model the normal
                // Activity recreation lifecycle. Do not pass system config as an Activity override,
                // re-select zh, or manually initialize L10n to repair state inside this test.
                RuntimeEnvironment.setQualifiers("$device-w360dp-h780dp-mdpi")
                assertEquals(device.substringBefore('-'),app.resources.configuration.locales[0].language)
                controller.recreate()
                repeat(2){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))}
                assertNotSame(oldActivity,controller.get());assertSame(vm,ViewModelProvider(controller.get())[EditorViewModel::class.java])
                assertSame(photo,vm.state.value.photo);assertEquals(parameters,vm.state.value.parameters);assertChosenChineseActivity(controller.get())
                assertEquals(device.substringBefore('-'),app.resources.configuration.locales[0].language)
            }
        }
    }
}
