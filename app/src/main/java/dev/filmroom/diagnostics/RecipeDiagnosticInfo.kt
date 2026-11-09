package dev.filmroom.diagnostics

/** Fixed labels and bounded technical measurements only; never a name, private recipe ID, URI or payload. */
enum class RecipeAction { LIST, SAVE, DELETE, MERGE, APPLY, QR_SHARE, QR_IMPORT, SCAN }
enum class RecipeStep { START, STORE_READ, STORE_WRITE, PARAMETERS, QR_ENCODE, QR_RENDER, IMAGE_READ, QR_DECODE, VALIDATE, CONFIRM, COMMIT, POSTER, FILE_WRITE, SHARE_CHOOSER, CAMERA, ALBUM, END }
enum class RecipeReason { NONE, INVALID_NAME, DUPLICATE_NAME, FULL, UNREADABLE, INCOMPATIBLE, WRITE_FAILED, MISSING, INVALID_QR, TOO_LARGE, PERMISSION_DENIED, FILE_NOT_FOUND, IO_ERROR, OUT_OF_MEMORY, INVALID_ARGUMENT, INVALID_STATE, STALE, INACTIVE, CAMERA_ERROR, UNKNOWN }
enum class RecipeInput { CAMERA, ALBUM, TEXT }
data class RecipeDiagnosticInfo(
    val action: RecipeAction,
    val step: RecipeStep = RecipeStep.START,
    val reason: RecipeReason = RecipeReason.NONE,
    val input: RecipeInput? = null,
    val count: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val elapsedMs: Long? = null,
    val correlation: Long? = null,
) {
    internal fun fields(): Map<String, Any> = buildMap {
        put("recipeAction", action.name); put("recipeStep", step.name); put("recipeReason", reason.name)
        input?.let { put("recipeInput", it.name) }
        correlation?.takeIf { it > 0 }?.let { put("recipeCorrelation", it) }
        count?.takeIf { it in 0..200 }?.let { put("recipeCount", it) }
        width?.takeIf { it in 1..20000 }?.let { put("recipeWidth", it) }
        height?.takeIf { it in 1..20000 }?.let { put("recipeHeight", it) }
        elapsedMs?.takeIf { it in 0..86_400_000 }?.let { put("recipeElapsedMs", it) }
    }
    companion object {
        internal fun fromFields(fields: Map<String, String>): RecipeDiagnosticInfo? {
            val action = RecipeAction.entries.firstOrNull { it.name == fields["recipeAction"] } ?: return null
            val step = RecipeStep.entries.firstOrNull { it.name == fields["recipeStep"] } ?: RecipeStep.START
            val reason = RecipeReason.entries.firstOrNull { it.name == fields["recipeReason"] } ?: RecipeReason.UNKNOWN
            fun number(key: String, min: Long, max: Long): Long? = fields[key]?.takeIf { it.length <= 9 && it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()?.takeIf { it in min..max }
            return RecipeDiagnosticInfo(action, step, reason,
                RecipeInput.entries.firstOrNull { it.name == fields["recipeInput"] },
                number("recipeCount", 0, 200)?.toInt(), number("recipeWidth", 1, 20000)?.toInt(),
                number("recipeHeight", 1, 20000)?.toInt(), number("recipeElapsedMs", 0, 86_400_000),
                fields["recipeCorrelation"]?.takeIf { it.length <= 19 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()?.takeIf { it > 0 })
        }
    }
}
