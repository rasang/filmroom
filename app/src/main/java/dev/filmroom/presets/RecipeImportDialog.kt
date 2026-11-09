package dev.filmroom.presets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import kotlinx.coroutines.launch

/** Scanner returns to the editor; importing never applies parameters to its photo. */
internal class RecipeImportDialog(
    private val activity: ComponentActivity,
    private val showReadable: (AlertDialog) -> Unit,
    private val onSaved: () -> Unit,
) {
    private val model = ViewModelProvider(activity)[PresetQrViewModel::class.java]
    private var dialog: AlertDialog? = null
    private var input: EditText? = null
    private var preserving = false
    private var restoredName: String? = null
    init {
        val restored = activity.savedStateRegistry.consumeRestoredStateForKey("recipe-import")
        activity.savedStateRegistry.registerSavedStateProvider("recipe-import") {
            Bundle().apply {
                if (model.state.value.phase == "confirm") {
                    putString("payload", model.state.value.payload)
                    putString("name", model.nameDraft)
                }
            }
        }
        if (model.state.value.phase == "home") restored?.getString("payload")?.let {
            restoredName = restored.getString("name")
            model.read(it)
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try { model.state.collect(::render) }
                finally { dismissPreserving() }
            }
        }
    }
    fun accept(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        when {
            data?.data != null -> model.read(data.data!!)
            data?.getStringExtra(RecipeScannerActivity.EXTRA_QR_TEXT) != null ->
                model.read(data.getStringExtra(RecipeScannerActivity.EXTRA_QR_TEXT)!!, dev.filmroom.diagnostics.RecipeInput.CAMERA)
        }
    }
    private fun text(id: Int) = L10n.text(id)
    private fun dismissPreserving() {
        preserving = true
        dialog?.dismiss(); dialog = null; input = null
        preserving = false
    }
    private fun render(screen: PresetQrViewModel.Screen) {
        if (screen.phase == "saved") {
            dismissPreserving()
            model.home()
            Toast.makeText(activity, text(R.string.saved_preset_saved), Toast.LENGTH_SHORT).show()
            onSaved()
            return
        }
        if (screen.draft == null) {
            if (screen.error != null) {
                Toast.makeText(activity, problem(screen.error), Toast.LENGTH_LONG).show()
                model.home()
            }
            return
        }
        if (screen.phase != "confirm" && screen.phase != "working") return
        if (dialog == null) {
            val name = EditText(activity).apply {
                tag = "recipe-import-name"
                hint = text(R.string.saved_preset_name)
                setSingleLine(true)
                setText(restoredName ?: model.nameDraft.ifEmpty { screen.draft.name })
                restoredName = null
                model.nameDraft = this.text.toString()
                setSelection(length())
                doAfterTextChanged { model.nameDraft = it.toString() }
            }
            input = name
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            val body = LinearLayout(activity).apply {
                setPadding(padding, padding / 3, padding, padding / 3)
                addView(name, LinearLayout.LayoutParams(-1, -2))
            }
            val target = AlertDialog.Builder(activity).setTitle(text(R.string.preset_qr_save_new)).setView(body)
                .setNegativeButton(text(R.string.saved_preset_cancel), null)
                .setPositiveButton(text(R.string.saved_preset_save), null).create()
            dialog = target
            target.setOnDismissListener {
                if (dialog === target) { dialog = null; input = null }
                if (!preserving && model.state.value.phase != "working") model.home()
            }
            showReadable(target)
            target.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (model.state.value.phase == "confirm") model.save(name.text.toString())
            }
        }
        val busy = screen.phase == "working"
        dialog?.let {
            it.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !busy
            it.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !busy
            it.setCancelable(!busy); it.setCanceledOnTouchOutside(!busy)
        }
        input?.isEnabled = !busy
        input?.error = screen.error?.let(::problem)
    }
    private fun problem(error: Exception) = text(when (error) {
        is SavedPresetStore.Failure -> when (error.problem) {
            SavedPresetStore.Problem.INVALID_NAME -> R.string.saved_preset_invalid_name
            SavedPresetStore.Problem.DUPLICATE_NAME -> R.string.saved_preset_duplicate_name
            SavedPresetStore.Problem.FULL -> R.string.saved_preset_full
            else -> R.string.saved_preset_write_failed
        }
        is PresetQrCodec.Failure -> when (error.problem) {
            PresetQrCodec.Problem.INCOMPATIBLE -> R.string.preset_qr_incompatible
            PresetQrCodec.Problem.TOO_LARGE -> R.string.preset_qr_too_large
            else -> R.string.preset_qr_invalid
        }
        else -> R.string.preset_qr_invalid
    })
}
