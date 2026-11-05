package dev.filmroom.io

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.engine.NativeWorkUnavailable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import dev.filmroom.diagnostics.CodecErrorCode
import dev.filmroom.diagnostics.CodecStage

/** Fixed diagnostic enums only. Never copy a color-space name, ICC data or exception text. */
private fun rejectHeic(code:CodecErrorCode,resource:Int,stage:CodecStage=CodecStage.VALIDATE):Nothing =
    throw PhotoException(PhotoException.Reason.UNSUPPORTED,dev.filmroom.i18n.UiMessage.of(resource),
        codecCode=code,codecStage=stage)

/** A conservative admission estimate, NOT a cap on vendor decoder/driver allocations. */
internal object HeicMemoryPolicy {
    const val MAX_PIXELS = 52_000_000L
    const val FIXED_BYTES = 64L * 1024 * 1024
    fun estimatedBytes(info: HeicContainer.Info, encodedBytes:Long=0): Long {
        require(encodedBytes in 0..PhotoLimits.MAX_COMPRESSED_BYTES)
        val pixels = maxOf(info.encodedWidth.toLong() * info.encodedHeight, info.estimatedDecodePixels)
        if (pixels <= 0) throw PhotoException(PhotoException.Reason.UNREADABLE,
            dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_199d9706e7a1))
        if (pixels > MAX_PIXELS) throw PhotoException(PhotoException.Reason.TOO_LARGE,
            dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_56e47360aaca))
        // Original output + decoder frame + orientation intermediate + YUV/reference buffers.
        // Budget for full image even if a platform advertises region/subsample decoding.
        val estimate = Math.addExact(Math.addExact(FIXED_BYTES, Math.multiplyExact(pixels, 32L)),encodedBytes)
        if(estimate>2L*1024*1024*1024)throw PhotoException(PhotoException.Reason.TOO_LARGE,
            dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_9ff6012fdde9))
        return estimate
    }
}

/** Testable boundary: host tests use marker rows, never pretend they ran Android's HEVC codec. */
internal interface HeicPixels : AutoCloseable {
    val width: Int
    val height: Int
    fun readRow(y: Int, argb: IntArray)
}
internal interface HeicBackend {
    fun supported(): Boolean
    fun decode(file: File, info: HeicContainer.Info, job: CoroutineContext): HeicPixels
}

/** Strict descriptor checks plus a narrow, documented ImageInfo output-report canonicalization. */
internal object HeicColorPolicy {
    /** ImageInfo reports the default OUTPUT color space, not the encoded ICC descriptor.
     * AOSP GraphicsJNI canonicalizes it through ColorSpace.match, while SkCodec retains
     * the encoded ICC for the actual source-to-target transform. Accept only the exact
     * approved rounded P3 descriptor's platform-computed named representation; this does
     * not relabel source pixels, alter ICC tolerances, or replace the decoder's source ICC.
     */
    fun requireHeaderColor(reported:ColorSpace?,expected:HeicIccProfile.Profile) {
        try { requireSource(reported,expected); return }
        catch(error:PhotoException) {
            if(error.codecCode!=CodecErrorCode.HEIC_SOURCE_TRANSFER_MISMATCH ||
                !HeicIccProfile.isRoundedDisplayP3(expected))throw error
            val t=expected.transfer
            val canonical=ColorSpace.match(expected.toXyzD50.values().map{it.toFloat()}.toFloatArray(),
                ColorSpace.Rgb.TransferParameters(t.a,t.b,t.c,t.d,t.e,t.f,t.g))
            // Require the platform's exact named object, never a profile/ColorSpace name string.
            if(canonical!=ColorSpace.get(ColorSpace.Named.DISPLAY_P3) || reported!=canonical)throw error
            // Original matrix/range guards still apply, independently of transfer canonicalization.
            requireSource(reported,expected.copy(transfer=expected.identity.expectedTransfer))
        }
    }
    fun requireSource(sourceColor:ColorSpace?,expected:HeicIccProfile.ColorIdentity=HeicIccProfile.ColorIdentity.SRGB) {
        requireSource(sourceColor,HeicIccProfile.Profile.standard(expected))
    }
    fun requireSource(sourceColor:ColorSpace?,expected:HeicIccProfile.Profile) {
        fun reject(code:CodecErrorCode):Nothing=rejectHeic(code,dev.filmroom.R.string.text_58a07dbdf8d7)
        if(sourceColor !is ColorSpace.Rgb)reject(CodecErrorCode.HEIC_SOURCE_NOT_RGB)
        if((0..2).any { sourceColor.getMinValue(it)<0f || sourceColor.getMaxValue(it)>1f })
            reject(CodecErrorCode.HEIC_SOURCE_RANGE)
        val transfer=sourceColor.transferParameters ?: reject(CodecErrorCode.HEIC_SOURCE_TRANSFER_UNAVAILABLE)
        val actual=doubleArrayOf(transfer.g,transfer.a,transfer.b,transfer.c,transfer.d,transfer.e,transfer.f)
        val declared=expected.transfer
        val wanted=doubleArrayOf(declared.g,declared.a,declared.b,declared.c,declared.d,declared.e,declared.f)
        if(actual.indices.any { !actual[it].isFinite() || kotlin.math.abs(actual[it]-wanted[it])>HeicIccProfile.TRANSFER_TOLERANCE })reject(CodecErrorCode.HEIC_SOURCE_TRANSFER_MISMATCH)
        // ICC XYZ tags are D50 PCS values; Android RGB transforms use their declared white.
        // Adapt for comparison ONLY. ImageDecoder performs the real source→target conversion.
        val adapted=ColorSpace.adapt(sourceColor,ColorSpace.ILLUMINANT_D50) as? ColorSpace.Rgb ?: reject(CodecErrorCode.HEIC_SOURCE_NOT_RGB)
        val transform=adapted.transform
        val matrix=expected.toXyzD50.values()
        if(transform.indices.any { !transform[it].isFinite() || kotlin.math.abs(transform[it]-matrix[it])>HeicIccProfile.PLATFORM_MATRIX_TOLERANCE })reject(CodecErrorCode.HEIC_SOURCE_MATRIX_MISMATCH)
    }
}

