package dev.filmroom.presets

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import dev.filmroom.model.EditorState
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetQrUiHostTest {
    @Before fun reset() {
        // Robolectric gives every test a different private root; AndroidX caches provider paths.
        val providerCache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply{isAccessible=true}
        (providerCache.get(null) as MutableMap<*,*>).clear()
        dev.filmroom.resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        L10n.choose(app,"zh")
        File(app.filesDir,"saved-presets.json").delete(); File(app.filesDir,"saved-presets.json.bak").delete()
    }
    private fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun button(activity: PresetQrActivity,id:Int) = all(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString()==L10n.text(id) }
    private fun field(activity: PresetQrActivity) = all(activity.window.decorView).filterIsInstance<EditText>().single()
    private fun await(done:()->Boolean) {
        val deadline=System.nanoTime()+10_000_000_000L
        do { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32)); Thread.sleep(5); if(done())return } while(System.nanoTime()<deadline)
        fail("Timed out")
    }
    @Test fun importRequiresConfirmationDuplicateDoesNotOverwriteAndCancelDoesNotSave() {
        val app=RuntimeEnvironment.getApplication(); val store=SavedPresetStore(app)
        val existing=store.save("Golden",EditorState(brightness=.2f))
        val payload=PresetQrCodec.encode("Golden",EditorState(brightness=.8f))
        Robolectric.buildActivity(PresetQrActivity::class.java).setup().use { host ->
            val activity=host.get();val model=ViewModelProvider(activity)[PresetQrViewModel::class.java]
            model.read(payload);await { model.state.value.phase=="confirm" }
            assertEquals(listOf(existing),store.list())
            button(activity,R.string.preset_qr_save_new).performClick()
            await { model.state.value.error!=null }
            assertEquals(listOf(existing),store.list())
            field(activity).setText("Imported")
            button(activity,R.string.preset_qr_save_new).performClick();button(activity,R.string.preset_qr_save_new).performClick()
            await { model.state.value.phase=="saved" }
            assertEquals(2,store.list().size);assertEquals(.8f,store.list().single { it.name=="Imported" }.parameters().brightness)
            assertEquals(existing,store.list().single { it.name=="Golden" })
        }
        Robolectric.buildActivity(PresetQrActivity::class.java).setup().use { host ->
            val activity=host.get();val model=ViewModelProvider(activity)[PresetQrViewModel::class.java]
            model.read(PresetQrCodec.encode("Cancelled",EditorState()));await { model.state.value.phase=="confirm" }
            button(activity,R.string.saved_preset_cancel).performClick()
            assertEquals(2,store.list().size)
        }
    }
    @Test fun corruptAndUnknownStockNeverChangeStore() {
        val store=SavedPresetStore(RuntimeEnvironment.getApplication());val existing=store.save("Keep",EditorState())
        Robolectric.buildActivity(PresetQrActivity::class.java).setup().use { host ->
            val model=ViewModelProvider(host.get())[PresetQrViewModel::class.java]
            model.read("https://example.com/");await { model.state.value.error!=null }
            assertEquals(listOf(existing),store.list())
            model.read(PresetQrCodec.encode("Unknown",EditorState(filmStock="unknown-film")));await { model.state.value.error is PresetQrCodec.Failure }
            assertEquals(listOf(existing),store.list());assertEquals("home",model.state.value.phase)
        }
    }
    @Test fun rotationPreservesConfirmationNameWithoutSaving() {
        val host=Robolectric.buildActivity(PresetQrActivity::class.java).setup()
        try {
            var activity=host.get();val model=ViewModelProvider(activity)[PresetQrViewModel::class.java]
            model.read(PresetQrCodec.encode("Original",EditorState(brightness=.5f)));await { model.state.value.phase=="confirm" }
            field(activity).setText("Renamed draft")
            host.recreate();activity=host.get();await { field(activity).visibility==View.VISIBLE }
            assertEquals("Renamed draft",field(activity).text.toString())
            assertTrue(SavedPresetStore(activity).list().isEmpty())
        } finally { host.close() }
    }
    @Test fun recreatedProcessRestoresOnlyUnconfirmedDraftNotAWrite() {
        val saved=android.os.Bundle()
        val first=Robolectric.buildActivity(PresetQrActivity::class.java).setup()
        val model=ViewModelProvider(first.get())[PresetQrViewModel::class.java]
        model.read(PresetQrCodec.encode("Original",EditorState(brightness=.4f)));await { model.state.value.phase=="confirm" }
        field(first.get()).setText("After restart")
        first.saveInstanceState(saved).pause().stop().destroy()
        Robolectric.buildActivity(PresetQrActivity::class.java).create(saved).start().restoreInstanceState(saved).resume().visible().use { second ->
            val restored=ViewModelProvider(second.get())[PresetQrViewModel::class.java]
            await { restored.state.value.phase=="confirm" }
            assertEquals("After restart",field(second.get()).text.toString())
            assertTrue(SavedPresetStore(second.get()).list().isEmpty())
        }
    }
    @Test fun imageRoundTripAndShareContainsOnlyGeneratedPng() {
        val app=RuntimeEnvironment.getApplication();val store=SavedPresetStore(app)
        val item=store.save("Image share",EditorState(grain=.45f))
        val intent=Intent(app,PresetQrActivity::class.java).putExtra(PresetQrActivity.EXTRA_PRESET_ID,item.id)
        Robolectric.buildActivity(PresetQrActivity::class.java,intent).setup().use { host ->
            val activity=host.get();val model=ViewModelProvider(activity)[PresetQrViewModel::class.java]
            await { model.state.value.phase=="share" }
            val screen=model.state.value
            assertEquals(item.parameters(),PresetQrCodec.decode(PresetQrImage.decode(checkNotNull(screen.bitmap))).parameters)
            await { button(activity,R.string.preset_qr_share_image).isEnabled }
            button(activity,R.string.preset_qr_share_image).performClick()
            var chooser:Intent?=null; await { chooser=shadowOf(activity).nextStartedActivity;chooser!=null }
            assertEquals(Intent.ACTION_CHOOSER,chooser!!.action)
            val send=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("image/png",send.type)
            val uri=send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("dev.filmroom.presetqr",uri.authority)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(uri,send.clipData!!.getItemAt(0).uri)
            assertEquals(item.parameters(),PresetQrCodec.decode(PresetQrImage.read(activity,uri)).parameters)
            assertEquals(listOf(item),store.list())
            try { androidx.core.content.FileProvider.getUriForFile(activity,"dev.filmroom.presetqr",File(activity.filesDir,"saved-presets.json"));fail("private store must never be exposed") } catch (_:IllegalArgumentException) { }
        }
    }
    @Test fun scannerLocksItsOrientationAndCancelLeavesImportOrientationAlone() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        Robolectric.buildActivity(PresetQrActivity::class.java).setup().use { host ->
            val activity=host.get(); val before=activity.requestedOrientation
            button(activity,R.string.preset_qr_scan).performClick()
            val scan=shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals("com.journeyapps.barcodescanner.CaptureActivity",scan.component?.className)
            assertTrue(scan.getBooleanExtra("SCAN_ORIENTATION_LOCKED",false))
            shadowOf(activity).receiveResult(scan,android.app.Activity.RESULT_CANCELED,null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before,activity.requestedOrientation)
            assertTrue(button(activity,R.string.preset_qr_album).isEnabled)
            assertTrue(SavedPresetStore(activity).list().isEmpty())
        }
    }
    @Test fun cameraIsOptInAndPhotoPickerRemainsAvailable() {
        Robolectric.buildActivity(PresetQrActivity::class.java).setup().use { host ->
            val activity=host.get()
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,activity.checkSelfPermission(Manifest.permission.CAMERA))
            assertEquals(View.VISIBLE,button(activity,R.string.preset_qr_album).visibility)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(shadowOf(activity).lastRequestedPermission)
            button(activity,R.string.preset_qr_scan).performClick()
            val permissionIntent=shadowOf(activity).nextStartedActivityForResult
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",permissionIntent.intent.action)
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",shadowOf(activity).nextStartedActivity.action)
            val permission=shadowOf(activity).lastRequestedPermission
            assertArrayEquals(arrayOf(Manifest.permission.CAMERA),permission.requestedPermissions)
            activity.onRequestPermissionsResult(permission.requestCode,permission.requestedPermissions,intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(View.VISIBLE,button(activity,R.string.preset_qr_album).visibility)
            button(activity,R.string.preset_qr_album).performClick()
            val picker=shadowOf(activity).nextStartedActivityForResult
            assertNotNull(picker);assertEquals("image/*",picker.intent.type)
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,activity.checkSelfPermission(Manifest.permission.CAMERA))
        }
    }
    @Test fun confirmationAndShareRenderAtLargeFontWithoutHiddenActions() {
        val dir=File(System.getProperty("filmroom.qr.renders",System.getProperty("java.io.tmpdir")),"qr-renders").apply { mkdirs() }
        for (language in listOf("zh","en","ja","ko")) {
            L10n.choose(RuntimeEnvironment.getApplication(),language);RuntimeEnvironment.setFontScale(2f)
            Robolectric.buildActivity(PresetQrActivity::class.java).setup().use { host ->
                val activity=host.get();val model=ViewModelProvider(activity)[PresetQrViewModel::class.java]
                model.read(PresetQrCodec.encode("長い名前 Golden 光影の旅 🌅",EditorState()));await { model.state.value.phase=="confirm" }
                val root=activity.window.decorView;root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)
                assertTrue(button(activity,R.string.preset_qr_save_new).height>=48)
                val bitmap=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap));File(dir,"confirm-$language.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        }
        RuntimeEnvironment.setFontScale(1f)
    }
}
