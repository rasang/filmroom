package dev.filmroom.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object DiagnosticShareIntents {
    fun create(context:Context,file:File,minutes:Int):Intent {
        require(minutes in 1..1440)
        val directory=File(context.cacheDir,"diagnostic-shares").canonicalFile
        require(file.canonicalFile.parentFile==directory && file.isFile && file.extension=="txt")
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.diagnostics",file)
        return Intent(Intent.ACTION_SEND).apply {
            type="text/plain"
            putExtra(Intent.EXTRA_STREAM,uri)
            putExtra(Intent.EXTRA_SUBJECT,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_22a603ffe91d, minutes))
            clipData=ClipData.newUri(context.contentResolver,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0dbb0ebd0479),uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
