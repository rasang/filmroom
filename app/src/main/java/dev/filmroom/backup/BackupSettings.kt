package dev.filmroom.backup

import android.content.Context
import dev.filmroom.i18n.L10n
import org.json.JSONObject

/** Explicit allowlist, never copy SharedPreferences wholesale (session URIs live elsewhere). */
internal object BackupSettings {
    private data class Field(val file: String, val key: String, val default: Any, val allowed: Set<String> = emptySet())
    private val fields = mapOf(
        "language" to Field("filmroom-language", "language", "zh", L10n.supported.toSet()),
        "resetOnImport" to Field("editor-preferences", "reset-on-import", false),
        "restoreLastPhoto" to Field("editor-preferences", "restore-last-photo", false),
        "rawBatchMode" to Field("raw-processing-preferences", "raw-batch-mode", "auto", setOf("auto","small","standard","large")),
        "singleDeleteMode" to Field("export-history-preferences", "single-delete-mode", "ask", setOf("ask","records","records-and-photos")),
        "clearHistoryMode" to Field("export-history-preferences", "clear-history-mode", "ask", setOf("ask","records","records-and-photos")),
        "diagnosticMinutes" to Field("diagnostic-ui", "minutes", 15)
    )
    fun snapshot(context: Context) = JSONObject().apply {
        fields.forEach { (name, field) -> put(name, context.getSharedPreferences(field.file,0).all[field.key] ?: field.default) }
    }
    fun validate(json: JSONObject) {
        require(json.keys().asSequence().all { it in fields }) { "INCOMPATIBLE" }
        fields.forEach { (name, field) -> if (json.has(name)) {
            val value = json.get(name)
            when (field.default) {
                is Boolean -> require(value is Boolean)
                is Int -> require(value is Int && value in 1..1440)
                is String -> require(value is String && value in field.allowed)
            }
        } }
    }
    /** Import called only after explicit overwrite confirmation. Missing old fields keep current values. */
    fun apply(context: Context, json: JSONObject) {
        validate(json)
        fields.entries.groupBy { it.value.file }.forEach { (file, entries) ->
            val edit = context.getSharedPreferences(file,0).edit()
            entries.forEach { (name, field) -> if (json.has(name)) when(val value = json.get(name)) {
                is Boolean -> edit.putBoolean(field.key,value)
                is Int -> edit.putInt(field.key,value)
                is String -> edit.putString(field.key,value)
            } }
            check(edit.commit()) { "WRITE_FAILED" }
        }
        L10n.initialize(context)
    }
}
