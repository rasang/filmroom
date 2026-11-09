package dev.filmroom.presets

import dev.filmroom.diagnostics.*
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
    private var correlation = RecipeCorrelation.next()
    fun beginImport(): Long { correlation = RecipeCorrelation.next(); return correlation }
    private fun run(action: RecipeAction, input: RecipeInput? = null, next: suspend (RecipeLog) -> Screen) {
        if (mutable.value.phase == "working") return
        val operationCorrelation = correlation
        val before = mutable.value
        mutable.value = before.copy(phase = "working", error = null)
        viewModelScope.launch {
            val log = RecipeLog(action, input, operationCorrelation)
            try { mutable.value = withContext(Dispatchers.IO) { next(log) }; log.end(Outcome.SUCCESS) }
            catch (cancelled: CancellationException) { log.failed(cancelled); throw cancelled }
            catch (error: Exception) { log.failed(error); mutable.value = before.copy(error = error) }
            catch (error: OutOfMemoryError) { log.failed(error); throw error }
        }
    }
    fun share(id: String) = run(RecipeAction.QR_SHARE) { log ->
        log.at(RecipeStep.STORE_READ)
        val item = store.list().firstOrNull { it.id == id } ?: throw SavedPresetStore.Failure(SavedPresetStore.Problem.MISSING)
        log.at(RecipeStep.PARAMETERS)
        val parameters = item.parameters()
        validateStocks(parameters)
        log.at(RecipeStep.QR_ENCODE)
        val payload = PresetQrCodec.encode(item.name, parameters)
        log.at(RecipeStep.QR_RENDER)
        Screen("share", PresetQrCodec.Draft(item.name, parameters), PresetQrImage.render(getApplication(), payload), payload)
    }
    fun read(text: String, input: RecipeInput = RecipeInput.TEXT, token: Long? = null) {
        if (mutable.value.phase == "working") return
        correlation = token?.let(RecipeCorrelation::accept) ?: RecipeCorrelation.next()
        run(RecipeAction.QR_IMPORT, input) { decoded(text, it) }
    }
    fun read(uri: Uri, token: Long? = null) {
        if (mutable.value.phase == "working") return
        correlation = token?.let(RecipeCorrelation::accept) ?: RecipeCorrelation.next()
        run(RecipeAction.QR_IMPORT, RecipeInput.ALBUM) { log ->
            log.at(RecipeStep.IMAGE_READ)
            decoded(PresetQrImage.read(getApplication(), uri, log), log)
        }
    }
    private fun decoded(text: String, log: RecipeLog): Screen {
        log.at(RecipeStep.QR_DECODE)
        val draft = PresetQrCodec.decode(text)
        log.at(RecipeStep.VALIDATE)
        validateStocks(draft.parameters)
        log.at(RecipeStep.CONFIRM)
        nameDraft = draft.name
        return Screen("confirm", draft = draft, payload = text)
    }
    fun save(name: String) {
        val draft = mutable.value.takeIf { it.phase == "confirm" }?.draft ?: return
        run(RecipeAction.SAVE) { log -> log.at(RecipeStep.COMMIT); store.save(name, draft.parameters, log.correlation); Screen("saved", draft = draft.copy(name = SavedPresetStore.cleanName(name))) }
    }
    fun home() { if (mutable.value.phase != "working") mutable.value = Screen() }
    private fun validateStocks(parameters: dev.filmroom.model.EditorState) {
        val rows = JSONObject(getApplication<Application>().assets.open("engine-data/profile_catalog.json").bufferedReader().use { it.readText() }).getJSONArray("profiles")
        if (!dev.filmroom.model.StockSelectionValidation.isSupported(parameters, rows))
            throw PresetQrCodec.Failure(PresetQrCodec.Problem.INCOMPATIBLE)
    }
}
