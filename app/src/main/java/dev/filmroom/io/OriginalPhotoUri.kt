package dev.filmroom.io

/** Store only the selected provider URI. Never invent a URI from a private cache path. */
internal object OriginalPhotoUri {
    fun sanitize(value: String?): String? {
        if (value.isNullOrBlank() || value.length > 4096 || value.any { it.code < 32 }) return null
        return runCatching {
            val uri = android.net.Uri.parse(value)
            value.takeIf { uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
                !uri.authority.orEmpty().contains("@") && uri.fragment == null && !uri.path.isNullOrBlank() }
        }.getOrNull()
    }
}
