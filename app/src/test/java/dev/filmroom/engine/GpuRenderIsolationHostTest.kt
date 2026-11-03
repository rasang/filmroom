package dev.filmroom.engine

import android.content.ComponentName
import android.content.pm.PackageManager
import dev.filmroom.BuildConfig
import dev.filmroom.FilmroomApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

class GpuRenderHelperTestApplication : FilmroomApplication() {
    override fun applicationProcessName(): String = packageName + GpuProbeProcess.RENDER_SUFFIX
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = GpuRenderHelperTestApplication::class)
class GpuRenderIsolationHostTest {
    @Test fun fullRenderHelperSkipsMainRecoveryAndDiagnostics() {
        val app = RuntimeEnvironment.getApplication() as GpuRenderHelperTestApplication
        assertTrue(runCatching { app.recoveryGate }.isFailure)
        val active = File(app.cacheDir, "original-input-12345678-1234-1234-1234-123456789abc.rgb").apply { writeText("active") }
        app.onCreate(); assertEquals("active", active.readText())
        assertFalse(File(app.filesDir, "diagnostics").exists()); active.delete()
    }
    @Test fun fullRenderServiceIsPrivateDisabledByDefaultAndHasDistinctExactIdentity() {
        val app = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val info = app.packageManager.getServiceInfo(ComponentName(app, GpuRenderService::class.java), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(info.exported); assertEquals(BuildConfig.PERF_BUILD, info.enabled)
        assertEquals(app.packageName + ":gpu_render", info.processName)
        assertTrue(GpuProbeProcess.isWorker(app.packageName, info.processName))
        assertFalse(GpuProbeProcess.isHelper(app.packageName, info.processName))
        assertFalse(GpuProbeProcess.isWorker(app.packageName, app.packageName + ":gpu_render_extra"))
    }
}
