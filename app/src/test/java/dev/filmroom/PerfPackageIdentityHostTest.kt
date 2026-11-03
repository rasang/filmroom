package dev.filmroom

import android.content.Intent
import android.net.Uri
import dev.filmroom.export.ExportService
import dev.filmroom.ui.DiagnosticShareIntents
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Run both normally and with -PfilmroomPerf=true against their generated merged manifests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class PerfPackageIdentityHostTest {
    @Before fun attachProviderToThisTestsCacheDirectory(){attachCurrentDiagnosticProvider()}
    @Test fun applicationProviderLabelAndServiceActionUseSelectedPackage(){
        val context=RuntimeEnvironment.getApplication()
        val expected=if(BuildConfig.PERF_BUILD)"dev.filmroom.perf" else "dev.filmroom"
        assertEquals(if(BuildConfig.PERF_BUILD)37 else 28,BuildConfig.VERSION_CODE)
        assertEquals(if(BuildConfig.PERF_BUILD)"0.1.19-opt.7.1" else "0.1.19",BuildConfig.VERSION_NAME)
        val installed=context.packageManager.getPackageInfo(expected,0)
        assertEquals(BuildConfig.VERSION_CODE.toLong(),installed.longVersionCode)
        assertEquals(BuildConfig.VERSION_NAME,installed.versionName)
        assertEquals(expected,BuildConfig.APPLICATION_ID)
        assertEquals(expected,context.packageName)
        val info=context.packageManager.getApplicationInfo(expected,0)
        val label=context.packageManager.getApplicationLabel(info).toString()
        assertEquals(if(BuildConfig.PERF_BUILD)"Filmroom Perf · 优化测试版" else "Filmroom",label)
        val provider=context.packageManager.resolveContentProvider("$expected.diagnostics",0)!!
        assertEquals(expected,provider.packageName)
        assertFalse(provider.exported)
        assertEquals("$expected.export.PAUSE",ExportService.ACTION_PAUSE)
        val service=Intent(context,ExportService::class.java).component!!
        assertEquals(expected,service.packageName)
        // Namespace/class names remain stable for JNI; only application identity changes.
        assertEquals("dev.filmroom.export.ExportService",service.className)
    }
    @Test fun gpuHelperIsPrivateIsolatedAndDisabledOutsidePerf(){
        val context=RuntimeEnvironment.getApplication()
        val component=android.content.ComponentName(context,dev.filmroom.engine.GpuProbeService::class.java)
        val service=context.packageManager.getServiceInfo(component,android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(service.exported);assertEquals(context.packageName+":gpu_probe",service.processName)
        assertEquals(BuildConfig.PERF_BUILD,service.enabled)
    }
    @Test fun diagnosticShareUsesOnlyCurrentPackagesProvider(){
        val context=RuntimeEnvironment.getApplication()
        val directory=File(context.cacheDir,"diagnostic-shares").apply{mkdirs()}
        val file=File(directory,"package-identity-test.txt").apply{writeText("test")}
        try {
            val intent=DiagnosticShareIntents.create(context,file,5)
            @Suppress("DEPRECATION")
            val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("${BuildConfig.APPLICATION_ID}.diagnostics",uri.authority)
            assertEquals(context.packageName,BuildConfig.APPLICATION_ID)
        } finally {file.delete()}
    }
    @Test fun optimizationControlsAppearOnlyInPrivatePerfBuild(){
        val controller=org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val root=controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)
            fun contains(view:android.view.View):Boolean = view.tag=="gpu-rendering-toggle" ||
                (view is android.view.ViewGroup && (0 until view.childCount).any{contains(view.getChildAt(it))})
            assertEquals(BuildConfig.PERF_BUILD,contains(root))
        } finally {controller.pause().stop().destroy()}
    }

    @Test fun scatteredOptimizationAndProbeControlsAreNotUserVisible(){
        val controller=org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try{
            val root=controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)
            fun tags(view:android.view.View):List<String> = listOfNotNull(view.tag as? String)+(if(view is android.view.ViewGroup)(0 until view.childCount).flatMap{tags(view.getChildAt(it))} else emptyList())
            assertFalse(tags(root).any{it.startsWith("optimization-") || it.startsWith("gpu-probe-")})
            assertEquals(if(BuildConfig.PERF_BUILD)1 else 0,tags(root).count{it=="gpu-rendering-toggle"})
        }finally{controller.pause().stop().destroy()}
    }

}
