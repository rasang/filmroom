package dev.filmroom

import android.app.AlertDialog
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ImageButton
import android.widget.ImageView
import android.content.Intent
import org.robolectric.shadows.ShadowPopupMenu
import dev.filmroom.presets.PresetQrCodec
import dev.filmroom.presets.PresetQrImage
import dev.filmroom.presets.PresetQrActivity
import dev.filmroom.presets.SavedPresetDialogs
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditHistory
import dev.filmroom.model.EditorState
import dev.filmroom.presets.SavedPresetStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
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
class SavedPresetsUiHostTest {
    @Before fun reset() {
        // Robolectric gives every test a different private root; AndroidX caches provider paths.
        val providerCache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply{isAccessible=true}
        (providerCache.get(null) as MutableMap<*,*>).clear()
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        dev.filmroom.i18n.L10n.choose(app,"zh")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        app.getSharedPreferences("editor-preferences",0).edit().clear().commit()
        File(app.filesDir,"saved-presets.json").delete()
        File(app.filesDir,"saved-presets.json.bak").delete()
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
    }
    @org.junit.After fun resetClock() {
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,1f)
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun field(activity:MainActivity,name:String) = MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun button(activity:MainActivity,name:String) = field(activity,name) as Button
    private fun latest() = ShadowAlertDialog.getLatestAlertDialog()
    private fun list(dialog:AlertDialog) = all(dialog.window!!.decorView).filterIsInstance<ListView>().single()
    private fun input(dialog:AlertDialog) = all(dialog.window!!.decorView).filterIsInstance<EditText>().single()
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+10_000_000_000L
        do { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)); Thread.sleep(5); if(condition())return } while(System.nanoTime()<deadline)
        fail("Timed out")
    }
    @Suppress("UNCHECKED_CAST")
    private fun setup(activity:MainActivity,parameters:EditorState):Pair<EditorViewModel,MutableStateFlow<EditorUiState>> {
        val vm=ViewModelProvider(activity)[EditorViewModel::class.java]
        val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        await{vm.state.value.startupReady && (operation.get(vm) as? Job)?.isCompleted==true}
        val source=PhotoSource(File(activity.cacheDir,"named-preset-source.png"),"fixture","image/png",96,64,1)
        val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.set(vm,EditHistory(parameters))
        vm.renderPreview={b,_,_,_->b.copy(Bitmap.Config.ARGB_8888,false)}
        flow.value=EditorUiState(photo=source,original=bitmap,rendered=bitmap,parameters=parameters,renderedParameters=parameters)
        await{button(activity,"recipeButton").isEnabled}
        return vm to flow
    }
    private fun choose(dialog:AlertDialog,index:Int=0) {
        val list=list(dialog);await{(list.adapter?.count ?: 0) > index}
        list.performItemClick(list.adapter.getView(index,null,list),index,index.toLong())
    }
    private fun openSave(activity:MainActivity) {
        button(activity,"recipeButton").performClick()
        val dialog=latest()
        if(all(dialog.window!!.decorView).any{it is ListView}) {
            await{dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled}
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            await{latest().isShowing && all(latest().window!!.decorView).any{it is EditText}}
        }
    }
    private fun action(dialog:AlertDialog,id:Int,index:Int=0) {
        val rows=list(dialog);await{rows.childCount>index}
        assertTrue(rows.onItemLongClickListener!!.onItemLongClick(rows,rows.getChildAt(index),index,index.toLong()))
        val menu=ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull(menu)
        val popup=shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        if(popup!=null) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            val root=popup.contentView.rootView
            if(root.width>0 && root.height>0) {
                val output=File("build/ui-host-renders/recipe-long-press-menu.png");output.parentFile.mkdirs()
                val image=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image));output.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            }
        }
        assertTrue(menu.menu.performIdentifierAction(id,0))
    }
    private fun render(dialog:AlertDialog,name:String) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
        val decor=dialog.window!!.decorView
        val output=File("build/ui-host-renders/$name.png");output.parentFile.mkdirs()
        Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888).let{bitmap->decor.draw(Canvas(bitmap));output.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test(timeout=30000) fun saveValidatesNameAndCancelDoesNotWriteOrChangeCompare() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val parameters=EditorState(brightness=1.3f)
            val(vm,flow)=setup(activity,parameters);val store=SavedPresetStore(activity)
            vm.compare();assertTrue(flow.value.compare)
            openSave(activity);val first=latest()
            openSave(activity);assertSame(first,latest())
            first.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(input(first).error)
            input(first).setText("Draft");first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(store.list().isEmpty());assertEquals(parameters,flow.value.parameters);assertTrue(flow.value.compare)
            openSave(activity);val saved=latest();input(saved).setText("  夕阳  ")
            saved.getButton(AlertDialog.BUTTON_POSITIVE).performClick();saved.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{!saved.isShowing};assertEquals(1,store.list().size);assertEquals("夕阳",store.list().single().name)
            assertEquals(parameters,store.list().single().parameters());assertFalse(flow.value.canUndo);assertTrue(flow.value.compare)
            openSave(activity);val duplicate=latest();input(duplicate).setText("夕阳")
            duplicate.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await{input(duplicate).error!=null};assertTrue(duplicate.isShowing)
            duplicate.cancel();assertEquals(1,store.list().size);vm.cancel()
        }
    }
    @Test(timeout=30000) fun selectionIsDraftApplyIsOneUndoStepAndDeletionDoesNotEditPhoto() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val before=EditorState(brightness=.5f)
            val(vm,flow)=setup(activity,before);val store=SavedPresetStore(activity)
            val after=EditorState(filmStock="kodak_gold_200",brightness=2f,grain=.3f,negativeWhiteHoles=false)
            store.save("Gold",after)
            button(activity,"recipeButton").performClick();val cancelled=latest();choose(cancelled)
            assertEquals(before,flow.value.parameters);cancelled.cancel();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));assertEquals(before,flow.value.parameters)
            button(activity,"recipeButton").performClick();val applying=latest();choose(applying)
            applying.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await{!applying.isShowing}
            assertEquals(after,flow.value.parameters);assertTrue(flow.value.canUndo)
            vm.undo();assertEquals(before,flow.value.parameters);assertFalse(flow.value.canUndo)
            vm.redo();assertEquals(after,flow.value.parameters)
            await{button(activity,"recipeButton").visibility==View.VISIBLE}
            button(activity,"recipeButton").performClick();val deleting=latest();choose(deleting)
            action(deleting,R.id.recipe_action_delete);val confirmation=latest()
            assertNotSame(deleting,confirmation);confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            assertFalse(confirmation.isShowing)
            assertEquals(1,store.list().size);assertTrue(deleting.isShowing)
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));assertEquals(1,store.list().size)
            action(deleting,R.id.recipe_action_delete);latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{list(deleting).adapter?.count==0};assertEquals(after,flow.value.parameters)
            assertFalse(deleting.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled);assertTrue(store.list().isEmpty())
            deleting.cancel();vm.cancel()
        }
    }
    @Test(timeout=30000) fun staleDialogAndCompositionDraftCannotApply() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val before=EditorState(brightness=.5f)
            val(vm,flow)=setup(activity,before);SavedPresetStore(activity).save("Other",before.copy(brightness=2f))
            button(activity,"recipeButton").performClick();val dialog=latest();choose(dialog)
            flow.value=flow.value.copy(parameters=before.copy(brightness=3f))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing);assertEquals(3f,flow.value.parameters.brightness)
            assertFalse(vm.applySavedPreset("other-photo",flow.value.parameters,before))
            dialog.cancel();flow.value=flow.value.copy(compositionEditing=true)
            assertFalse(vm.applySavedPreset(flow.value.photo!!.cacheKey,flow.value.parameters,before))
            await{!button(activity,"recipeButton").isEnabled}
        }
    }
    @Test(timeout=30000) fun saveDraftSurvivesRotationWithoutWriting() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val before=EditorState(brightness=.5f);setup(activity,before)
            openSave(activity);input(latest()).setText("旋转后的名字")
            host.recreate();await{latest().isShowing && all(latest().window!!.decorView).filterIsInstance<EditText>().any{it.text.toString()=="旋转后的名字"}}
            assertTrue(SavedPresetStore(host.get()).list().isEmpty());latest().cancel()
        }
    }
    @Test(timeout=30000) fun loadSelectionSurvivesRotationAndSamePresetDoesNotAddUndo() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val before=EditorState(brightness=.5f);val(vm,flow)=setup(activity,before)
            SavedPresetStore(activity).save("Same",before)
            vm.compare();button(activity,"recipeButton").performClick();choose(latest())
            host.recreate();await{latest().isShowing && list(latest()).checkedItemPosition==0}
            val applying=latest();applying.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await{!applying.isShowing}
            assertEquals(before,flow.value.parameters);assertFalse(flow.value.compare);assertFalse(flow.value.canUndo)
        }
    }
    @Test(timeout=30000) fun missingStockIsRetainedButCannotBePartiallyApplied() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val before=EditorState(brightness=.5f);val(_,flow)=setup(activity,before)
            val store=SavedPresetStore(activity);store.save("New stock",before.copy(filmStock="future_film"))
            button(activity,"recipeButton").performClick();val dialog=latest();choose(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{all(dialog.window!!.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString().contains("不支持")}}
            assertTrue(dialog.isShowing);assertEquals(before,flow.value.parameters)
            assertEquals("future_film",store.list().single().parameters().filmStock);dialog.cancel()
        }
    }
    @Test(timeout=60000) fun dialogsFitShortLargeFontEnglishAndChineseWindows() {
        for(language in listOf("zh","en")) {
            dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),language)
            RuntimeEnvironment.setQualifiers("w740dp-h320dp-mdpi");RuntimeEnvironment.setFontScale(2f)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
                val activity=host.get();setup(activity,EditorState())
                SavedPresetStore(activity).save("Long named preset with complete current settings",EditorState())
                for(mode in listOf("saveCurrentRecipe","recipeButton","shareRecipe")) {
                    if(mode=="saveCurrentRecipe")openSave(activity)else {
                        button(activity,"recipeButton").performClick()
                        if(mode=="shareRecipe") { choose(latest());action(latest(),R.id.recipe_action_share);await{latest().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled} }
                    }
                    val dialog=latest()
                    if(mode=="recipeButton")choose(dialog)
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
                    val decor=dialog.window!!.decorView
                    assertTrue(decor.width>0 && decor.height>0)
                    if(mode=="recipeButton") {
                        val entries=list(dialog);val row=entries.getChildAt(0)
                        assertNotNull(row);assertTrue("A complete preset row must fit in the list",entries.height>=row.height)
                        val label=row as android.widget.TextView;val visible=android.graphics.Rect()
                        assertTrue(label.getGlobalVisibleRect(visible))
                        assertTrue("Preset name row is clipped by an ancestor",visible.height()>=label.height)
                        assertTrue("Preset name text is clipped within its row",label.height-label.compoundPaddingTop-label.compoundPaddingBottom>=label.layout.height)
                    } else if(mode=="saveCurrentRecipe") {
                        val field=input(dialog);val visible=android.graphics.Rect()
                        assertTrue(field.getGlobalVisibleRect(visible));assertTrue("Name input must be visible without scrolling",visible.height()>=field.height)
                    }
                    for(which in if(mode=="recipeButton") listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL) else listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE)) {
                        val action=dialog.getButton(which);val rect=android.graphics.Rect()
                        assertTrue(action.getGlobalVisibleRect(rect));assertTrue("Dialog action is clipped",rect.height()>=action.height)
                    }
                    val output=File("build/ui-host-renders/saved-presets-dialog-$language-$mode.png");output.parentFile.mkdirs()
                    Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888).let{bitmap->decor.draw(Canvas(bitmap));output.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
                    dialog.cancel();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
                }
            }
            File(RuntimeEnvironment.getApplication().filesDir,"saved-presets.json").delete()
        }
    }
    @Test(timeout=60000) fun maximumWidthNamesWrapAndScrollWithoutInternalTextClipping() {
        RuntimeEnvironment.setQualifiers("w740dp-h320dp-mdpi");RuntimeEnvironment.setFontScale(2f)
        for(name in listOf("预".repeat(60),"W".repeat(60))) {
            Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
                val activity=host.get();setup(activity,EditorState());SavedPresetStore(activity).save(name,EditorState())
                button(activity,"recipeButton").performClick();val dialog=latest();choose(dialog)
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
                val entries=list(dialog);val row=entries.getChildAt(0) as android.widget.TextView
                assertEquals(name,row.text.toString())
                assertTrue("All name lines must fit within their row",row.height-row.compoundPaddingTop-row.compoundPaddingBottom>=row.layout.height)
                entries.scrollListBy(10000);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
                val last=entries.getChildAt(entries.childCount-1)
                assertTrue("The end of an oversized name must be scrollable into view",last.bottom<=entries.height-entries.paddingBottom)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown)
                dialog.cancel();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            }
            File(RuntimeEnvironment.getApplication().filesDir,"saved-presets.json").delete()
        }
    }
    @Test(timeout=60000) fun buttonsStayLeftOfCompareAndOutsidePhotoAtAllLayouts() {
        for((width,height) in listOf(320 to 640,360 to 780,740 to 320)) for(scale in listOf(1f,2f)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-mdpi");RuntimeEnvironment.setFontScale(scale)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
                val activity=host.get();setup(activity,EditorState())
                val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
                val compare=button(activity,"compare");val recipe=button(activity,"recipeButton");val info=button(activity,"infoButton");val photo=field(activity,"photo") as View
                assertTrue(recipe.left>=info.right);assertTrue(recipe.right<compare.left)
                assertEquals(1,all(root).count{it.tag=="saved-recipes"})
                assertFalse(all(root).any{it.tag in listOf("load-saved-preset","save-saved-preset")})
                assertEquals("配方",recipe.contentDescription.toString())
                for(button in listOf(recipe,compare)) { assertEquals(48,button.width);assertEquals(48,button.height);assertTrue(photo.bottom+4<=button.top);assertEquals(View.VISIBLE,button.visibility) }
                val output=File("build/ui-host-renders/saved-presets-${width}x${height}-font${scale.toInt()}.png");output.parentFile.mkdirs()
                Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).let{bitmap->root.draw(Canvas(bitmap));output.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            }
        }
    }
    @Test(timeout=60000) fun scanEntryOpensCameraDirectly() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();setup(activity,EditorState())
            button(activity,"recipeButton").performClick()
            val recipes=latest()
            all(recipes.window!!.decorView).single{it.tag=="recipe-qr-scan"}.performClick()
            val intent=shadowOf(activity).nextStartedActivity
            assertEquals(dev.filmroom.presets.RecipeScannerActivity::class.java.name,intent.component!!.className)
            assertFalse(recipes.isShowing)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
    @Test(timeout=60000) fun scanReturnsToEditorNameDialogAndSaveReopensListWithoutApplying() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val original=EditorState(brightness=.2f);val(_,flow)=setup(activity,original)
            button(activity,"recipeButton").performClick()
            val recipes=latest()
            all(recipes.window!!.decorView).single{it.tag=="recipe-qr-scan"}.performClick()
            val launch=shadowOf(activity).nextStartedActivityForResult
            assertEquals(dev.filmroom.presets.RecipeScannerActivity::class.java.name,launch.intent.component!!.className)
            // Robolectric tracks for-result and ordinary launch queues separately.
            // Consume the already asserted scanner launch before checking no later screen opens.
            assertEquals(launch.intent.component,shadowOf(activity).nextStartedActivity.component)
            val payload=PresetQrCodec.encode("Scanned",EditorState(brightness=.8f))
            val correlation = dev.filmroom.presets.RecipeCorrelation.next()
            activity.activityResultRegistry.dispatchResult(launch.requestCode,android.app.Activity.RESULT_OK,
                Intent().putExtra(dev.filmroom.presets.RecipeScannerActivity.EXTRA_QR_TEXT,payload)
                    .putExtra(dev.filmroom.presets.RecipeScannerActivity.EXTRA_CORRELATION, correlation))
            await{latest()!==recipes && latest().isShowing}
            val naming=latest();assertEquals("recipe-import-name",input(naming).tag)
            assertEquals("Scanned",input(naming).text.toString())
            assertTrue(SavedPresetStore(activity).list().isEmpty())
            input(naming).setText("Imported")
            naming.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            naming.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await{SavedPresetStore(activity).list().size==1 && latest()!==naming && latest().isShowing}
            assertTrue(all(latest().window!!.decorView).any{it.tag=="saved-preset-list"})
            assertEquals(.8f,SavedPresetStore(activity).list().single().parameters().brightness)
            assertEquals(correlation, dev.filmroom.presets.RecipeCorrelation.forApply(SavedPresetStore(activity).list().single().id))
            assertEquals(original,flow.value.parameters)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
    @Test(timeout=60000) fun scannedNamingCancelDoesNotSaveOrApply() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val original=EditorState(brightness=.2f);val(_,flow)=setup(activity,original)
            button(activity,"recipeButton").performClick();val recipes=latest()
            all(recipes.window!!.decorView).single{it.tag=="recipe-qr-scan"}.performClick()
            val launch=shadowOf(activity).nextStartedActivityForResult
            activity.activityResultRegistry.dispatchResult(launch.requestCode,android.app.Activity.RESULT_OK,
                Intent().putExtra(dev.filmroom.presets.RecipeScannerActivity.EXTRA_QR_TEXT,PresetQrCodec.encode("Cancel",EditorState())))
            await{latest()!==recipes && latest().isShowing}
            latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(SavedPresetStore(activity).list().isEmpty());assertEquals(original,flow.value.parameters)
        }
    }
    @Test(timeout=60000) fun longPressSharesInModalAndOnlyShareImageOpensChooser() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val parameters=EditorState(brightness=.7f);val(_,flow)=setup(activity,parameters)
            val store=SavedPresetStore(activity);val item=store.save("暖调日光",parameters)
            button(activity,"recipeButton").performClick();val recipes=latest();choose(recipes)
            render(recipes,"recipes-list-zh")
            val scan=all(recipes.window!!.decorView).filterIsInstance<ImageButton>().single()
            assertEquals("recipe-qr-scan",scan.tag);assertNull(scan.background);assertEquals(48,scan.width);assertEquals(48,scan.height)
            val rows=list(recipes);await{rows.childCount>0}
            val row=rows.getChildAt(0)
            val node=row.createAccessibilityNodeInfo()
            assertTrue(node.actionList.any{it.id==R.id.recipe_action_share});assertTrue(node.actionList.any{it.id==R.id.recipe_action_delete})
            action(recipes,R.id.recipe_action_share)
            val modal=latest();assertNotSame(recipes,modal);assertTrue(recipes.isShowing)
            await{modal.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled}
            assertNull(shadowOf(activity).nextStartedActivity)
            val qr=all(modal.window!!.decorView).filterIsInstance<ImageView>().single{it.tag=="recipe-share-qr"}
            val image=(qr.drawable as android.graphics.drawable.BitmapDrawable).bitmap
            assertEquals(item.parameters(),PresetQrCodec.decode(PresetQrImage.decode(image)).parameters)
            assertFalse((qr.drawable as android.graphics.drawable.BitmapDrawable).paint.isFilterBitmap)
            render(modal,"recipe-share-modal-zh")
            modal.getButton(AlertDialog.BUTTON_POSITIVE).performClick();modal.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            var chooser:Intent?=null;await{chooser=shadowOf(activity).nextStartedActivity;chooser!=null}
            assertEquals(Intent.ACTION_CHOOSER,chooser!!.action)
            val send=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val uri=send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("image/png",send.type);assertEquals((dev.filmroom.BuildConfig.APPLICATION_ID + ".presetqr"),uri.authority)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0);assertEquals(uri,send.clipData!!.getItemAt(0).uri)
            assertEquals(item.parameters(),PresetQrCodec.decode(PresetQrImage.read(activity,uri)).parameters)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(parameters,flow.value.parameters);assertEquals(listOf(item),store.list())
            modal.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();await{!modal.isShowing};assertTrue(recipes.isShowing)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            scan.performClick()
            val intent=shadowOf(activity).nextStartedActivity
            assertEquals(dev.filmroom.presets.RecipeScannerActivity::class.java.name,intent.component!!.className)
            assertFalse(intent.hasExtra(PresetQrActivity.EXTRA_PRESET_ID));assertFalse(recipes.isShowing)
        }
    }
    @Test(timeout=60000) fun sharingModalRestoresOnRotationAndDismissedGenerationDoesNotNavigate() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            var activity=host.get();val(_,flow)=setup(activity,EditorState())
            SavedPresetStore(activity).save("旋转测试",EditorState())
            button(activity,"recipeButton").performClick();val recipes=latest();choose(recipes)
            action(recipes,R.id.recipe_action_share);val first=latest();assertTrue(first.isShowing)
            host.recreate();activity=host.get()
            await{latest()!==first && latest().isShowing && all(latest().window!!.decorView).any{it.tag=="recipe-share-qr"}}
            val modal=latest();await{modal.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled}
            assertNull(shadowOf(activity).nextStartedActivity);assertFalse(flow.value.canUndo)
            modal.cancel();await{!modal.isShowing}
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            val list=org.robolectric.shadows.ShadowDialog.getShownDialogs().filterIsInstance<AlertDialog>().single{it.isShowing && all(it.window!!.decorView).any{view->view is ListView}}
            choose(list)
            action(list,R.id.recipe_action_share);latest().cancel()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            assertNull(shadowOf(activity).nextStartedActivity);assertEquals(1,SavedPresetStore(activity).list().size)
        }
    }

    @Test(timeout=30000) fun noFilmFramedRecipeRoundTripsButCannotPolluteEditorOrSession() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity=host.get();val before=EditorState(brightness=.5f)
            val(vm,flow)=setup(activity,before)
            val session=activity.getSharedPreferences("local-session",0)
            session.edit().putString("parameters",before.json()).commit()
            val sessionBefore=session.all.toMap()
            val history=EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}
            val historyBefore=history.get(vm)
            val uiBefore=flow.value
            val store=SavedPresetStore(activity)
            var previousDialog: AlertDialog? = null
            for(stage in 0..1) {
                val bad=before.copy(filmStock="none",paperStock="none",filmBorder=true,filmBorderStage=stage,
                    composition=dev.filmroom.model.CompositionTransform(.55,.45,1.3))
                assertEquals(bad,EditorState.fromJson(bad.json()))
                assertEquals(bad,dev.filmroom.presets.PresetQrCodec.decode(
                    dev.filmroom.presets.PresetQrCodec.encode("No film",bad)).parameters)
                val saved=store.save("No film $stage",bad)
                val document=dev.filmroom.backup.AppBackupCodec.Document(org.json.JSONObject(),listOf(saved),org.json.JSONArray())
                val restored=dev.filmroom.backup.AppBackupCodec.read(
                    dev.filmroom.backup.AppBackupCodec.encode(document).inputStream())
                assertEquals(bad,restored.recipes.single().parameters())
                try {
                    vm.applySavedPreset(flow.value.photo!!.cacheKey,before,bad)
                    fail("Expected incompatible recipe")
                } catch(error:SavedPresetStore.Failure) {
                    assertEquals(SavedPresetStore.Problem.INCOMPATIBLE,error.problem)
                }
                assertEquals(uiBefore,flow.value);assertSame(historyBefore,history.get(vm))
                assertEquals(sessionBefore,session.all)
                button(activity,"recipeButton").performClick();val dialog=latest()
                assertTrue("Each stage must open a live recipe dialog",dialog.isShowing)
                previousDialog?.let { assertNotSame("Do not reuse the dismissed stage dialog",it,dialog) }
                choose(dialog)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                await { all(dialog.window!!.decorView).filterIsInstance<TextView>().any {
                    it.text.toString()==dev.filmroom.i18n.L10n.text(R.string.saved_preset_incompatible) } }
                assertTrue(dialog.isShowing);assertEquals(uiBefore,flow.value)
                assertSame(historyBefore,history.get(vm));assertEquals(sessionBefore,session.all)
                dialog.cancel()
                // AlertDialog posts OnDismiss; let track() clear its reference before reopening.
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(dialog.isShowing)
                previousDialog=dialog
                store.delete(saved.id)
            }
            vm.cancel()
        }
    }

    @Test(timeout=30000) fun validNoneAndFramedStagesApplyWithoutNormalization() {
        for(next in listOf(EditorState(filmStock="none",filmBorder=false),
            EditorState(filmBorder=true,filmBorderStage=0),EditorState(filmBorder=true,filmBorderStage=1))) {
            Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
                val before=EditorState(brightness=.5f)
                val(vm,flow)=setup(host.get(),before)
                assertTrue(vm.applySavedPreset(flow.value.photo!!.cacheKey,before,next))
                assertEquals(next,flow.value.parameters);assertTrue(flow.value.canUndo)
                assertEquals(next,EditorState.fromJson(host.get().getSharedPreferences("local-session",0)
                    .getString("parameters",null)!!))
                // This test exercises adoption, not the JNI render; stop before scheduled work.
                vm.cancel()
            }
        }
    }
}
