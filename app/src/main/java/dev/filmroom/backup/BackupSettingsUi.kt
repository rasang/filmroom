package dev.filmroom.backup

import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import dev.filmroom.R
import kotlinx.coroutines.launch

/** Local SAF documents only. The system picker owns the destination/access grant. */
internal class BackupSettingsUi(private val activity: ComponentActivity) {
    private val model = ViewModelProvider(activity)[AppBackupViewModel::class.java]
    private var dialog: AlertDialog? = null
    private var buttons = emptyList<Button>()
    // A boolean only: never persist document URIs or backup contents in saved state.
    private var pickerPending = activity.savedStateRegistry
        .consumeRestoredStateForKey("backup-picker")?.getBoolean("pending") ?: false
    init {
        activity.savedStateRegistry.registerSavedStateProvider("backup-picker") {
            android.os.Bundle().apply { putBoolean("pending", pickerPending) }
        }
    }
    private val create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { finishPicker(it, model::export) }
    private val open = activity.registerForActivityResult(object: ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent =
            super.createIntent(context,input).addCategory(android.content.Intent.CATEGORY_OPENABLE)
    }) { finishPicker(it, model::read) }
    fun addTo(parent: LinearLayout) {
        val title = TextView(activity).apply { setText(R.string.backup_title); textSize=16f; setTextColor(dev.filmroom.ui.FilmroomPalette.ink) }
        val note = TextView(activity).apply { setText(R.string.backup_description); textSize=13f; setTextColor(dev.filmroom.ui.FilmroomPalette.muted) }
        parent.addView(title); parent.addView(note)
        val export = Button(activity).apply { setText(R.string.backup_export); isAllCaps=false; tag="backup-export"
            setOnClickListener { launchPicker { create.launch("Filmroom-backup-${java.time.LocalDate.now()}.json") } } }
        val import = Button(activity).apply { setText(R.string.backup_import); isAllCaps=false; tag="backup-import"
            setOnClickListener { launchPicker { open.launch(arrayOf("application/json","application/octet-stream","text/plain")) } } }
        buttons=listOf(export,import); buttons.forEach(parent::addView)
        updateButtons()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    dialog?.setOnDismissListener(null); dialog?.dismiss(); dialog=null
                    updateButtons()
                    when(state) {
                        is AppBackupViewModel.State.Idle -> Unit
                        is AppBackupViewModel.State.Busy -> show(AlertDialog.Builder(activity).setMessage(R.string.backup_working).setCancelable(false))
                        is AppBackupViewModel.State.Preview -> show(AlertDialog.Builder(activity)
                            .setTitle(R.string.backup_import)
                            .setMessage(activity.getString(R.string.backup_confirm,state.document.settings.length(),state.document.recipes.size,state.document.history.length()))
                            .setNegativeButton(android.R.string.cancel){_,_->model.dismiss()}
                            .setPositiveButton(R.string.backup_restore){_,_->model.restore()}, dismiss=true)
                        is AppBackupViewModel.State.Finished -> show(AlertDialog.Builder(activity)
                            .setTitle(R.string.backup_done)
                            .setMessage(if(state.importing) activity.getString(R.string.backup_restored,state.recipes,state.history) else activity.getString(R.string.backup_exported))
                            .setPositiveButton(android.R.string.ok){_,_->model.dismiss();if(state.importing)activity.recreate()},dismiss=true,refresh=state.importing)
                        is AppBackupViewModel.State.Failed -> show(AlertDialog.Builder(activity).setTitle(R.string.backup_failed)
                            .setMessage(if(state.partial)R.string.backup_partial else R.string.backup_invalid)
                            .setPositiveButton(android.R.string.ok){_,_->model.dismiss()},dismiss=true)
                    }
                }
            }
        }
    }
    private fun updateButtons() {
        buttons.forEach { it.isEnabled = !pickerPending && model.state.value !is AppBackupViewModel.State.Busy }
    }
    internal fun launchPicker(launch: () -> Unit) {
        // Guard even programmatic clicks, before the external activity can be launched.
        if (pickerPending || model.state.value is AppBackupViewModel.State.Busy) return
        pickerPending = true
        updateButtons()
        try {
            launch()
        } catch (_: Exception) {
            pickerPending = false
            updateButtons()
            show(AlertDialog.Builder(activity).setTitle(R.string.backup_failed)
                .setMessage(R.string.backup_invalid).setPositiveButton(android.R.string.ok, null))
        }
    }
    private fun finishPicker(uri: android.net.Uri?, accept: (android.net.Uri) -> Unit) {
        pickerPending = false
        // Busy is set synchronously by the model before the buttons can be enabled again.
        try { uri?.let(accept) } finally { updateButtons() }
    }
    private fun show(builder: AlertDialog.Builder, dismiss: Boolean=false, refresh: Boolean=false) {
        dialog=builder.create().also { d ->
            if(dismiss)d.setOnCancelListener { model.dismiss(); if(refresh)activity.recreate() }
            d.show();d.window?.setBackgroundDrawableResource(R.drawable.filmroom_dialog_background)
        }
    }
    fun close(){ dialog?.setOnDismissListener(null);dialog?.dismiss();dialog=null }
}
