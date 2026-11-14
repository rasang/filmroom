package dev.filmroom

import android.content.ComponentName
import android.content.pm.PackageManager
import dev.filmroom.ui.DiagnosticFileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], application=FilmroomApplication::class)
class OfficialReleaseIdentityHostTest {
    private val app get()=RuntimeEnvironment.getApplication() as FilmroomApplication

    @Test fun applicationAndGeneratedMetadataUseOfficialUpgradeIdentity() {
        assertEquals("dev.filmroom",app.packageName)
        assertEquals("dev.filmroom",BuildConfig.APPLICATION_ID)
        assertEquals(113,BuildConfig.VERSION_CODE)
        assertEquals("0.1.22.2-beta9",BuildConfig.VERSION_NAME)
    }

    @Test fun manifestVersionAndApplicationLabelMatchOfficialRelease() {
        val info=app.packageManager.getPackageInfo(app.packageName,0)
        assertEquals(113L,info.longVersionCode)
        assertEquals("0.1.22.2-beta9",info.versionName)
        assertEquals("Filmroom",app.getString(R.string.app_name))
        assertEquals("Filmroom",app.applicationInfo.loadLabel(app.packageManager).toString())
    }

    @Test fun diagnosticProviderKeepsPrivateOfficialAuthority() {
        val provider=app.packageManager.getProviderInfo(ComponentName(app,DiagnosticFileProvider::class.java),0)
        assertEquals("dev.filmroom.diagnostics",provider.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val providers=app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue(providers.none{it.authority.orEmpty().contains(".perf")})
    }

    @Test fun launcherQueueAndExportServiceKeepTheirExistingExposure() {
        val pm=app.packageManager
        assertTrue(pm.getActivityInfo(ComponentName(app,MainActivity::class.java),0).exported)
        assertFalse(pm.getActivityInfo(ComponentName(app,ExportQueueActivity::class.java),0).exported)
        assertFalse(pm.getServiceInfo(ComponentName(app,dev.filmroom.export.ExportService::class.java),0).exported)
        val requested=pm.getPackageInfo(app.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.FOREGROUND_SERVICE" in requested)
        assertTrue("android.permission.INTERNET" in requested)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in requested)
    }
}
