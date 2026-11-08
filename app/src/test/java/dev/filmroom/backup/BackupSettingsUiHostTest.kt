package dev.filmroom.backup

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.filmroom.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class BackupSettingsUiHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    @Test fun exportUsesCreateDocumentWithoutBroadStorageAccess() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity=controller.get()
            (all(activity.findViewById(android.R.id.content)).single{it.tag=="backup-export"} as Button).performClick()
            val intent=shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action)
            assertEquals("application/zip",intent.type)
            assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("Filmroom-backup-"))
            assertNull(intent.data)
        }
    }
    @Test fun restoreShowsPreviewAndCancelDoesNotWrite() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity=controller.get()
            val prefs=activity.getSharedPreferences("editor-preferences",0)
            prefs.edit().putBoolean("reset-on-import",false).commit()
            val model=androidx.lifecycle.ViewModelProvider(activity)[AppBackupViewModel::class.java]
            val field=AppBackupViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST")
            val state=field.get(model) as kotlinx.coroutines.flow.MutableStateFlow<AppBackupViewModel.State>
            state.value=AppBackupViewModel.State.Preview(AppBackupCodec.Document(
                org.json.JSONObject().put("resetOnImport",true),emptyList(),org.json.JSONArray()))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            assertTrue(org.robolectric.Shadows.shadowOf(dialog).message.toString().contains("1"))
            assertFalse(prefs.getBoolean("reset-on-import",true))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(prefs.getBoolean("reset-on-import",true))
            assertEquals(AppBackupViewModel.State.Idle,model.state.value)
        }
    }
    @Test fun importUsesSystemOpenDocument() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity=controller.get()
            (all(activity.findViewById(android.R.id.content)).single{it.tag=="backup-import"} as Button).performClick()
            val intent=shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertNull(intent.data)
        }
    }
    private fun button(activity: SettingsActivity, tag: String): Button =
        all(activity.findViewById(android.R.id.content)).single { it.tag == tag } as Button

    @Test fun pendingPickerRejectsRepeatedAndOppositeActionClicks() {
        for (tag in listOf("backup-export", "backup-import")) {
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use { host ->
                val activity = host.get()
                button(activity, tag).performClick()
                val first = shadowOf(activity).nextStartedActivityForResult
                assertNotNull(first)
                assertFalse(button(activity, "backup-export").isEnabled)
                assertFalse(button(activity, "backup-import").isEnabled)
                // performClick also exercises the listener's explicit guard, not just touch disabling.
                button(activity, "backup-export").performClick()
                button(activity, "backup-import").performClick()
                assertNull(shadowOf(activity).nextStartedActivityForResult)
                assertEquals(AppBackupViewModel.State.Idle,
                    androidx.lifecycle.ViewModelProvider(activity)[AppBackupViewModel::class.java].state.value)
            }
        }
    }

    @Test fun cancelledPickerReenablesBothActionsAndAllowsAnotherLaunch() {
        for (tag in listOf("backup-export", "backup-import")) {
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use { host ->
                val activity = host.get()
                button(activity, tag).performClick()
                val first = shadowOf(activity).nextStartedActivityForResult
                activity.activityResultRegistry.dispatchResult(first.requestCode, android.app.Activity.RESULT_CANCELED, null)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(button(activity, "backup-export").isEnabled)
                assertTrue(button(activity, "backup-import").isEnabled)
                assertEquals(AppBackupViewModel.State.Idle,
                    androidx.lifecycle.ViewModelProvider(activity)[AppBackupViewModel::class.java].state.value)
                button(activity, tag).performClick()
                assertNotNull(shadowOf(activity).nextStartedActivityForResult)
            }
        }
    }

    @Test fun recreationKeepsPickerPendingUntilItsCancelledResult() {
        for (tag in listOf("backup-export", "backup-import")) {
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use { host ->
                button(host.get(), tag).performClick()
                val first = shadowOf(host.get()).nextStartedActivityForResult
                host.recreate()
                val restored = host.get()
                assertFalse(button(restored, "backup-export").isEnabled)
                assertFalse(button(restored, "backup-import").isEnabled)
                button(restored, tag).performClick()
                assertNull(shadowOf(restored).nextStartedActivityForResult)
                restored.activityResultRegistry.dispatchResult(first.requestCode, android.app.Activity.RESULT_CANCELED, null)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(button(restored, "backup-export").isEnabled)
                assertTrue(button(restored, "backup-import").isEnabled)
                button(restored, tag).performClick()
                assertNotNull(shadowOf(restored).nextStartedActivityForResult)
            }
        }
    }

    @Test fun launchFailureReleasesPendingWithoutStartingBackupWork() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { host ->
            val activity = host.get()
            val ui = SettingsActivity::class.java.getDeclaredField("backupUi").apply { isAccessible = true }
                .get(activity) as BackupSettingsUi
            ui.launchPicker {
                assertFalse(button(activity, "backup-export").isEnabled)
                assertFalse(button(activity, "backup-import").isEnabled)
                throw android.content.ActivityNotFoundException("test picker unavailable")
            }
            assertTrue(button(activity, "backup-export").isEnabled)
            assertTrue(button(activity, "backup-import").isEnabled)
            assertEquals(AppBackupViewModel.State.Idle,
                androidx.lifecycle.ViewModelProvider(activity)[AppBackupViewModel::class.java].state.value)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            button(activity, "backup-import").performClick()
            assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        }
    }
}
