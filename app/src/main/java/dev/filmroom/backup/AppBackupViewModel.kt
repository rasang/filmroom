package dev.filmroom.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.filmroom.export.ExportQueue
import dev.filmroom.presets.SavedPresetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Work survives Activity rotation. No decoded backup, paths or images are saved in Bundle/logs. */
internal class AppBackupViewModel(app: Application) : AndroidViewModel(app) {
    sealed class State {
        object Idle: State()
        object Busy: State()
        data class Preview(val document: AppBackupCodec.Document): State()
        data class Finished(val importing: Boolean, val recipes: Int = 0, val history: Int = 0): State()
        data class Failed(val partial: Boolean): State()
    }
    private val mutable = MutableStateFlow<State>(State.Idle)
    val state = mutable.asStateFlow()
    private val context get() = getApplication<Application>()
    fun dismiss() { if (mutable.value !is State.Busy) mutable.value = State.Idle }
    fun export(uri: Uri) {
        if (mutable.value is State.Busy) return
        mutable.value = State.Busy
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = ExportQueue.get(context).store
                    val document = AppBackupCodec.Document(BackupSettings.snapshot(context), SavedPresetStore(context).list(), store.exportBackupHistory())
                    validateStocks(document)
                    val bytes = AppBackupCodec.encode(document)
                    context.contentResolver.openOutputStream(uri,"wt").use { stream ->
                        checkNotNull(stream); stream.write(bytes); stream.flush()
                    }
                }
                mutable.value = State.Finished(false)
            } catch (_: Exception) { mutable.value = State.Failed(false) }
        }
    }
    fun read(uri: Uri) {
        if (mutable.value is State.Busy) return
        mutable.value = State.Busy
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    val decoded = context.contentResolver.openInputStream(uri).use { AppBackupCodec.read(checkNotNull(it)) }
                    validateStocks(decoded)
                    dev.filmroom.export.ExportHistoryBackup.validateRows(decoded.history)
                    decoded
                }
                mutable.value = State.Preview(document)
            } catch (_: Exception) { mutable.value = State.Failed(false) }
        }
    }
    private fun validateStocks(document: AppBackupCodec.Document) {
        val profiles = context.assets.open("engine-data/profile_catalog.json").bufferedReader().use { org.json.JSONObject(it.readText()).getJSONArray("profiles") }
        AppBackupCodec.validateStocks(document,profiles)
    }
    fun restore() {
        val document = (mutable.value as? State.Preview)?.document ?: return
        mutable.value = State.Busy
        viewModelScope.launch {
            var attemptedWrite = false
            try {
                val counts = withContext(Dispatchers.IO) {
                    val store = ExportQueue.get(context).store
                    dev.filmroom.export.ExportHistoryBackup.validateRows(document.history)
                    BackupSettings.validate(document.settings)
                    attemptedWrite = true
                    val recipes = SavedPresetStore(context).mergeBackup(document.recipes)
                    val history = store.mergeBackupHistory(document.history)
                    BackupSettings.apply(context,document.settings)
                    recipes to history
                }
                mutable.value = State.Finished(true, counts.first, counts.second)
            } catch (_: Exception) {
                // Each store writes atomically. Never undo concurrent user work; retry deduplicates.
                mutable.value = State.Failed(attemptedWrite)
            }
        }
    }
}