/** Only for a separately validated SDR8 primary, never a general HDR -> SDR converter.
 * AOSP ImageDecoder decodes base pixels then attaches a separate gainmap; Bitmap.getPixels
 * reads those base pixels. Removing the attachment changes no pixels and prevents later
 * HDR Canvas rendering. See docs/HEIC_IMPORT.md for official contracts and runtime limits.
 */
internal object HeicSdrBasePolicy {
    // Public Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR (API36), documented value 0.
    private const val SDR_TO_HDR = 0
    private fun reject(code:CodecErrorCode):Nothing=rejectHeic(code,dev.filmroom.R.string.text_986742c74398)
    internal fun requireSdrDirection(direction:Int?) {
        if(direction==null)reject(CodecErrorCode.HEIC_GAINMAP_DIRECTION_UNAVAILABLE)
        if(direction!=SDR_TO_HDR)reject(CodecErrorCode.HEIC_GAINMAP_DIRECTION_UNSUPPORTED)
    }
    fun prepare(bitmap:Bitmap) {
        if(bitmap.config!=Bitmap.Config.ARGB_8888)reject(CodecErrorCode.HEIC_OUTPUT_CONFIG)
        if(bitmap.colorSpace?.isSrgb!=true)reject(CodecErrorCode.HEIC_OUTPUT_COLOR)
        if(android.os.Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap()) {
            if(android.os.Build.VERSION.SDK_INT>=36) {
                // Public API added in 36; compileSdk remains 35. Never guess if unavailable.
                val direction=try {
                    bitmap.gainmap?.let { it.javaClass.getMethod("getGainmapDirection").invoke(it) as? Int }
                } catch(_:ReflectiveOperationException) { null } catch(_:SecurityException) { null }
                requireSdrDirection(direction)
            }
            bitmap.setGainmap(null)
            if(bitmap.hasGainmap())reject(CodecErrorCode.HEIC_GAINMAP_REMOVE_FAILED)
        }
    }
}

/** Split the existing compound guards only to retain a bounded, actionable reason. */
internal object HeicPlatformPolicy {
    fun requireHeader(mime:String,animated:Boolean,width:Int,height:Int,info:HeicContainer.Info) {
        fun reject(code:CodecErrorCode):Nothing=rejectHeic(code,dev.filmroom.R.string.text_8b96428c13f4,CodecStage.DECODE)
        if(mime !in setOf("image/heif","image/heic"))reject(CodecErrorCode.HEIC_HEADER_MIME)
        if(animated)reject(CodecErrorCode.HEIC_HEADER_ANIMATED)
        if(width!=info.displayWidth || height!=info.displayHeight)reject(CodecErrorCode.HEIC_HEADER_SIZE)
    }
    fun requireOutput(result:Bitmap,info:HeicContainer.Info) {
        fun reject(code:CodecErrorCode):Nothing=rejectHeic(code,dev.filmroom.R.string.text_1e6cd3983af1,CodecStage.DECODE)
        if(result.width!=info.displayWidth || result.height!=info.displayHeight)reject(CodecErrorCode.HEIC_OUTPUT_SIZE)
        if(result.config!=Bitmap.Config.ARGB_8888)reject(CodecErrorCode.HEIC_OUTPUT_CONFIG)
        if(result.colorSpace?.isSrgb!=true)reject(CodecErrorCode.HEIC_OUTPUT_COLOR)
    }
}

