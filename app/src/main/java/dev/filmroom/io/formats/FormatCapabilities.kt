package dev.filmroom.io.formats

enum class DecoderSignal {
    MIME_REPORTED, AVIF_BASELINE_8_BIT_API31, NOT_REPORTED, PROBE_FAILED, EXTERNAL_CODEC_REQUIRED,
}

enum class OutputEncoding(private val label: () -> String, val format: PhotoFormat) {
    JPEG({"JPEG"}, PhotoFormat.JPEG), PNG({"PNG"}, PhotoFormat.PNG),
    WEBP_LOSSY({dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9064bd4b637b)}, PhotoFormat.WEBP), WEBP_LOSSLESS({dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e0d8c2722d95)}, PhotoFormat.WEBP),
    HEIF({"HEIF / HEIC"}, PhotoFormat.HEIF), AVIF({"AVIF"}, PhotoFormat.AVIF),
    TIFF_16({dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_459f5d31c1d5)}, PhotoFormat.TIFF), EXR_FLOAT({dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4f410de4c30b)}, PhotoFormat.EXR);
    val displayName:String get()=label()
}

enum class BitmapEncoder {
    JPEG, PNG, WEBP_LEGACY_LOSSY_MAX99, WEBP_LEGACY_LOSSLESS_QUALITY100,
    WEBP_LOSSY, WEBP_LOSSLESS, NONE,
}

/** Explicit wiring, not inferred from platform version or a library being packaged. */
data class CodecIntegrations(
    val platformImports: Set<PhotoFormat> = setOf(PhotoFormat.JPEG, PhotoFormat.PNG, PhotoFormat.HEIF),
    val platformOriginalImports: Set<PhotoFormat> = setOf(PhotoFormat.JPEG, PhotoFormat.PNG, PhotoFormat.HEIF),
    val nativeImports: Set<PhotoFormat> = emptySet(),
    val nativeOriginalImports: Set<PhotoFormat> = emptySet(),
    val bitmapExports: Set<OutputEncoding> = setOf(OutputEncoding.JPEG, OutputEncoding.PNG),
    val streamingExports: Set<OutputEncoding> = setOf(OutputEncoding.PNG),
) {
    init {
        require(listOf(platformImports, platformOriginalImports, nativeImports, nativeOriginalImports)
            .none { PhotoFormat.UNKNOWN in it }) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bec7754b9d7d) }
    }
}

/** Populate only from a successful, typed native capability probe, never a file extension. */
data class NativeCodecAvailability(
    val imports: Set<PhotoFormat> = emptySet(),
    val originalImports: Set<PhotoFormat> = emptySet(),
    val streamingExports: Set<OutputEncoding> = emptySet(),
) {
    init { require(PhotoFormat.UNKNOWN !in imports && PhotoFormat.UNKNOWN !in originalImports) }
}

data class ImportCapability(
    val format: PhotoFormat,
    val platformDecoder: DecoderSignal,
    val platformImplemented: Boolean,
    val nativeImplemented: Boolean,
    val nativeDecoderPresent: Boolean,
    val originalPlatformImplemented: Boolean,
    val originalNativeImplemented: Boolean,
    val nativeOriginalDecoderPresent: Boolean,
    val regionDecoderDocumented: Boolean,
) {
    /** A format report is never proof that this particular file/profile/size is decodable. */
    val platformDecoderPresent: Boolean get() = platformDecoder == DecoderSignal.MIME_REPORTED
    val canAttemptPreview: Boolean get() =
        (platformImplemented && (platformDecoderPresent || platformDecoder == DecoderSignal.AVIF_BASELINE_8_BIT_API31)) ||
            (nativeImplemented && nativeDecoderPresent)
    val canAttemptOriginal: Boolean get() =
        (platformImplemented && originalPlatformImplemented && regionDecoderDocumented &&
            (platformDecoderPresent || platformDecoder == DecoderSignal.AVIF_BASELINE_8_BIT_API31)) ||
            (nativeImplemented && originalNativeImplemented && nativeDecoderPresent && nativeOriginalDecoderPresent)
    val requiresFileDecodeValidation: Boolean get() = true
    /** HEIF is decoded once under admission to a canonical full-size spool, NOT region decoded. */
    val usesCanonicalOriginalSpool: Boolean get() = format == PhotoFormat.HEIF && originalPlatformImplemented
    val requiresOriginalRegionAndOrientationValidation: Boolean get() = !usesCanonicalOriginalSpool
}

