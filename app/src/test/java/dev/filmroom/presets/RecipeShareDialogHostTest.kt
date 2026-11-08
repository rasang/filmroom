package dev.filmroom.presets

import android.app.AlertDialog
import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import dev.filmroom.R
import dev.filmroom.i18n.L10n
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
class RecipeShareDialogHostTest {
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun await(done:()->Boolean) {
        repeat(1000){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));if(done())return;Thread.sleep(5)}
        fail("QR modal did not become ready")
    }
    private fun write(name:String,image:Bitmap) {
        val dir=File("build/ui-host-renders").apply{mkdirs()}
        File(dir,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test(timeout=60000) fun actualModalImagePixelsDecodeDefaultAndFull89FieldRecipes() {
        val app=RuntimeEnvironment.getApplication();L10n.choose(app,"zh")
        for(index in listOf(0,6)) {
            val(name,parameters)=PresetQrCompactHostTest().samples()[index]
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
                val activity=host.get();activity.setTheme(R.style.AppTheme)
                var closed=false
                val presenter=PresetQrShareDialog(activity,{it.show()},{closed=true})
                presenter.show(SavedPresetStore.Preset("fixture-$index",name,0,SavedPresetStore.snapshot(parameters)))
                val dialog=ShadowAlertDialog.getLatestAlertDialog()
                await{dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled}
                val share = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                assertEquals(dev.filmroom.ui.FilmroomPalette.onAccent, share.currentTextColor)
                assertEquals(dev.filmroom.ui.FilmroomPalette.accent,
                    ((share.background as android.graphics.drawable.InsetDrawable).drawable as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
                assertNotNull(share.compoundDrawablesRelative[0])
                assertEquals("分享二维码", share.text.toString())
                val status = all(dialog.window!!.decorView).filterIsInstance<android.widget.TextView>()
                    .single { it.tag == "recipe-share-status" }
                assertEquals("请使用 Filmroom 配方页的扫码功能扫描。", status.text.toString())
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
                val qr=all(dialog.window!!.decorView).filterIsInstance<ImageView>().single{it.tag=="recipe-share-qr"}
                assertTrue(qr.width>0 && qr.height>0);assertEquals(qr.width,qr.height)
                val pixels=Bitmap.createBitmap(qr.width,qr.height,Bitmap.Config.ARGB_8888)
                qr.draw(Canvas(pixels));write("recipe-modal-image-$index.png",pixels)
                println("RECIPE_MODAL_IMAGE sample=$index width=${qr.width} height=${qr.height}")
                assertEquals(parameters,PresetQrCodec.decode(PresetQrImage.decode(pixels)).parameters)
                val decor=dialog.window!!.decorView
                val screen=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(screen));write("recipe-modal-full-$index.png",screen)
                assertNull(shadowOf(activity).nextStartedActivity)
                presenter.close();await{closed};assertFalse(presenter.isShowing);assertNull(presenter.presetId)
                pixels.recycle();screen.recycle()
            }
        }
    }
    @Test fun scannerAloneIsPortraitInPhoneAndLargeWindowManifest() {
        val app=RuntimeEnvironment.getApplication()
        for(qualifiers in listOf("w360dp-h780dp-mdpi","w600dp-h840dp-mdpi")) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val scan=app.packageManager.getActivityInfo(ComponentName(app,"com.journeyapps.barcodescanner.CaptureActivity"),0)
            val importer=app.packageManager.getActivityInfo(ComponentName(app,PresetQrActivity::class.java),0)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,scan.screenOrientation)
            assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,importer.screenOrientation)
        }
    }
}
