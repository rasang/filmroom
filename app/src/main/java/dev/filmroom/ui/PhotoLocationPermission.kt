package dev.filmroom.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Optional permission for embedded photo GPS, not access to the device's current location. */
internal class PhotoLocationPermission(private val activity: ComponentActivity, private val launchFiles: () -> Unit) {
    private var pending = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pending) { pending = false; launchFiles() }
    }
    fun restore(state: Bundle?) { pending = state?.getBoolean("photoLocationFilePickPending") == true }
    fun save(state: Bundle) { state.putBoolean("photoLocationFilePickPending", pending) }
    fun pickFiles() {
        if (pending) return
        val prefs = activity.getSharedPreferences("photo-location-permission", 0)
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            prefs.getBoolean("asked", false)) { launchFiles(); return }
        // The import chooser explains the purpose. Refusal does not block photo editing.
        prefs.edit().putBoolean("asked", true).apply()
        pending = true
        launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
}
