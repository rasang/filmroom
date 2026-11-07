package dev.filmroom.io.importing

import dev.filmroom.io.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** TIFF/ORF/CR3 signatures are only routing hints. The native content parser must validate the RAW format. */
internal class RawInputAdapter(private val codecs: RawSourceDecoder): PhotoInputAdapter {
    override fun accepts(header: PhotoInputHeader) = (header.isClassicTiff || header.isPanasonicRw2 || header.isOlympusRaw || header.isCanonCr3)
    override suspend fun load(request: PhotoImportRequest, header: PhotoInputHeader): LoadedPhoto = with(request) {
        val metadata:JSONObject
        val raw:File
        if(existingRaw!=null && existingRaw.isFile && existingRaw.canonicalFile.parentFile==sourceDirectory.canonicalFile){
            metadata=codecs.probe(file);raw=existingRaw
        }else{
            if(!allowDecode)throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_312bb591804d))
            raw=File(sourceDirectory,"raw-source-${UUID.randomUUID()}.rgb");onNewRaw(raw)
            metadata=codecs.decode(file,raw)
        }
        val parsed=dev.filmroom.codec.RawMetadata.parse(metadata)
        val width=parsed.width;val height=parsed.height
        job[dev.filmroom.diagnostics.ImportObservation]?.apply { rawFormat=parsed.format;dimensions(width,height);memoryStage=dev.filmroom.diagnostics.ImportMemoryStage.RAW_PREVIEW }
        if(raw.length()!=parsed.bytes)throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_52dca03550a9))
        job.ensureActive()
        val preview=LinearSpool.preview(raw,width,height,maxEdge,job)
        return@with LoadedPhoto(PhotoSource(file,name,parsed.format.mimeType,width,height,file.length(),false,raw,photoMetadata=captureMetadata,developmentMessages=parsed.displayMessages),preview)
    }
}
