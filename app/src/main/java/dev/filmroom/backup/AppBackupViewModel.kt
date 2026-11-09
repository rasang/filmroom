package dev.filmroom.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.filmroom.diagnostics.BackupAction
import dev.filmroom.diagnostics.BackupStep
import kotlinx.coroutines.CancellationException
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
            val log = BackupLog(BackupAction.EXPORT)
            try {
                withContext(Dispatchers.IO) {
                    val store = ExportQueue.get(context).store
                    val document = AppBackupCodec.Document(BackupSettings.snapshot(context), SavedPresetStore(context).list(), store.exportBackupHistory())
                    log.counts(document.recipes.size, document.history.length())
                    log.step(BackupStep.VALIDATE)
                    validateStocks(document)
                    log.step(BackupStep.OPEN)
                    context.contentResolver.openOutputStream(uri,"wt").use { stream ->
                        log.step(BackupStep.CODEC, writesStarted=true)
                        AppBackupCodec.writeZip(document, checkNotNull(stream))
                    }
                }
                log.success()
                mutable.value = State.Finished(false)
            } catch (e: CancellationException) { log.failure(e); throw e }
            catch (e: Exception) { log.failure(e); mutable.value = State.Failed(false) }
        }
    }
    fun read(uri: Uri) {
        if (mutable.value is State.Busy) return
        mutable.value = State.Busy
        viewModelScope.launch {
            val log = BackupLog(BackupAction.READ)
            try {
                val document = withContext(Dispatchers.IO) {
                    log.step(BackupStep.OPEN)
                    val decoded = context.contentResolver.openInputStream(uri).use {
                        log.step(BackupStep.CODEC)
                        AppBackupCodec.read(checkNotNull(it))
                    }
                    log.counts(decoded.recipes.size, decoded.history.length())
                    log.step(BackupStep.VALIDATE)
                    validateStocks(decoded)
                    dev.filmroom.export.ExportHistoryBackup.validateRows(decoded.history)
                    ExportQueue.get(context).store.mergeBackupHistory(decoded.history,validateOnly=true)
                    decoded
                }
                log.success()
                mutable.value = State.Preview(document)
            } catch (e: CancellationException) { log.failure(e); throw e }
            catch (e: Exception) { log.failure(e); mutable.value = State.Failed(false) }
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
            val log = BackupLog(BackupAction.RESTORE)
            log.counts(document.recipes.size, document.history.length())
            var attemptedWrite = false
            try {
                val counts = withContext(Dispatchers.IO) {
                    val store = ExportQueue.get(context).store
                    log.step(BackupStep.VALIDATE)
                    dev.filmroom.export.ExportHistoryBackup.validateRows(document.history)
                    BackupSettings.validate(document.settings)
                    store.mergeBackupHistory(document.history,validateOnly=true)
                    attemptedWrite = true
                    log.step(BackupStep.RECIPES, writesStarted=true)
                    val recipes = SavedPresetStore(context).mergeBackup(document.recipes)
                    log.step(BackupStep.HISTORY)
                    val history = store.mergeBackupHistory(document.history)
                    log.step(BackupStep.SETTINGS)
                    BackupSettings.apply(context,document.settings)
                    recipes to history
                }
                log.counts(counts.first, counts.second)
                log.success()
                mutable.value = State.Finished(true, counts.first, counts.second)
            } catch (e: CancellationException) { log.failure(e); throw e }
            catch (e: Exception) {
                log.failure(e)
                // Each store writes atomically. Never undo concurrent user work; retry deduplicates.
                mutable.value = State.Failed(attemptedWrite)
            }
        }
    }
}
