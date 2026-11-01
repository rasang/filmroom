package dev.filmroom

import android.app.Application
import androidx.activity.ComponentActivity
import dev.filmroom.ui.ExportNotificationPermission
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ExportNotificationPermissionHostTest {
    @Test fun decliningOptionalPermissionContinuesOnceAndNeverNagsAgain(){
        val c=Robolectric.buildActivity(ComponentActivity::class.java)
        val prompt=ExportNotificationPermission(c.get());c.setup()
        try {
            c.get().getSharedPreferences("export-notification-ui",0).edit().clear().commit()
            var continued=0
            prompt.offer{continued++}
            val dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing)
            assertEquals(0,continued)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1,continued)
            prompt.offer{continued++};assertEquals(2,continued)
            assertFalse(dialog.isShowing)
        }finally{c.close()}
    }
    @Test @Config(sdk=[29]) fun olderAndroidContinuesWithoutPermissionDialog(){
        val c=Robolectric.buildActivity(ComponentActivity::class.java)
        val prompt=ExportNotificationPermission(c.get());c.setup()
        try{var continued=false;prompt.offer{continued=true};assertTrue(continued)}finally{c.close()}
    }
}
