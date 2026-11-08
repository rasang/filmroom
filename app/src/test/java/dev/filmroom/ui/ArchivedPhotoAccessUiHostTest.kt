package dev.filmroom.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ArchivedPhotoAccessUiHostTest {
    internal class Host:ComponentActivity() {
        lateinit var access:ArchivedPhotoAccessUi
        var linked=0
        var permissionResults=0
        override fun onCreate(state:Bundle?) {
            setTheme(dev.filmroom.R.style.AppTheme)
            super.onCreate(state)
            access=ArchivedPhotoAccessUi(this,{_,_->permissionResults++},{_,_->linked++;true})
        }
    }
    @Test fun permissionStatesDistinguishFullPartialAndDenied() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(ArchivedPhotoAccessUi.Access.DENIED,ArchivedPhotoAccessUi.currentAccess(app))
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(ArchivedPhotoAccessUi.Access.PARTIAL,ArchivedPhotoAccessUi.currentAccess(app))
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(ArchivedPhotoAccessUi.Access.GRANTED,ArchivedPhotoAccessUi.currentAccess(app))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),ArchivedPhotoAccessUi.requestedPermissions())
    }
    @Test @Config(sdk=[31]) fun olderAndroidRequestsOnlyReadExternalStorage() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),ArchivedPhotoAccessUi.requestedPermissions())
    }
    @Test fun noPromptOnConstructionAndRepeatedOfferDoesNotLaunchAnythingUntilChoice() {
        Robolectric.buildActivity(Host::class.java).setup().use { host ->
            val a=host.get()
            assertFalse(a.access.busy);assertNull(shadowOf(a).lastRequestedPermission)
            a.access.offer("../invalid");assertFalse(a.access.busy)
            a.access.offer(UUID.randomUUID().toString())
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            a.access.offer(UUID.randomUUID().toString())
            assertSame(dialog,ShadowAlertDialog.getLatestAlertDialog())
            assertNull(shadowOf(a).nextStartedActivity);assertNull(shadowOf(a).lastRequestedPermission)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(a.access.busy);assertEquals(0,a.linked);assertEquals(0,a.permissionResults)
        }
    }
    @Test fun pickedImageNeedsConfirmationAndCancelDoesNotKeepGrantOrLink() {
        Robolectric.buildActivity(Host::class.java).setup().use { host ->
            val a=host.get();a.access.offer(UUID.randomUUID().toString())
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
            val request=shadowOf(a).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,request.intent.action)
            val uri=Uri.parse("content://photos/document/selected-image")
            a.activityResultRegistry.dispatchResult(request.requestCode,Activity.RESULT_OK,Intent().setData(uri))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0,a.linked)
            assertTrue(a.contentResolver.persistedUriPermissions.none{it.uri==uri})
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(a.access.busy);assertEquals(0,a.linked)
            assertTrue(a.contentResolver.persistedUriPermissions.none{it.uri==uri})
        }
    }
    @Test fun pickerCancellationAfterRotationClearsPendingWithoutReplaying() {
        Robolectric.buildActivity(Host::class.java).setup().use { host ->
            val a=host.get();a.access.offer(UUID.randomUUID().toString())
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
            val request=shadowOf(a).nextStartedActivityForResult
            shadowOf(a).nextStartedActivity // drain the original launch from the separate started-activity queue
            host.recreate()
            val restored=host.get()
            assertTrue(restored.access.busy)
            assertNull(shadowOf(restored).nextStartedActivity)
            restored.activityResultRegistry.dispatchResult(request.requestCode,Activity.RESULT_CANCELED,null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(restored.access.busy);assertEquals(0,restored.linked)
        }
    }
}