data class EncodeCapability(
    val encoding: OutputEncoding,
    val bitmapEncoder: BitmapEncoder,
    val bitmapImplemented: Boolean,
    val streamingImplemented: Boolean,
    val streamingEncoderPresent: Boolean,
) {
    val canAttemptBoundedBitmap: Boolean get() = bitmapImplemented && bitmapEncoder != BitmapEncoder.NONE
    val canAttemptOriginalStreaming: Boolean get() = streamingImplemented && streamingEncoderPresent
    val bitmapMaxEdge: Int? get() = if (canAttemptBoundedBitmap) 4096 else null
}

data class FormatCapabilitySnapshot(val imports: List<ImportCapability>, val exports: List<EncodeCapability>) {
    fun importFor(format: PhotoFormat): ImportCapability = imports.first { it.format == format }
    fun exportFor(encoding: OutputEncoding): EncodeCapability = exports.first { it.encoding == encoding }
}

/** Pure policy: unit tests inject the MIME query; Android's real query lives in a separate adapter. */
object FormatCapabilities {
    fun inspect(
        api: Int,
        mimeSupported: (String) -> Boolean,
        integrations: CodecIntegrations = CodecIntegrations(),
        native: NativeCodecAvailability = NativeCodecAvailability(),
    ): FormatCapabilitySnapshot {
        require(api >= 29) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_19220b804ef8) }
        val imports = PhotoFormat.entries.map { format ->
            val signal = if (format.platformMime == null) DecoderSignal.EXTERNAL_CODEC_REQUIRED else try {
                if (mimeSupported(format.platformMime)) DecoderSignal.MIME_REPORTED
                // Official platform table: false may still permit AVIF baseline 8-bit on API31+.
                else if (format == PhotoFormat.AVIF && api >= 31) DecoderSignal.AVIF_BASELINE_8_BIT_API31
                else DecoderSignal.NOT_REPORTED
            } catch (_: RuntimeException) { DecoderSignal.PROBE_FAILED
            } catch (_: LinkageError) { DecoderSignal.PROBE_FAILED }
            val regionDocumented = format in setOf(PhotoFormat.JPEG, PhotoFormat.PNG, PhotoFormat.WEBP, PhotoFormat.HEIF) ||
                (format == PhotoFormat.AVIF && api >= 31)
            ImportCapability(format, signal,
                format in integrations.platformImports, format in integrations.nativeImports,
                format in native.imports, format in integrations.platformOriginalImports,
                format in integrations.nativeOriginalImports, format in native.originalImports, regionDocumented)
        }
        val exports = OutputEncoding.entries.map { encoding ->
            EncodeCapability(encoding, bitmapEncoder(api, encoding), encoding in integrations.bitmapExports,
                encoding in integrations.streamingExports,
                encoding == OutputEncoding.PNG || encoding in native.streamingExports)
        }
        return FormatCapabilitySnapshot(imports, exports)
    }

    fun bitmapEncoder(api: Int, encoding: OutputEncoding): BitmapEncoder = when (encoding) {
        OutputEncoding.JPEG -> BitmapEncoder.JPEG
        OutputEncoding.PNG -> BitmapEncoder.PNG
        OutputEncoding.WEBP_LOSSY -> if (api >= 30) BitmapEncoder.WEBP_LOSSY else
            if (api >= 29) BitmapEncoder.WEBP_LEGACY_LOSSY_MAX99 else BitmapEncoder.NONE
        OutputEncoding.WEBP_LOSSLESS -> if (api >= 30) BitmapEncoder.WEBP_LOSSLESS else
            if (api >= 29) BitmapEncoder.WEBP_LEGACY_LOSSLESS_QUALITY100 else BitmapEncoder.NONE
        // MediaCodec/HeifWriter availability is NOT Bitmap.compress availability.
        else -> BitmapEncoder.NONE
    }
}

object FormatExplanation {
    val HEADER_ONLY get()=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a19839fd3ffd)
    val PLATFORM_SDR get()=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_55b7686e7779)
    val ANIMATION get()=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_066d69f4c0e2)
    val ORIGINAL get()=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0699e8795e4f)
    val NATIVE_PRECISION get()=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e12fc1fdd16b)

    fun importStatus(capability: ImportCapability): String = when {
        capability.canAttemptPreview -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b1ce081679c3)
        !capability.platformImplemented && !capability.nativeImplemented -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_27cd59975564)
        capability.platformDecoder == DecoderSignal.PROBE_FAILED -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e2c0354dd326)
        else -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_cb55532a0726)
    }

    fun exportStatus(capability: EncodeCapability): String = when {
        capability.canAttemptOriginalStreaming -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b6b940d93867)
        capability.canAttemptBoundedBitmap -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e3c99cd869ac)
        else -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f3c2509899ee)
    }
}
