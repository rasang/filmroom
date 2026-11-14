package dev.filmroom.diagnostics

import dev.filmroom.io.formats.PhotoFormat

/** Fixed categories only. Never stores source URI, authority, filename, MIME text or exception text. */
enum class OriginalPhotoGrant { NOT_REQUESTED, NOT_APPLICABLE, ALREADY_PERSISTED, PERSISTED, TEMPORARY_ONLY, REQUEST_FAILED, VERIFY_FAILED }

enum class ImportOrigin { UNKNOWN, PHOTO_PICKER, FILES_PICKER }
enum class ImportPhase { PREPARE, SOURCE_OPEN, PRIVATE_OPEN, SOURCE_READ, COPY_ADMISSION, PRIVATE_WRITE,
    PRIVATE_CLOSE, SOURCE_CLOSE, INPUT_PROBE, INPUT_PROCESS, COMPLETE }
enum class ImportRoute { RASTER, RAW, HEIC, OTHER }
enum class ImportFailure { PERMISSION_DENIED, FILE_NOT_FOUND, IO, MEMORY_ALLOCATION,
    PLATFORM_INCOMPLETE, PLATFORM_MALFORMED, PLATFORM_SOURCE_EXCEPTION, PLATFORM_OTHER,
    CODEC_REPORTED, OTHER }

/** Suffix is a fixed label derived from the already queried display name, never the name itself. */
enum class ImportSuffix { NONE, UNKNOWN, JPG, JPEG, PNG, WEBP, HEIC, HEIF, AVIF, DNG, ARW, SR2, SRF, NEF, NRW,
    CR2, CR3, CRW, RAF, ORF, ORI, RW2, RAW, PEF, PTX, SRW, RWL, IIQ, THREE_FR, FFF, MOS, MRW, DCR, KDC, ERF, MEF, TIFF, TIF, GIF, BMP;
    companion object {
        fun fromName(name:String):ImportSuffix {
            val at=name.lastIndexOf('.')
            if(at<0)return NONE
            val suffix=name.substring(at+1)
            if(suffix.length !in 1..5 || !suffix.all{it in 'a'..'z'||it in 'A'..'Z'||it in '0'..'9'})return UNKNOWN
            if(suffix.equals("3fr",true))return THREE_FR
            return entries.firstOrNull{it!=NONE && it!=UNKNOWN && it!=THREE_FR && it.name.equals(suffix,true)}?:UNKNOWN
        }
    }
}

data class ImportDiagnosticInfo(
    val origin: ImportOrigin? = null,
    val phase: ImportPhase? = null,
    /** Successfully written chunks, not a claim that a failed output write wrote no bytes. */
    val copiedBytes: Long? = null,
    val format: PhotoFormat? = null,
    /** Actual selected adapter, not a MIME or filename guess. */
    val route: ImportRoute? = null,
    val failure: ImportFailure? = null,
    val suffix:ImportSuffix?=null,
    val sourceBytes:Long?=null,
    val sizeHintBytes:Long?=null,
    val width:Int?=null,val height:Int?=null,
    val memoryStage:ImportMemoryStage?=null,
    val memory:ImportMemoryInfo?=null,
    val decodedRawFormat:dev.filmroom.codec.RawFormat?=null,
    val originalPhotoGrant:OriginalPhotoGrant?=null,
    val gps:GpsDiagnosticInfo?=null,
) {
    internal fun fields(): Map<String, Any> = buildMap {
        if(origin!=null || phase!=null){
            put("importByteSizeKnown",sourceBytes?.let{it in 0..MAX_BYTES}==true)
            put("importDimensionsKnown",width?.let{it in 1..1_000_000}==true && height?.let{it in 1..1_000_000}==true)
            put("importFormatKnown",format!=null && format!=PhotoFormat.UNKNOWN)
        }
        origin?.let { put("importOrigin", it.name) }
        originalPhotoGrant?.let { put("importOriginalPhotoGrant", it.name) }
        phase?.let { put("importPhase", it.name) }
        copiedBytes?.takeIf { it in 0..MAX_BYTES }?.let { put("importCopiedBytes", it) }
        format?.let { put("importFormat", it.name);put("importFormatBasis","HEADER_SIGNATURE") }
        decodedRawFormat?.let{put("importDecodedRawFormat",it.name)}
        route?.let { put("importRoute", it.name) }
        failure?.let { put("importFailure", it.name) }
        suffix?.let{put("importSuffix",it.name)}
        sourceBytes?.takeIf{it in 0..MAX_BYTES}?.let{put("importSourceBytes",it)}
        sizeHintBytes?.takeIf{it in 0..(1L shl 40)}?.let{put("importSizeHintBytes",it)}
        width?.takeIf{it in 1..1_000_000}?.let{put("importWidth",it)}
        height?.takeIf{it in 1..1_000_000}?.let{put("importHeight",it)}
        memoryStage?.let{put("importMemoryStage",it.name)}
        memory?.let{putAll(it.fields())}
        gps?.let{putAll(it.fields().mapKeys { entry -> "import"+entry.key.replaceFirstChar(Char::uppercaseChar) })}
    }

    internal companion object {
        private const val MAX_BYTES = 512L * 1024 * 1024
        fun fromFields(fields: Map<String, String>): ImportDiagnosticInfo? {
            fun n(k:String,max:Long,min:Long=0):Long?=fields[k]?.takeIf{it.length<=13 && it.isNotEmpty() && it.all{c->c in '0'..'9'}}?.toLongOrNull()?.takeIf{it in min..max}
            val value = ImportDiagnosticInfo(
                ImportOrigin.entries.firstOrNull { it.name == fields["importOrigin"] },
                ImportPhase.entries.firstOrNull { it.name == fields["importPhase"] },
                fields["importCopiedBytes"]?.takeIf { it.length <= 10 && it.all { c -> c in '0'..'9' } }
                    ?.toLongOrNull()?.takeIf { it in 0..MAX_BYTES },
                PhotoFormat.entries.firstOrNull { it.name == fields["importFormat"] },
                ImportRoute.entries.firstOrNull { it.name == fields["importRoute"] },
                ImportFailure.entries.firstOrNull { it.name == fields["importFailure"] },
                ImportSuffix.entries.firstOrNull{it.name==fields["importSuffix"]},
                n("importSourceBytes",MAX_BYTES),n("importSizeHintBytes",1L shl 40),
                n("importWidth",1_000_000,1)?.toInt(),n("importHeight",1_000_000,1)?.toInt(),
                ImportMemoryStage.entries.firstOrNull{it.name==fields["importMemoryStage"]},ImportMemoryInfo.fromFields(fields),
                dev.filmroom.codec.RawFormat.entries.firstOrNull{it.name==fields["importDecodedRawFormat"]},
                OriginalPhotoGrant.entries.firstOrNull{it.name==fields["importOriginalPhotoGrant"]},
                GpsDiagnosticInfo.fromFields(fields.filterKeys{it.startsWith("importGps")}.mapKeys{it.key.removePrefix("import").replaceFirstChar(Char::lowercaseChar)}),
            )
            return value.takeIf { it.fields().isNotEmpty() }
        }
    }
}
