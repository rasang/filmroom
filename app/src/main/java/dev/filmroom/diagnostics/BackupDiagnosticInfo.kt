package dev.filmroom.diagnostics

/** Metadata-only backup breadcrumbs. No names, media references or backup JSON. */
enum class BackupAction { EXPORT, READ, RESTORE }
enum class BackupStep { PREPARE, OPEN, CODEC, VALIDATE, RECIPES, HISTORY, SETTINGS, COMPLETE }
enum class BackupFailure { NONE, PERMISSION, NOT_FOUND, IO, INVALID_DATA, INVALID_STATE, CANCELLED, UNKNOWN }
data class BackupDiagnosticInfo(
    val action: BackupAction, val step: BackupStep,
    val failure: BackupFailure = BackupFailure.NONE,
    val recipes: Int? = null, val history: Int? = null,
    val writesStarted: Boolean = false,
) {
    internal fun fields(): Map<String, Any> = linkedMapOf<String, Any>(
        "backupAction" to action.name, "backupStep" to step.name,
        "backupFailure" to failure.name, "backupWritesStarted" to writesStarted,
    ).apply {
        recipes?.takeIf { it in 0..100_000 }?.let { put("backupRecipes", it) }
        history?.takeIf { it in 0..100_000 }?.let { put("backupHistory", it) }
    }
    companion object {
        internal fun parse(fields: Map<String, String>): BackupDiagnosticInfo? {
            val action = BackupAction.entries.firstOrNull { it.name == fields["backupAction"] } ?: return null
            val step = BackupStep.entries.firstOrNull { it.name == fields["backupStep"] } ?: return null
            fun count(key: String) = fields[key]?.takeIf { it.length <= 6 }?.toIntOrNull()?.takeIf { it in 0..100_000 }
            return BackupDiagnosticInfo(action, step,
                BackupFailure.entries.firstOrNull { it.name == fields["backupFailure"] } ?: BackupFailure.UNKNOWN,
                count("backupRecipes"), count("backupHistory"), fields["backupWritesStarted"]?.toBooleanStrictOrNull() ?: false)
        }
    }
}
