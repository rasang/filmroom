package dev.filmroom.io.formats

import org.junit.Assert.*
import org.junit.Test

class FormatCapabilitiesTest {
    @Test fun platformAvailabilityNeverAutomaticallyEnablesUnwiredFormats() {
        val caps = FormatCapabilities.inspect(35, { true })
        assertTrue(caps.importFor(PhotoFormat.JPEG).canAttemptPreview)
        assertTrue(caps.importFor(PhotoFormat.PNG).canAttemptOriginal)
        assertTrue(caps.importFor(PhotoFormat.HEIF).canAttemptOriginal)
        assertTrue(caps.importFor(PhotoFormat.HEIF).usesCanonicalOriginalSpool)
        for (format in listOf(PhotoFormat.WEBP, PhotoFormat.AVIF, PhotoFormat.GIF, PhotoFormat.BMP)) {
            assertTrue(caps.importFor(format).platformDecoderPresent)
            assertFalse(caps.importFor(format).canAttemptPreview)
            assertFalse(caps.importFor(format).canAttemptOriginal)
        }
        assertFalse(caps.exportFor(OutputEncoding.WEBP_LOSSY).canAttemptBoundedBitmap)
    }

    @Test fun unavailableOptionalHeifRemainsDisabledDespiteWiring() {
        val wiring = CodecIntegrations(platformImports = setOf(PhotoFormat.HEIF), platformOriginalImports = setOf(PhotoFormat.HEIF))
        val cap = FormatCapabilities.inspect(35, { false }, wiring).importFor(PhotoFormat.HEIF)
        assertEquals(DecoderSignal.NOT_REPORTED, cap.platformDecoder)
        assertFalse(cap.canAttemptPreview); assertFalse(cap.canAttemptOriginal)
    }

    @Test fun avifFalseOn31IsBaselineOnlyNotProofOfUnsupportedOrHighBitDepth() {
        val wiring = CodecIntegrations(platformImports = setOf(PhotoFormat.AVIF))
        val old = FormatCapabilities.inspect(30, { false }, wiring).importFor(PhotoFormat.AVIF)
        val newer = FormatCapabilities.inspect(31, { false }, wiring).importFor(PhotoFormat.AVIF)
        assertEquals(DecoderSignal.NOT_REPORTED, old.platformDecoder); assertFalse(old.canAttemptPreview)
        assertEquals(DecoderSignal.AVIF_BASELINE_8_BIT_API31, newer.platformDecoder)
        assertFalse(newer.platformDecoderPresent); assertTrue(newer.canAttemptPreview)
        assertFalse(newer.canAttemptOriginal) // preview wiring does not wire original spool.
        assertTrue(newer.requiresFileDecodeValidation)
    }

    @Test fun mimeProbeFailureIsNotReportedAsAvailabilityAndDoesNotLeakItsMessage() {
        val cap = FormatCapabilities.inspect(35, { throw IllegalStateException("private/path/photo.jpg") }).importFor(PhotoFormat.JPEG)
        assertEquals(DecoderSignal.PROBE_FAILED, cap.platformDecoder)
        assertFalse(cap.canAttemptPreview)
        assertFalse(cap.toString().contains("private"))
        assertEquals(DecoderSignal.PROBE_FAILED,
            FormatCapabilities.inspect(35, { throw UnsatisfiedLinkError("secret") }).importFor(PhotoFormat.PNG).platformDecoder)
    }

    @Test fun nativeProbeAndExplicitWiringAreBothRequiredForActualRawOrTiffRoutes() {
        val available = NativeCodecAvailability(imports = setOf(PhotoFormat.DNG, PhotoFormat.TIFF, PhotoFormat.EXR),
            originalImports = setOf(PhotoFormat.DNG), streamingExports = setOf(OutputEncoding.TIFF_16))
        assertFalse(FormatCapabilities.inspect(35, { true }, native = available).importFor(PhotoFormat.DNG).canAttemptPreview)
        val wiring = CodecIntegrations(nativeImports = setOf(PhotoFormat.DNG, PhotoFormat.TIFF, PhotoFormat.EXR),
            nativeOriginalImports = setOf(PhotoFormat.DNG, PhotoFormat.TIFF), streamingExports = setOf(OutputEncoding.TIFF_16))
        val caps = FormatCapabilities.inspect(35, { false }, wiring, available)
        assertTrue(caps.importFor(PhotoFormat.DNG).canAttemptPreview)
        assertTrue(caps.importFor(PhotoFormat.DNG).canAttemptOriginal)
        assertTrue(caps.importFor(PhotoFormat.TIFF).canAttemptPreview)
        assertFalse(caps.importFor(PhotoFormat.TIFF).canAttemptOriginal)
        assertTrue(caps.exportFor(OutputEncoding.TIFF_16).canAttemptOriginalStreaming)
        assertFalse(caps.exportFor(OutputEncoding.TIFF_16).canAttemptBoundedBitmap)
    }

    @Test fun importingHeifDoesNotImplyHeifEncodingAndOriginalIsSeparate() {
        val wiring = CodecIntegrations(platformImports = setOf(PhotoFormat.HEIF), platformOriginalImports = emptySet(), bitmapExports = OutputEncoding.entries.toSet())
        val caps = FormatCapabilities.inspect(35, { true }, wiring)
        assertTrue(caps.importFor(PhotoFormat.HEIF).canAttemptPreview)
        assertFalse(caps.importFor(PhotoFormat.HEIF).canAttemptOriginal)
        assertEquals(BitmapEncoder.NONE, caps.exportFor(OutputEncoding.HEIF).bitmapEncoder)
        assertFalse(caps.exportFor(OutputEncoding.HEIF).canAttemptBoundedBitmap)
        assertFalse(caps.exportFor(OutputEncoding.HEIF).canAttemptOriginalStreaming)
        assertFalse(caps.exportFor(OutputEncoding.AVIF).canAttemptBoundedBitmap)
    }

    @Test fun bitmapBoundedAndOriginalStreamingExportAreNeverConflated() {
        val caps = FormatCapabilities.inspect(35, { true })
        assertTrue(caps.exportFor(OutputEncoding.JPEG).canAttemptBoundedBitmap)
        assertEquals(4096, caps.exportFor(OutputEncoding.JPEG).bitmapMaxEdge)
        assertFalse(caps.exportFor(OutputEncoding.JPEG).canAttemptOriginalStreaming)
        assertTrue(caps.exportFor(OutputEncoding.PNG).canAttemptOriginalStreaming)
    }

    @Test fun android29LegacyWebpQuality100MeansLosslessNotMaximumLossyQuality() {
        assertEquals(BitmapEncoder.WEBP_LEGACY_LOSSY_MAX99, FormatCapabilities.bitmapEncoder(29, OutputEncoding.WEBP_LOSSY))
        assertEquals(BitmapEncoder.WEBP_LEGACY_LOSSLESS_QUALITY100, FormatCapabilities.bitmapEncoder(29, OutputEncoding.WEBP_LOSSLESS))
        assertEquals(BitmapEncoder.WEBP_LOSSY, FormatCapabilities.bitmapEncoder(30, OutputEncoding.WEBP_LOSSY))
        assertEquals(BitmapEncoder.WEBP_LOSSLESS, FormatCapabilities.bitmapEncoder(30, OutputEncoding.WEBP_LOSSLESS))
    }
}
