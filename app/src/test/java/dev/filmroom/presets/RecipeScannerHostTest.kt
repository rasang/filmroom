package dev.filmroom.presets

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.result.ActivityResult
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeScannerHostTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))} else emptyList()
    private fun denyCamera(a:RecipeScannerActivity) {
        val request=shadowOf(a).nextStartedActivityForResult
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",request.intent.action)
        a.activityResultRegistry.dispatchResult(request.requestCode,Activity.RESULT_OK,android.content.Intent()
            .putExtra("androidx.activity.result.contract.extra.PERMISSIONS",arrayOf(android.Manifest.permission.CAMERA))
            .putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED)))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun deniedCameraStillOffersAlbumAndCancelResumesWithNoSecondPermissionPrompt() {
        L10n.choose(RuntimeEnvironment.getApplication(),"zh")
        Robolectric.buildActivity(RecipeScannerActivity::class.java).setup().use { host ->
            val a=host.get();denyCamera(a); val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)
            val album=all(root).single{it.tag=="recipe-camera-album"} as Button
            assertTrue(album.isEnabled);assertTrue(album.height>=48);assertTrue(album.left<180);assertTrue(album.top>390)
            assertFalse(album.contentDescription.isNullOrBlank())
            val image=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888);root.draw(Canvas(image))
            val output=File("build/ui-host-renders/recipe-camera-denied.png");output.parentFile!!.mkdirs();output.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            album.performClick();assertFalse(album.isEnabled)
            val launch=shadowOf(a).nextStartedActivityForResult
            assertNotNull(launch)
            album.performClick();assertNull(shadowOf(a).nextStartedActivityForResult)
            a.activityResultRegistry.dispatchResult(launch.requestCode,Activity.RESULT_CANCELED,null)
            shadowOf(Looper.getMainLooper()).idle();assertTrue(album.isEnabled);assertFalse(a.isFinishing)
            host.pause().stop().start().resume();assertTrue(album.isEnabled)
        }
    }
    @Test fun albumSelectionReturnsUriAndFinishes() {
        val token = RecipeCorrelation.next()
        val intent = android.content.Intent(RuntimeEnvironment.getApplication(), RecipeScannerActivity::class.java)
            .putExtra(RecipeScannerActivity.EXTRA_CORRELATION, token)
        Robolectric.buildActivity(RecipeScannerActivity::class.java, intent).setup().use { host ->
            val a=host.get();denyCamera(a); val root=a.findViewById<ViewGroup>(android.R.id.content)
            all(root).single{it.tag=="recipe-camera-album"}.performClick()
            val launch=shadowOf(a).nextStartedActivityForResult
            val uri=android.net.Uri.parse("content://fixture/recipe.png")
            a.activityResultRegistry.dispatchResult(launch.requestCode,Activity.RESULT_OK,android.content.Intent().setData(uri))
            assertTrue(a.isFinishing);assertEquals(Activity.RESULT_OK,shadowOf(a).resultCode);assertEquals(uri,shadowOf(a).resultIntent.data)
            assertEquals(token, shadowOf(a).resultIntent.getLongExtra(RecipeScannerActivity.EXTRA_CORRELATION, 0))
        }
    }
    @Test fun recreationKeepsAnonymousTokenForScannerLaunchedWithoutExtra() {
        Robolectric.buildActivity(RecipeScannerActivity::class.java).setup().use { host ->
            val before = host.get(); denyCamera(before)
            val saved = android.os.Bundle()
            host.saveInstanceState(saved)
            val token = saved.getLong(RecipeScannerActivity.EXTRA_CORRELATION)
            assertTrue(token > 0)
            host.recreate()
            val restored = host.get()
            all(restored.findViewById<ViewGroup>(android.R.id.content)).single { it.tag == "recipe-camera-album" }.performClick()
            val launch = shadowOf(restored).nextStartedActivityForResult
            restored.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK,
                android.content.Intent().setData(android.net.Uri.parse("content://fixture/recipe.png")))
            assertEquals(token, shadowOf(restored).resultIntent.getLongExtra(RecipeScannerActivity.EXTRA_CORRELATION, 0))
        }
    }
    @Test fun recreationDoesNotLaunchAlbumTwice() {
        Robolectric.buildActivity(RecipeScannerActivity::class.java).setup().use { host ->
            val a=host.get();denyCamera(a);all(a.findViewById<ViewGroup>(android.R.id.content)).single{it.tag=="recipe-camera-album"}.performClick()
            assertNotNull(shadowOf(a).nextStartedActivityForResult)
            host.recreate()
            val restored=host.get();val album=all(restored.findViewById<ViewGroup>(android.R.id.content)).single{it.tag=="recipe-camera-album"}
            assertFalse(album.isEnabled);assertNull(shadowOf(restored).nextStartedActivityForResult)
        }
    }
}
