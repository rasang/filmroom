package dev.filmroom

import android.content.pm.PackageManager
import dev.filmroom.ui.DiagnosticFileProvider
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver

/** AndroidX statics survive some Robolectric sandboxes while app cacheDir changes per test. */
internal fun attachCurrentDiagnosticProvider() {
    val context=RuntimeEnvironment.getApplication()
    val authority="${context.packageName}.diagnostics"
    val info=requireNotNull(context.packageManager.resolveContentProvider(authority,PackageManager.GET_META_DATA))
    val provider=DiagnosticFileProvider()
    // FileProvider.attachInfo is the normal supported lifecycle entry point. AndroidX1.13
    // removes this authority from its static path cache here; no reflective root overrides.
    provider.attachInfo(context,info)
    ShadowContentResolver.registerProviderInternal(authority,provider)
}
