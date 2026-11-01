package dev.filmroom.ui

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** One optional explanation per install, invoked only by an explicit export/list interaction. */
class ExportNotificationPermission(private val activity:ComponentActivity) {
    private var afterPermission:(()->Unit)?=null
    private val launcher=activity.registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(!granted)Toast.makeText(activity,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_notification),Toast.LENGTH_LONG).show()
        afterPermission?.also{afterPermission=null;it()}
    }
    fun offer(after:()->Unit={}) {
        if(Build.VERSION.SDK_INT<33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED){after();return}
        val preferences=activity.getSharedPreferences("export-notification-ui",0)
        if(preferences.getBoolean("offered",false)){after();return}
        preferences.edit().putBoolean("offered",true).apply()
        AlertDialog.Builder(activity).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e1bc9722aab0))
            .setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_597ba8c43d0f))
            .setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3834f9cf9f9b)){_,_->afterPermission=after;launcher.launch(Manifest.permission.POST_NOTIFICATIONS)}
            .setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a2666f394298)){_,_->after()}.setOnCancelListener{after()}.show()
    }
}
