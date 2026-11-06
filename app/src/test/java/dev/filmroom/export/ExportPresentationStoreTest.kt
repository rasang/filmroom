package dev.filmroom.export

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportPresentationStoreTest {
    @Test fun pendingPresentationIsSeparateAttemptScopedAndBounded() {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("export-preview-presentations",Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ExportPresentationStore.remember(app,"job","attempt-one",true)
        assertTrue(ExportPresentationStore.pending(app,"job","attempt-one"))
        assertFalse(ExportPresentationStore.pending(app,"job","attempt-two"))
        ExportPresentationStore.clear(app,"job")
        assertFalse(ExportPresentationStore.pending(app,"job","attempt-one"))
        repeat(300){ExportPresentationStore.remember(app,"job-$it","attempt-$it",true)}
        assertTrue(prefs.all.size<=256)
        assertTrue(ExportPresentationStore.pending(app,"job-299","attempt-299"))
        ExportPresentationStore.remember(app,"job-299","attempt-299",false)
        assertFalse(ExportPresentationStore.pending(app,"job-299","attempt-299"))
    }
}
