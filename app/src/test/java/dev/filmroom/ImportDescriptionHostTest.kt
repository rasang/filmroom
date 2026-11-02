package dev.filmroom

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.i18n.L10n
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** The actual import chooser label changes; document-picker contract stays unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ImportDescriptionHostTest {
    @Test fun fourLocalesShowPhotoRawDescriptionAndKeepDocumentPickerMimeContract() {
        val app=RuntimeEnvironment.getApplication()
        val expected=mapOf("zh" to "从文件选择照片/RAW","en" to "Choose a photo/RAW from files",
            "ja" to "ファイルから写真／RAWを選択","ko" to "파일에서 사진/RAW 선택")
        try { for((tag,label) in expected) {
            resetAndroidViewModelFactoryForHostTest();L10n.choose(app,tag)
            app.getSharedPreferences("local-session",0).edit().clear().commit()
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity=controller.get();val model=ViewModelProvider(activity)[EditorViewModel::class.java]
                val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
                val deadline=System.nanoTime()+10_000_000_000L
                while((operation.get(model) as? Job)?.isCompleted==false && System.nanoTime()<deadline) {
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));Thread.sleep(5)
                }
                assertTrue(model.state.value.startupReady);assertNull(model.state.value.busy)
                MainActivity::class.java.getDeclaredMethod("importDialog").apply{isAccessible=true}.invoke(activity)
                val dialog=ShadowAlertDialog.getLatestAlertDialog()
                assertTrue(dialog.isShowing);assertEquals(2,dialog.listView.adapter.count)
                assertEquals(label,dialog.listView.adapter.getItem(1))
                assertEquals(L10n.text(R.string.text_543b8fb9aa67),dialog.listView.adapter.getItem(0))
                dialog.listView.performItemClick(null,1,1)
                val intent=shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
                assertArrayEquals(arrayOf("image/*","application/octet-stream"),intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                assertNull(model.state.value.photo)
            }
        }}finally{L10n.choose(app,"zh")}
    }
}