internal object AndroidHeicBackend : HeicBackend {
    override fun supported() = ImageDecoder.isMimeTypeSupported("image/heif")
    override fun decode(file: File, info: HeicContainer.Info, job: CoroutineContext): HeicPixels {
        var bitmap: Bitmap? = null
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, header, _ ->
                job.ensureActive()
                HeicPlatformPolicy.requireHeader(header.mimeType,header.isAnimated,header.size.width,header.size.height,info)
                HeicColorPolicy.requireHeaderColor(header.colorSpace,info.sourceProfile)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.isMutableRequired = false
                decoder.setOnPartialImageListener { false }
                // Intentionally NO target size/sample/crop. ImageDecoder owns container rotation once.
            }
            job.ensureActive()
            val result = checkNotNull(bitmap)
            HeicPlatformPolicy.requireOutput(result,info)
            HeicSdrBasePolicy.prepare(result)
            bitmap = null
            return object : HeicPixels {
                override val width = result.width
                override val height = result.height
                override fun readRow(y: Int, argb: IntArray) = result.getPixels(argb, 0, width, 0, y, width, 1)
                override fun close() = result.recycle()
            }
        } catch(e:ImageDecoder.DecodeException) {
            throw PhotoException(PhotoException.Reason.UNREADABLE,
                dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_a064cfbab736),e,
                codecCode=CodecErrorCode.HEIC_PLATFORM_DECODE_FAILED,codecStage=CodecStage.DECODE)
        } finally { bitmap?.recycle() }
    }
}

/** Decode once, then every preview/export reads this identical display-oriented linear SDR source.
 * Unlike OriginalSpool, no JPEG-style EXIF remapping or region-memory assumption is used here.
 */
internal class HeicImport(
    private val memory: () -> NativeMemorySnapshot,
    private val backend: HeicBackend = AndroidHeicBackend,
) {
    suspend fun write(file: File, info: HeicContainer.Info, target: File, job: CoroutineContext) {
        job.ensureActive()
        if (!backend.supported()) rejectHeic(CodecErrorCode.HEIC_NO_PLATFORM_DECODER,
            dev.filmroom.R.string.text_dfe135051811,CodecStage.DECODE)
        val estimate = HeicMemoryPolicy.estimatedBytes(info,file.length())
        val expected = LinearRgb.byteCount(info.displayWidth, info.displayHeight)
        if (target.parentFile!!.usableSpace < expected + PhotoLimits.IMPORT_RESERVE_BYTES) {
            throw PhotoException(PhotoException.Reason.STORAGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_ba94a97c604b))
        }
        require(!target.exists())
        var created = false
        var completed = false
        try {
            NativeWorkReservations.run(estimate, memory) {
                job.ensureActive()
                backend.decode(file, info, job).use { pixels ->
                    job.ensureActive()
                    if (pixels.width != info.displayWidth || pixels.height != info.displayHeight)
                        throw IOException("HEIC decoder changed dimensions")
                    check(target.createNewFile()); created = true
                    FileOutputStream(target).use { output ->
                        output.buffered(256 * 1024).use { buffered ->
                            val colors = IntArray(pixels.width)
                            val row = ByteBuffer.allocate(pixels.width * 12).order(ByteOrder.LITTLE_ENDIAN)
                            repeat(pixels.height) { y ->
                                job.ensureActive()
                                pixels.readRow(y, colors)
                                row.clear()
                                for (color in colors) {
                                    val alpha = color ushr 24
                                    for (channel in 2 downTo 0) {
                                        val shift = channel * 8
                                        // Explicit opaque-white policy, same as ordinary raster imports.
                                        val value = (((color ushr shift) and 255) * alpha + 255 * (255 - alpha) + 127) / 255
                                        row.putFloat(LinearRgb.srgb8ToLinear[value])
                                    }
                                }
                                buffered.write(row.array())
                            }
                            job.ensureActive(); buffered.flush(); output.fd.sync()
                        }
                    }
                }
            }
            job.ensureActive()
            if (target.length() != expected) throw IOException("Incomplete HEIC linear source")
            completed = true
        } catch (_: NativeWorkUnavailable) {
            throw PhotoException(PhotoException.Reason.TOO_LARGE,
                dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_f1632bf9ae5b))
        } catch (error: OutOfMemoryError) {
            throw PhotoException(PhotoException.Reason.TOO_LARGE,
                dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_389695202ee7), error)
        } finally { if (!completed && created) target.delete() }
    }
}
