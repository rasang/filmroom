package dev.filmroom.export

import dev.filmroom.io.ExportFormat
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.imprint.ImprintContent

/** Pixel-payload identity, deliberately NOT a finished-file identity. Current exportMillis is
 * applied only AFTER copying the payload; freezing it here would leak the previous export time.
 * Metadata is still conservatively keyed in full, including resolved original-zone/GPS policy.
 * All glyphs, ICC and compositor code/assets are APK-owned, and the cache never survives a process.
 */
internal object EncodedExportIdentity {
    fun supported(job:ExportJob)=job.format in setOf(ExportFormat.JPEG,ExportFormat.TIFF16,ExportFormat.PNG)
    fun key(job:ExportJob,sourceDigest:String,science:String,metadata:PhotoMetadata):EncodedPayloadCache.Key {
        require(supported(job))
        val imprint=ImprintContent.from(metadata,job.parameters.imprint)
        return EncodedPayloadCache.key("encoded-pixels-v2:png-bare-v1:metadata-before-write:oriented-source-v1:alpha-white",
            sourceDigest,job.inputKind,job.sourceWidth.toString(),job.sourceHeight.toString(),job.maxEdge?.toString(),
            job.width.toString(),job.height.toString(),job.outputWidth.toString(),job.outputHeight.toString(),
            science,job.parameters.json("export",includePresentation=true),job.border?.json(),job.crop?.json(),
            job.format.name,if(job.format==ExportFormat.TIFF16)"16" else "8",job.quality.toString(),job.parameters.outputColorSpace,dev.filmroom.io.OutputColorSpace.parse(job.parameters.outputColorSpace).profileSha256,
            metadata.toJson(),EncodedPayloadCache.key(metadata.exifBytes(job.outputWidth,job.outputHeight,job.parameters.outputColorSpace,0).joinToString(",")).digest,
            imprint.date,imprint.fields.joinToString("|"){"${it.kind}:${it.text}:${it.meteringMode}"})
    }
}
