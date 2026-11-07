package dev.filmroom.presets

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class PresetQrViewModel(app: Application) : AndroidViewModel(app) {
    data class Screen(val phase: String = "home", val draft: PresetQrCodec.Draft? = null,
        val bitmap: Bitmap? = null, val payload: String? = null, val error: Exception? = null)
    private val mutable = MutableStateFlow(Screen())
    val state = mutable.asStateFlow()
    var initialized = false
    var externalPending = false
    var nameDraft = ""
    private val store = SavedPresetStore(app)
    private fun run(next: suspend () -> Screen) {
        if (mutable.value.phase == "working") return
        val before = mutable.value
        mutable.value = before.copy(phase = "working", error = null)
        viewModelScope.launch {
            try { mutable.value = withContext(Dispatchers.IO) { next() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = before.copy(error = error) }
        }
    }
    fun share(id: String) = run {
        val item = store.list().firstOrNull { it.id == id } ?: throw SavedPresetStore.Failure(SavedPresetStore.Problem.MISSING)
        val parameters = item.parameters()
        validateStocks(parameters)
        val payload = PresetQrCodec.encode(item.name, parameters)
        Screen("share", PresetQrCodec.Draft(item.name, parameters), PresetQrImage.render(getApplication(), payload), payload)
    }
    fun read(text: String) = run { decoded(text) }
    fun read(uri: Uri) = run { decoded(PresetQrImage.read(getApplication(), uri)) }
    private fun decoded(text: String): Screen {
        val draft = PresetQrCodec.decode(text)
        validateStocks(draft.parameters)
        nameDraft = draft.name
        return Screen("confirm", draft = draft, payload = text)
    }
    fun save(name: String) {
        val draft = mutable.value.takeIf { it.phase == "confirm" }?.draft ?: return
        run { store.save(name, draft.parameters); Screen("saved", draft = draft.copy(name = SavedPresetStore.cleanName(name))) }
    }
    fun home() { if (mutable.value.phase != "working") mutable.value = Screen() }
    private fun validateStocks(parameters: dev.filmroom.model.EditorState) {
        val rows = JSONObject(getApplication<Application>().assets.open("engine-data/profile_catalog.json").bufferedReader().use { it.readText() }).getJSONArray("profiles")
        val profiles = (0 until rows.length()).map { rows.getJSONObject(it) }
        if (profiles.none { it.getString("id") == parameters.filmStock && it.getString("kind") in setOf("negative", "reversal") } ||
            profiles.none { it.getString("id") == parameters.paperStock && it.getString("kind") in setOf("paper", "cine_print") })
            throw PresetQrCodec.Failure(PresetQrCodec.Problem.INCOMPATIBLE)
    }
}
