package dev.filmroom.io.importing

import dev.filmroom.io.*
import dev.filmroom.io.formats.PhotoHeaders
import dev.filmroom.codec.PhotoCodecs
import android.graphics.Bitmap
import java.io.File
import kotlin.coroutines.CoroutineContext
import org.json.JSONObject

/** Bounded content probe (the caller must not mutate its prefix). File names and provider MIME hints never select a decoder. */
internal class PhotoInputHeader(val bytes: ByteArray, val count: Int) {
    init { require(count in 0..minOf(bytes.size, PhotoHeaders.MAX_PREFIX_BYTES)) }
    val rasterMime = PhotoLimits.supportedMime(bytes, count)
    val identified = PhotoHeaders.sniff(bytes, count)
    // Classic TIFF only: recognizing a BigTIFF container does not imply DNG decoder support.
    val isPanasonicRw2 = count >= 4 && bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x55.toByte() && bytes[3] == 0.toByte()
    val isClassicTiff = count >= 4 && ((bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 42.toByte() && bytes[3] == 0.toByte()) ||
        (bytes[0] == 0x4d.toByte() && bytes[1] == 0x4d.toByte() && bytes[2] == 0.toByte() && bytes[3] == 42.toByte()))
}

/** Ownership/admission/cancellation remain with the repository and each existing codec backend. */
internal data class PhotoImportRequest(
    val file: File, val name: String, val maxEdge: Int, val job: CoroutineContext,
    val existingRaw: File?, val allowDecode: Boolean, val onNewRaw: (File) -> Unit,
    val sourceDirectory: File, val captureMetadata: PhotoMetadata,
)

internal interface PhotoInputAdapter {
    fun accepts(header: PhotoInputHeader): Boolean
    suspend fun load(request: PhotoImportRequest, header: PhotoInputHeader): LoadedPhoto
}

/** Exactly one route is chosen before decoding; a corrupt/unsupported variant never falls back. */
internal class PhotoInputAdapters(adapters: List<PhotoInputAdapter>) {
    private val adapters = adapters.toList()
    fun select(header: PhotoInputHeader): PhotoInputAdapter {
        val matches = adapters.filter { it.accepts(header) }
        check(matches.size <= 1) { "Ambiguous photo input adapters" }
        return matches.singleOrNull() ?: throw PhotoException(PhotoException.Reason.UNSUPPORTED,
            dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_f6e0e231129a))
    }
    suspend fun load(request: PhotoImportRequest, header: PhotoInputHeader): LoadedPhoto =
        select(header).load(request, header)
}

internal data class RasterImportPreview(val bitmap: Bitmap, val width: Int, val height: Int, val alpha: Boolean)
internal fun interface RasterPreviewDecoder {
    suspend fun decode(file: File, maxEdge: Int, job: CoroutineContext): RasterImportPreview
}
internal interface RawSourceDecoder {
    suspend fun probe(file: File): JSONObject
    suspend fun decode(file: File, target: File): JSONObject
}
internal class NativeRawSourceDecoder(private val codecs: PhotoCodecs): RawSourceDecoder {
    override suspend fun probe(file: File) = codecs.probe(file)
    override suspend fun decode(file: File, target: File) = codecs.decode(file, target)
}
