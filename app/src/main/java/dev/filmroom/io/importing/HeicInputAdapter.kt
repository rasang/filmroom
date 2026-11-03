package dev.filmroom.io.importing

import dev.filmroom.io.*
import dev.filmroom.io.formats.PhotoFormat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ensureActive

internal class HeicInputAdapter(private val heicImporter: HeicImport): PhotoInputAdapter {
    override fun accepts(header: PhotoInputHeader) = header.identified.format == PhotoFormat.HEIF
    override suspend fun load(request: PhotoImportRequest, header: PhotoInputHeader): LoadedPhoto = with(request) {
        val info=try{HeicContainer.inspect(file){job.ensureActive()}}catch(e:HeicContainer.Rejected){
            val reason=when(e.failure){HeicContainer.Failure.UNSUPPORTED->PhotoException.Reason.UNSUPPORTED;HeicContainer.Failure.LIMIT->PhotoException.Reason.TOO_LARGE;else->PhotoException.Reason.UNREADABLE}
            val code=when(e.failure){
                HeicContainer.Failure.UNSUPPORTED->dev.filmroom.diagnostics.CodecErrorCode.HEIC_CONTAINER_UNSUPPORTED
                HeicContainer.Failure.LIMIT->dev.filmroom.diagnostics.CodecErrorCode.HEIC_CONTAINER_LIMIT
                else->dev.filmroom.diagnostics.CodecErrorCode.HEIC_CONTAINER_INVALID
            }
            throw PhotoException(reason,e.message ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0e98e94d0aff),e,
                codecCode=code,codecStage=dev.filmroom.diagnostics.CodecStage.PROBE,
                uiMessage=e.uiMessage ?: if(e.message==null)dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_0e98e94d0aff)else null)
        }
        HeicMemoryPolicy.estimatedBytes(info,file.length()) // Same size limit on restore; no allocation here.
        val linear=if(existingRaw!=null && existingRaw.isFile && existingRaw.canonicalFile.parentFile==sourceDirectory.canonicalFile){
            existingRaw
        }else{
            if(!allowDecode)throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_2f6241f58a5d))
            File(sourceDirectory,"heic-source-${UUID.randomUUID()}.rgb").also{
                onNewRaw(it);heicImporter.write(file,info,it,job)
            }
        }
        if(linear.length()!=LinearRgb.byteCount(info.displayWidth,info.displayHeight))
            throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_662817bc355b))
        val preview=LinearSpool.preview(linear,info.displayWidth,info.displayHeight,maxEdge,job)
        return@with LoadedPhoto(PhotoSource(file,name,"image/heif",info.displayWidth,info.displayHeight,file.length(),
            linearFile=linear,photoMetadata=captureMetadata,developmentMessages=listOf(dev.filmroom.i18n.UiMessage.of(if(info.sourceColor==HeicIccProfile.ColorIdentity.DISPLAY_P3)
                dev.filmroom.R.string.text_99d3398d25b4 else dev.filmroom.R.string.text_21acba32c252))),preview)
    }
}
