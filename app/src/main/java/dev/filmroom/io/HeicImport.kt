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

/** A conservative admission estimate, NOT a cap on vendor decoder/driver allocations. */
internal object HeicMemoryPolicy {
    const val MAX_PIXELS = 52_000_000L
    const val FIXED_BYTES = 64L * 1024 * 1024
    fun estimatedBytes(info: HeicContainer.Info, encodedBytes:Long=0): Long {
        require(encodedBytes in 0..PhotoLimits.MAX_COMPRESSED_BYTES)
        val pixels = maxOf(info.encodedWidth.toLong() * info.encodedHeight, info.estimatedDecodePixels)
        if (pixels !in 1..MAX_PIXELS) throw PhotoException(PhotoException.Reason.TOO_LARGE,
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

/** Source metadata is checked BEFORE setting an sRGB output request; target tags are not evidence. */
internal object HeicColorPolicy {
    fun requireSource(sourceColor:ColorSpace?,expected:HeicIccProfile.ColorIdentity=HeicIccProfile.ColorIdentity.SRGB) {
        fun reject():Nothing=throw PhotoException(PhotoException.Reason.UNSUPPORTED,
            dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_58a07dbdf8d7))
        if(sourceColor !is ColorSpace.Rgb || (0..2).any {
                sourceColor.getMinValue(it)<0f || sourceColor.getMaxValue(it)>1f }) reject()
        val transfer=sourceColor.transferParameters ?: reject()
        val actual=doubleArrayOf(transfer.g,transfer.a,transfer.b,transfer.c,transfer.d,transfer.e,transfer.f)
        val declared=expected.expectedTransfer
        val wanted=doubleArrayOf(declared.g,declared.a,declared.b,declared.c,declared.d,declared.e,declared.f)
        if(actual.indices.any { !actual[it].isFinite() || kotlin.math.abs(actual[it]-wanted[it])>HeicIccProfile.TRANSFER_TOLERANCE })reject()
        // ICC XYZ tags are D50 PCS values; Android RGB transforms use their declared white.
        // Adapt for comparison ONLY. ImageDecoder performs the real source→target conversion.
        val adapted=ColorSpace.adapt(sourceColor,ColorSpace.ILLUMINANT_D50) as? ColorSpace.Rgb ?: reject()
        val transform=adapted.transform
        val matrix=expected.expectedToXyzD50
        if(transform.indices.any { !transform[it].isFinite() || kotlin.math.abs(transform[it]-matrix[it])>HeicIccProfile.PLATFORM_MATRIX_TOLERANCE })reject()
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
    private fun reject():Nothing=throw PhotoException(PhotoException.Reason.UNSUPPORTED,
        dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_986742c74398))
    internal fun requireSdrDirection(direction:Int?) { if(direction!=SDR_TO_HDR)reject() }
    fun prepare(bitmap:Bitmap) {
        if(bitmap.config!=Bitmap.Config.ARGB_8888 || bitmap.colorSpace?.isSrgb!=true)reject()
        if(android.os.Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap()) {
            if(android.os.Build.VERSION.SDK_INT>=36) {
                // Public API added in 36; compileSdk remains 35. Never guess if unavailable.
                val direction=try {
                    bitmap.gainmap?.let { it.javaClass.getMethod("getGainmapDirection").invoke(it) as? Int }
                } catch(_:ReflectiveOperationException) { null } catch(_:SecurityException) { null }
                requireSdrDirection(direction)
            }
            bitmap.setGainmap(null)
            if(bitmap.hasGainmap())reject()
        }
    }
}

internal object AndroidHeicBackend : HeicBackend {
    override fun supported() = ImageDecoder.isMimeTypeSupported("image/heif")
    override fun decode(file: File, info: HeicContainer.Info, job: CoroutineContext): HeicPixels {
        var bitmap: Bitmap? = null
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, header, _ ->
                job.ensureActive()
                if (header.mimeType !in setOf("image/heif", "image/heic") || header.isAnimated ||
                    header.size.width != info.displayWidth || header.size.height != info.displayHeight) {
                    throw PhotoException(PhotoException.Reason.UNSUPPORTED,
                        dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_8b96428c13f4))
                }
                HeicColorPolicy.requireSource(header.colorSpace,info.sourceColor)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.isMutableRequired = false
                decoder.setOnPartialImageListener { false }
                // Intentionally NO target size/sample/crop. ImageDecoder owns container rotation once.
            }
            job.ensureActive()
            val result = checkNotNull(bitmap)
            if (result.width != info.displayWidth || result.height != info.displayHeight ||
                result.config != Bitmap.Config.ARGB_8888 || result.colorSpace?.isSrgb != true) {
                throw PhotoException(PhotoException.Reason.UNSUPPORTED,
                    dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_1e6cd3983af1))
            }
            HeicSdrBasePolicy.prepare(result)
            bitmap = null
            return object : HeicPixels {
                override val width = result.width
                override val height = result.height
                override fun readRow(y: Int, argb: IntArray) = result.getPixels(argb, 0, width, 0, y, width, 1)
                override fun close() = result.recycle()
            }
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
        if (!backend.supported()) throw PhotoException(PhotoException.Reason.UNSUPPORTED,
            dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_dfe135051811))
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
