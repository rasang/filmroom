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
import java.nio.file.Files

class GpuProbeHelperTestApplication : FilmroomApplication() {
    override fun applicationProcessName(): String = packageName + GpuProbeProcess.SUFFIX
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = GpuProbeHelperTestApplication::class)
class GpuProbeIsolationHostTest {
    private val context get() = (RuntimeEnvironment.getApplication() as GpuProbeHelperTestApplication)
    @Test fun helperApplicationSkipsRecoveryDiagnosticsAndFileDeletion() {
        assertTrue(runCatching { context.recoveryGate }.isFailure)
        val active = File(context.cacheDir, "original-input-12345678-1234-1234-1234-123456789abc.rgb").apply { writeText("active CPU export") }
        context.onCreate()
        assertEquals("active CPU export", active.readText())
        assertFalse(File(context.filesDir, "diagnostics").exists())
        active.delete()
    }
    @Test fun manifestIsPrivateSeparateProcessAndNormalBuildDisabled() {
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context, GpuProbeService::class.java), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(service.exported)
        assertEquals(context.packageName + ":gpu_probe", service.processName)
        assertEquals(BuildConfig.PERF_BUILD, service.enabled)
    }
    @Test fun snapshotOwnershipNeverFollowsLinksOrDeletesUnknownFiles() {
        val root = File(context.cacheDir, "gpu-probes").apply { mkdirs() }
        val dir = File(root, "probe-91").apply { mkdirs() }
        val outside = File(context.cacheDir, "original-do-not-delete").apply { writeText("original") }
        val link = File(dir, "source.f64le")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        GpuProbePaths.deleteOwned(context, 91)
        assertTrue(outside.exists()); assertTrue(Files.isSymbolicLink(link.toPath()))
        Files.delete(link.toPath())
        val unknown = File(dir, "unknown").apply { writeText("untouched") }
        GpuProbePaths.deleteOwned(context, 91)
        assertTrue(unknown.exists())
        unknown.delete(); dir.delete(); outside.delete(); root.delete()
    }
    @Test fun deletesOnlySubmittedTokenDirectoryAndItsFixedFiles() {
        val root = File(context.cacheDir, "gpu-probes").apply { mkdirs() }
        val owned = File(root, "probe-92").apply { mkdirs() }
        val other = File(root, "probe-93").apply { mkdirs() }
        listOf("manifest.json", "source.f64le", "kernel.f64le", "reference.f64le").forEach { File(owned, it).writeText("fixture") }
        File(other, "manifest.json").writeText("other owner")
        GpuProbePaths.deleteOwned(context, 92)
        assertFalse(owned.exists()); assertEquals("other owner", File(other, "manifest.json").readText())
        File(other, "manifest.json").delete(); other.delete(); root.delete()
    }
}
