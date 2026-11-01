package dev.filmroom

import android.app.AlertDialog
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetResetSettingsHostTest {
    @org.junit.Before fun chosenChineseFixture(){
        resetAndroidViewModelFactoryForHostTest()
        dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),"zh")
        // Lifecycle tests need automatic window traversal during ActivityController.recreate().
        // Disable animation duration only in the host fixture so idle cannot drain endless frames.
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
    }
    @org.junit.After fun restoreHostAnimationClock(){
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,1f)
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
    }
    @org.junit.Rule @JvmField val watchdog=object:org.junit.rules.TestWatcher(){
        private var timer:java.util.Timer?=null
        override fun starting(description:org.junit.runner.Description){
            println("START preset/settings: ${description.methodName}")
            timer=java.util.Timer(true).apply {schedule(object:java.util.TimerTask(){override fun run(){
                println("HOST FIXTURE STALL: ${description.methodName}")
                Thread.getAllStackTraces().forEach{(thread,stack)->println("THREAD ${thread.name}:\n"+stack.joinToString("\n"))}
            }},20000)}
        }
        override fun finished(description:org.junit.runner.Description){timer?.cancel();timer=null;println("END preset/settings: ${description.methodName}")}
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun root(a:android.app.Activity)=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun button(a:android.app.Activity,name:String)=all(root(a)).filterIsInstance<Button>().first{it.contentDescription==name || it.text.toString()==name}
    private fun layout(v:View,w:Int,h:Int){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
    private fun await(condition:()->Boolean){
        val deadline=System.nanoTime()+10_000_000_000L
        do{
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));Thread.sleep(5)
            if(condition())return
        }while(System.nanoTime()<deadline)
        fail("Timed out waiting for bounded host fixture")
    }
    private fun awaitOperation(vm:EditorViewModel){
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        await { (operation.get(vm) as? Job)?.isCompleted==true }
    }
    @Suppress("UNCHECKED_CAST")
    private fun setup(a:MainActivity,p:EditorState):Pair<EditorViewModel,MutableStateFlow<EditorUiState>> {
        val vm=ViewModelProvider(a)[EditorViewModel::class.java]
        val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        await { vm.state.value.startupReady && (operation.get(vm) as? Job)?.isCompleted==true }
        assertSame(a.application,vm.getApplication<Application>())
        val photo=PhotoSource(File(a.cacheDir,"preset-source.png"),"test","image/png",96,64,1)
        val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.set(vm,EditHistory(p))
        vm.renderPreview={b,_,_,_->b.copy(Bitmap.Config.ARGB_8888,false)}
        flow.value=EditorUiState(photo=photo,original=bitmap,rendered=bitmap,parameters=p,renderedParameters=p)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
        return vm to flow
    }
    @Test(timeout=30000) fun processingDefaultsResetAllScienceControlsButPreservePresentationAndOutputChoice(){
        val p=EditorState(filmStock="kodak_ektachrome_100",paperStock="other",brightness=3f,grain=0f,diffusionFilter="black_pro_mist",autoExposure=true,
            filmBorder=true,negativeWhiteHoles=false,composition=CompositionTransform(.4,.6,1.7),crop=CropSettings(mode=CropMode.SQUARE),useGpu=false,outputColorSpace="Display P3",outputGamut=false)
        val expected=EditorState(filmStock=p.filmStock,paperStock="kodak_portra_endura",outputRoute="direct",filmBorder=true,negativeWhiteHoles=false,composition=p.composition,crop=p.crop,useGpu=false,outputColorSpace="Display P3",outputGamut=false)
        assertEquals(expected,p.processingDefaults("kodak_portra_endura","direct"))
    }
    @Test(timeout=30000) fun confirmationCancelAndBackNeverMutateAndConfirmationIsOneUndoStep(){
        RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0).edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();val p=EditorState(filmStock="kodak_ektachrome_100",brightness=2f,grain=.2f,filmBorder=false,negativeWhiteHoles=false)
            val(vm,flow)=setup(a,p)
            val appChoices=EditorPreferences(a);appChoices.resetOnImport=true;appChoices.restoreLastPhoto=false
            val reset=button(a,"恢复预设");assertTrue(reset.isEnabled)
            reset.performClick();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));val first=ShadowAlertDialog.getLatestAlertDialog();assertTrue(first.isShowing)
            reset.performClick();assertSame(first,ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(p,flow.value.parameters);first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));assertEquals(p,flow.value.parameters)
            reset.performClick();ShadowAlertDialog.getLatestAlertDialog().cancel();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));assertEquals(p,flow.value.parameters)
            reset.performClick();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));assertTrue(a.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED));assertEquals(p,flow.value.parameters);ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            assertEquals(0f,flow.value.parameters.brightness);assertEquals(1f,flow.value.parameters.grain);assertEquals("direct",flow.value.parameters.outputRoute)
            assertEquals(p.filmStock,flow.value.parameters.filmStock);assertFalse(flow.value.parameters.negativeWhiteHoles)
            assertTrue("Restoring a film preset must preserve explicit app settings",EditorPreferences(a).resetOnImport)
            assertFalse(EditorPreferences(a).restoreLastPhoto)
            assertTrue(flow.value.canUndo);vm.undo();assertEquals(p,flow.value.parameters);assertFalse(flow.value.canUndo)
            vm.cancel()
        }
    }
    @Test(timeout=30000) fun staleConfirmationCannotResetDifferentPhotoOrChangedParametersOrCompositionDraft(){
        RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0).edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();val p=EditorState(brightness=2f);val(vm,flow)=setup(a,p);val key=flow.value.photo!!.cacheKey
            button(a,"恢复预设").performClick();val dialog=ShadowAlertDialog.getLatestAlertDialog()
            flow.value=flow.value.copy(parameters=p.copy(brightness=3f));dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));assertEquals(3f,flow.value.parameters.brightness)
            assertFalse(vm.restorePreset("not-$key",flow.value.parameters))
            flow.value=flow.value.copy(compositionEditing=true);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));assertFalse(button(a,"恢复预设").isEnabled)
            assertFalse(vm.restorePreset(key,flow.value.parameters));assertEquals(3f,flow.value.parameters.brightness)
        }
    }
    @Test(timeout=30000) fun settingsOpensOnceAsSeparateActivityAndBackLeavesEditorStateIntact(){
        RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0).edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();val p=EditorState(brightness=1.5f,negativeWhiteHoles=false);val(_,flow)=setup(a,p)
            val settings=button(a,"设置");settings.performClick();settings.performClick()
            assertEquals(SettingsActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className);assertNull(shadowOf(a).nextStartedActivity)
            assertEquals(p,flow.value.parameters)
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use{s->s.get().onBackPressedDispatcher.onBackPressed();assertTrue(s.get().isFinishing)}
            c.pause().stop().start().resume();assertEquals(p,flow.value.parameters)
        }
    }
    @Test(timeout=30000) fun settingsPreservesCustomInputOnRecreationAndRejectsOutOfRangeWithoutSharing(){
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
            val a=c.get();all(root(a)).filterIsInstance<RadioButton>().single{it.text.toString()=="自定义分钟数"}.performClick()
            val field=all(root(a)).filterIsInstance<EditText>().single();field.setText("1441")
            button(a,"分享 TXT 日志").performClick();assertNotNull(field.error);assertNull(shadowOf(a).nextStartedActivity)
            field.setText("42");c.recreate()
            val restored=all(root(c.get())).filterIsInstance<EditText>().single();assertEquals("42",restored.text.toString());assertTrue(restored.isEnabled)
        }
    }
    @Test(timeout=30000) fun settingsChoicesPersistAndColdRestoreOffDoesNotClearSavedSession(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("editor-preferences",0).edit().clear().commit()
        val prefs=EditorPreferences(app);assertFalse(prefs.resetOnImport);assertFalse(prefs.restoreLastPhoto)
        // Explicit saved ON choices survive upgrades; subsequent clicks exercise OFF persistence.
        prefs.resetOnImport=true;prefs.restoreLastPhoto=true
        assertTrue(EditorPreferences(app).resetOnImport);assertTrue(EditorPreferences(app).restoreLastPhoto)
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
            val switches=all(root(c.get())).filterIsInstance<Switch>()
            switches.single{it.contentDescription=="导入新照片时重置参数"}.performClick()
            switches.single{it.contentDescription=="启动时恢复上次照片"}.performClick()
            assertFalse(prefs.resetOnImport);assertFalse(prefs.restoreLastPhoto)
            c.recreate();assertTrue(all(root(c.get())).filterIsInstance<Switch>().none{it.isChecked})
        }
        val session=app.getSharedPreferences("local-session",0)
        session.edit().putString("source","deliberately-missing-last-photo").putString("parameters",EditorState(brightness=2f).json()).commit()
        val vm=EditorViewModel(app)
        repeat(15){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));Thread.sleep(5)}
        assertNull(vm.state.value.photo);assertNull(vm.state.value.busy)
        assertEquals("deliberately-missing-last-photo",session.getString("source",null))
        prefs.restoreLastPhoto=true
        repeat(5){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));Thread.sleep(5)}
        assertNull("Changing setting does not hot-import while editor is open",vm.state.value.photo)
        val restarted=EditorViewModel(app)
        awaitOperation(restarted)
        assertNull(restarted.state.value.photo)
        assertNotNull("Unavailable previous source handled gracefully",restarted.state.value.message)
        vm.cancel();restarted.cancel();session.edit().clear().commit();app.getSharedPreferences("editor-preferences",0).edit().clear().commit();Unit
    }
    @Test(timeout=30000) fun localizedContextsShareOnePersistedEditorPreferenceStore(){
        // ActivityController.recreate() internally waits for a new window traversal.
        // Let the host schedule vsync automatically for this lifecycle fixture; suppress
        // animation duration only in the fixture so automatic idle cannot drain forever.
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        val durationScale=android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}
        durationScale.invoke(null,0f)
        fun settledWindow(activity:android.app.Activity){
            repeat(2){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))}
            assertTrue("Host window must be attached before lifecycle assertions",activity.window.decorView.isAttachedToWindow)
        }
        val app=RuntimeEnvironment.getApplication()
        val appStore=app.getSharedPreferences("editor-preferences",0)
        println("PREFERENCE_CONTEXT application=${app.javaClass.name} base=${app.baseContext.javaClass.name} data=${app.applicationInfo.dataDir}")
        try { for(language in dev.filmroom.i18n.L10n.supported) {
            dev.filmroom.i18n.L10n.choose(app,language)
            val scoped=dev.filmroom.i18n.L10n.wrap(app)
            assertSame("Scoped UI context must retain process Application",app,scoped.applicationContext)
            EditorPreferences(app).resetOnImport=false
            assertFalse(EditorPreferences(scoped).resetOnImport)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity=controller.get();settledWindow(activity)
                val vm=ViewModelProvider(activity)[EditorViewModel::class.java]
                println("PREFERENCE_CONTEXT locale=$language activityApp=${activity.application.javaClass.name} vmApp=${vm.getApplication<Application>().javaClass.name} scoped=${scoped.javaClass.name} data=${activity.applicationInfo.dataDir}")
                assertSame("Editor VM must use the original process Application",app,vm.getApplication<Application>())
                assertSame("Activity must not introduce an alternative Application",app,activity.applicationContext)
                assertFalse(EditorPreferences(activity).resetOnImport)
                assertFalse(EditorPreferences(vm.getApplication<Application>()).resetOnImport)
                Robolectric.buildActivity(SettingsActivity::class.java).setup().use { settings ->
                    settledWindow(settings.get())
                    EditorPreferences(settings.get()).resetOnImport=true
                    assertTrue(EditorPreferences(app).resetOnImport)
                    assertTrue(EditorPreferences(vm.getApplication<Application>()).resetOnImport)
                    EditorPreferences(settings.get()).resetOnImport=false
                    settings.recreate();settledWindow(settings.get())
                    assertFalse(EditorPreferences(settings.get()).resetOnImport)
                }
                controller.recreate();settledWindow(controller.get())
                assertSame(vm,ViewModelProvider(controller.get())[EditorViewModel::class.java])
                assertFalse(EditorPreferences(controller.get()).resetOnImport)
                vm.cancel()
            }
            assertFalse("Fresh preference wrapper reads the same committed value",EditorPreferences(app).resetOnImport)
        }} finally { appStore.edit().clear().commit();dev.filmroom.i18n.L10n.choose(app,"zh");durationScale.invoke(null,1f);org.robolectric.shadows.ShadowChoreographer.setPaused(true) }
    }
    @Test(timeout=30000) fun importSwitchOnlyAdoptsOnSuccessAndAlwaysDropsOldCrop(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val prefs=EditorPreferences(app)
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        try{for((reset,whiteHoles) in listOf(false to false,true to false,false to true,true to true)){
            prefs.resetOnImport=reset
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val a=c.get();val p=EditorState(filmStock="kodak_gold_200",brightness=2f,grain=.2f,filmBorder=true,negativeWhiteHoles=whiteHoles,composition=CompositionTransform(.4,.6,1.7),crop=CropSettings(CropMode.SQUARE))
                val(vm,flow)=setup(a,p);val original=flow.value.photo
                val oldHistory=EditHistory().apply{update(p)}
                EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.set(vm,oldHistory)
                flow.value=flow.value.copy(canUndo=true)
                EditorViewModel::class.java.getDeclaredMethod("saveSession").apply{isAccessible=true}.invoke(vm)
                val savedBefore=app.getSharedPreferences("local-session",0).all.toMap()
                vm.importPhoto(android.net.Uri.parse("content://missing-import-provider/photo"))
                awaitOperation(vm)
                assertSame(original,flow.value.photo);assertEquals(p,flow.value.parameters)
                assertTrue(flow.value.canUndo);assertEquals(p,oldHistory.current)
                assertEquals(savedBefore,app.getSharedPreferences("local-session",0).all)
                val input=File(app.cacheDir,"reset-import-$reset.png")
                val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
                input.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                val uri=android.net.Uri.parse("content://preset-import-test/$reset.png")
                shadowOf(app.contentResolver).registerInputStream(uri,input.inputStream())
                assertEquals("Import preference before valid source",reset,EditorPreferences(vm.getApplication<Application>()).resetOnImport)
                assertEquals("Processing state before valid source",p,flow.value.parameters)
                vm.importPhoto(uri)
                awaitOperation(vm)
                assertNotSame("${flow.value.message}",original,flow.value.photo)
                assertEquals("kodak_gold_200",flow.value.parameters.filmStock)
                assertEquals(if(reset)0f else 2f,flow.value.parameters.brightness)
                assertEquals(if(reset)1f else .2f,flow.value.parameters.grain)
                assertEquals(CropSettings(),flow.value.parameters.crop);assertEquals(CompositionTransform(),flow.value.parameters.composition)
                assertEquals("Only successful reset-ON import disables the border",!reset,flow.value.parameters.filmBorder)
                assertEquals("White-hole preference is independent of border enablement",whiteHoles,flow.value.parameters.negativeWhiteHoles)
                assertEquals("Old photo edit/history must not be mutated",p,oldHistory.current)
                assertEquals(flow.value.parameters.json(),app.getSharedPreferences("local-session",0).getString("parameters",null))
                assertFalse(flow.value.canUndo);assertFalse(flow.value.canRedo)
                val newKey=flow.value.photo!!.cacheKey
                prefs.restoreLastPhoto=true
                val restored=EditorViewModel(app).apply{renderPreview={b,_,_,_->b.copy(Bitmap.Config.ARGB_8888,false)}}
                awaitOperation(restored)
                assertEquals(newKey,restored.state.value.photo!!.cacheKey);assertEquals(flow.value.parameters,restored.state.value.parameters)
                restored.cancel();vm.cancel()
            }
            app.getSharedPreferences("local-session",0).edit().clear().commit()
        }}finally{app.getSharedPreferences("editor-preferences",0).edit().clear().commit()}
    }
    @Test(timeout=30000) fun cancellationDuringImportPreservesPhotoParametersAndHistoryForBothSettings(){
        val app=RuntimeEnvironment.getApplication();val prefs=EditorPreferences(app)
        try{for(reset in listOf(false,true)){
            app.getSharedPreferences("local-session",0).edit().clear().commit();prefs.resetOnImport=reset
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val p=EditorState(brightness=2f,filmBorder=true,composition=CompositionTransform(.4,.6,1.7),crop=CropSettings(CropMode.SQUARE),negativeWhiteHoles=false)
                val(vm,flow)=setup(c.get(),p);val original=flow.value.photo
                val history=EditHistory().apply{update(p)}
                EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.set(vm,history)
                flow.value=flow.value.copy(canUndo=true)
                EditorViewModel::class.java.getDeclaredMethod("saveSession").apply{isAccessible=true}.invoke(vm)
                val savedBefore=app.getSharedPreferences("local-session",0).all.toMap()
                val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
                val uri=android.net.Uri.parse("content://preset-import-test/cancel-$reset")
                val stream=object:java.io.InputStream(){
                    override fun read():Int {entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return -1}
                    override fun read(b:ByteArray,off:Int,len:Int)=read()
                }
                shadowOf(app.contentResolver).registerInputStream(uri,stream)
                try{
                    vm.importPhoto(uri)
                    await { entered.count==0L }
                    vm.cancel();release.countDown()
                    awaitOperation(vm)
                    assertSame(original,flow.value.photo);assertEquals(p,flow.value.parameters);assertTrue(flow.value.canUndo)
                    assertEquals(savedBefore,app.getSharedPreferences("local-session",0).all)
                    assertEquals(p,history.current);history.undo();assertEquals(EditorState(),history.current)
                }finally{release.countDown();vm.cancel()}
            }
        }}finally{app.getSharedPreferences("editor-preferences",0).edit().clear().commit()}
    }
    @Test(timeout=30000) fun restoreOffWhileEditingDoesNotChangePhotoAcrossSettingsAndRotation(){
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("local-session",0).edit().clear().commit()
        try{Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val p=EditorState(brightness=1.2f,negativeWhiteHoles=false,crop=CropSettings(CropMode.RATIO_3_4))
            val(vm,flow)=setup(c.get(),p);val source=flow.value.photo
            EditorPreferences(app).restoreLastPhoto=false
            button(c.get(),"设置").performClick();c.pause().stop().start().resume()
            assertSame(source,flow.value.photo);assertEquals(p,flow.value.parameters)
            c.recreate();val after=ViewModelProvider(c.get())[EditorViewModel::class.java]
            assertSame(vm,after);assertSame(source,after.state.value.photo);assertEquals(p,after.state.value.parameters)
        }}finally{app.getSharedPreferences("editor-preferences",0).edit().clear().commit()}
    }
    @Test(timeout=30000) fun toolbarAndSettingsNativeScreensAtNarrowLandscapeAndLargeFonts(){
        val out=File("build/preset-settings-qa").apply{mkdirs()}
        try{for((w,h)in listOf(320 to 640,360 to 780,740 to 320))for(font in listOf(1f,2f)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(font)
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val a=c.get();layout(root(a),w,h);assertFalse(button(a,"恢复预设").isEnabled)
                val toolbar=button(a,"恢复预设").parent as ViewGroup
                assertEquals(7,all(toolbar).filterIsInstance<Button>().size)
                val image=Bitmap.createBitmap(w,toolbar.height,Bitmap.Config.ARGB_8888);toolbar.draw(Canvas(image));File(out,"toolbar-${w}-font$font.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            }
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
                val view=root(c.get());layout(view,w,h)
                val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(image));File(out,"settings-${w}-font$font.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            }
        }}finally{RuntimeEnvironment.setFontScale(1f)}
    }
}
