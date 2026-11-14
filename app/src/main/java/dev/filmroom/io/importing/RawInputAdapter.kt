package dev.filmroom.io.importing

import dev.filmroom.io.*
import dev.filmroom.codec.RawMetadata
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** TIFF/ORF/CR3/RAF signatures are only routing hints. The native content parser must validate the RAW format. */
internal class RawInputAdapter(private val codecs: RawSourceDecoder): PhotoInputAdapter {
    override fun accepts(header: PhotoInputHeader) = (header.isClassicTiff || header.isPanasonicRw2 || header.isOlympusRaw || header.isCanonCr3 || header.isFujifilmRaf)
    override suspend fun load(request: PhotoImportRequest, header: PhotoInputHeader): LoadedPhoto = with(request) {
        var preview:android.graphics.Bitmap?=null
        var completed=false
        var migrating=false
        try {
            job.ensureActive()
            val cached=existingRaw?.takeIf { Files.isRegularFile(it.toPath(),NOFOLLOW_LINKS)
                && it.canonicalFile.parentFile==sourceDirectory.canonicalFile }
            val probed=cached?.let { RawMetadata.parse(codecs.probe(file)) }
            val parsed:RawMetadata
            val raw:File
            if(cached!=null && probed!=null && RawDevelopmentCache.matches(cached,file,probed)) {
                parsed=probed;raw=cached
            }else{
                // A known but stale app-owned cache can be rebuilt from its original RAW.
                // A missing/invalid restore key still cannot silently initiate decoding.
                if(!allowDecode && cached==null)throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_312bb591804d))
                migrating=cached!=null
                raw=File(sourceDirectory,"raw-source-${UUID.randomUUID()}.rgb")
                onNewRaw(raw)
                parsed=RawMetadata.parse(codecs.decode(file,raw))
                if(raw.length()!=parsed.bytes)throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_52dca03550a9))
                RawDevelopmentCache.write(raw,file,parsed)
            }
            val width=parsed.width;val height=parsed.height
            job[dev.filmroom.diagnostics.ImportObservation]?.apply { rawFormat=parsed.format;dimensions(width,height);memoryStage=dev.filmroom.diagnostics.ImportMemoryStage.RAW_PREVIEW }
            job.ensureActive()
            val bitmap=LinearSpool.preview(raw,width,height,maxEdge,job)
            preview=bitmap
            val loaded=LoadedPhoto(PhotoSource(file,name,parsed.format.mimeType,width,height,file.length(),false,raw,photoMetadata=captureMetadata,developmentMessages=parsed.displayMessages),bitmap)
            completed=true
            loaded
        } catch(c:CancellationException) {
            throw c
        } catch(e:Exception) {
            // A new decoder, resource shortage or preview I/O can fail after a valid probe.
            // None establishes that the retained original is invalid; never erase its session keys.
            if(migrating && file.isFile)throw RawCacheMigrationFailure(e)
            throw e
        } catch(e:OutOfMemoryError) {
            if(migrating && file.isFile)throw RawCacheMigrationFailure(e)
            throw e
        } finally {
            // Repository owns every registered new spool/record, including failed output.
            if(!completed)preview?.recycle()
        }
    }
}
