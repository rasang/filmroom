package dev.filmroom.codec

import dev.filmroom.diagnostics.CodecErrorCode
import dev.filmroom.diagnostics.CodecStage
import dev.filmroom.io.PhotoException
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Import remains cache-only. Persistent exports authorize one immutable queue job, never app dataDir. */
internal object CodecExportScope {
    fun select(cacheRoot:File,configuredJobs:File?,input:File,output:File):File {
        fun rejected():Nothing=throw PhotoException(PhotoException.Reason.EXPORT,
            dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_b64913db4f1d),
            codecCode=CodecErrorCode.INVALID_PATH,codecStage=CodecStage.VALIDATE)
        val cache=cacheRoot.canonicalFile
        val source=input.canonicalFile
        val destination=output.canonicalFile
        if(source.toPath().startsWith(cache.toPath()) && destination.toPath().startsWith(cache.toPath()))return cache
        val configured=configuredJobs?:rejected()
        val jobs=configured.canonicalFile
        val app=cache.parentFile?:rejected()
        val files=File(app,"files").canonicalFile
        // A configured or parent symlink must not expand authorization outside the app.
        if(cache.name!="cache" || files.parentFile!=app || files.name!="files" || jobs.parentFile!=files || jobs.name!="export-jobs")rejected()
        val job=source.parentFile?:rejected()
        val id=runCatching{UUID.fromString(job.name).toString()}.getOrNull()
        if(id!=job.name || job.parentFile!=jobs || destination.parentFile!=job)rejected()
        // Do not accept a symlinked job even if its target is another approved job.
        val requestedJob=input.absoluteFile.parentFile?:rejected()
        if(Files.isSymbolicLink(requestedJob.toPath()) || Files.isSymbolicLink(configured.toPath()) ||
            Files.isSymbolicLink(input.toPath()) || Files.isSymbolicLink(output.toPath()))rejected()
        if(!source.name.matches(Regex("film-result-[0-9a-f-]{36}\\.rgb")) ||
            !destination.name.matches(Regex("codec-output-[0-9a-f-]{36}\\.(jpg|tif)")))rejected()
        return job
    }
}
