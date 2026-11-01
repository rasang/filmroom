package dev.filmroom.ui

import android.net.Uri
import androidx.core.content.FileProvider

/** Report MIME does not depend on the recipient's or device's extension table. */
class DiagnosticFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        // Resolve through FileProvider first: retain its canonical-path/root checks.
        val fallback = super.getType(uri)
        return if (uri.lastPathSegment?.endsWith(".txt") == true) "text/plain" else fallback
    }
}
