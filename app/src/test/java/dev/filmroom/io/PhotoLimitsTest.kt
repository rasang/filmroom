package dev.filmroom.io

import org.junit.Assert.*
import org.junit.Test

class PhotoLimitsTest {
    @Test fun sourceAdmissionKeepsInvalidEdgeAndPixelFailuresDistinct() {
        assertNull(PhotoLimits.sourceFailure(15000,10000))
        assertNull(PhotoLimits.sourceFailure(30000,1))
        assertEquals(PhotoLimits.SourceFailure.INVALID,PhotoLimits.sourceFailure(0,30001))
        assertEquals(PhotoLimits.SourceFailure.EDGE,PhotoLimits.sourceFailure(30001,1))
        assertEquals(PhotoLimits.SourceFailure.EDGE,PhotoLimits.sourceFailure(1,30001))
        assertEquals(PhotoLimits.SourceFailure.EDGE,PhotoLimits.sourceFailure(Long.MAX_VALUE,Long.MAX_VALUE))
        assertEquals(PhotoLimits.SourceFailure.PIXELS,PhotoLimits.sourceFailure(15000,10001))
        assertTrue(PhotoLimits.acceptsCopiedBytes(PhotoLimits.MAX_COMPRESSED_BYTES-1,1))
        assertFalse(PhotoLimits.acceptsCopiedBytes(PhotoLimits.MAX_COMPRESSED_BYTES,1))
    }

    @Test fun recognizesOnlyJpegAndPngSignatures() {
        assertEquals("image/jpeg", PhotoLimits.supportedMime(byteArrayOf(-1, -40, -1, 0)))
        assertEquals("image/png", PhotoLimits.supportedMime(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        assertNull(PhotoLimits.supportedMime("GIF89a".toByteArray()))
        assertNull(PhotoLimits.supportedMime(byteArrayOf(-1, -40)))
        assertNull(PhotoLimits.supportedMime(ByteArray(8), -1))
    }
    @Test fun boundsDecodedSizeWithoutUpsampling() {
        assertEquals(PhotoLimits.Dimensions(1280, 960), PhotoLimits.fit(4000, 3000, 1280))
        assertEquals(PhotoLimits.Dimensions(960, 1280), PhotoLimits.fit(3000, 4000, 1280))
        assertEquals(PhotoLimits.Dimensions(400, 300), PhotoLimits.fit(400, 300, 1280))
        assertEquals(PhotoLimits.Dimensions(1, 1280), PhotoLimits.fit(1, 30000, 1280))
    }
    @Test fun rejectsPixelBombsAndInvalidDimensions() {
        assertTrue(PhotoLimits.validSource(12000, 12000))
        assertFalse(PhotoLimits.validSource(15000, 15000))
        assertFalse(PhotoLimits.validSource(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(PhotoLimits.validSource(0, 200))
        assertFalse(PhotoLimits.validSource(-1, 200))
    }
    @Test fun providerNamesCannotBecomePathsOrControlText() {
        assertEquals("image.jpg", PhotoLimits.safeDisplayName("../../image.jpg"))
        assertEquals("abc.png", PhotoLimits.safeDisplayName("C:\\private\\abc.png"))
        assertEquals("照片", PhotoLimits.safeDisplayName("\n\r"))
        assertEquals("photo.jpg", PhotoLimits.safeDisplayName("photo\u0000.jpg"))
    }

    @Test fun highEntropyNominal50MegapixelPngFitsTheCompressedCap() {
        // Actual independently generated RGB8/RGBA8 PNG fixture sizes.
        assertTrue(PhotoLimits.acceptsCopiedBytes(0, 151_074_877))
        assertTrue(PhotoLimits.acceptsCopiedBytes(0, 201_431_103))
        // 16-bit RGBA payload plus conservative PNG overhead also fits.
        assertTrue(8192L * 6144 * 8 + 1024 * 1024 < PhotoLimits.MAX_COMPRESSED_BYTES)
    }

    @Test fun streamingCopyLimitHasExactBoundaryAndNoOverflow() {
        val limit = PhotoLimits.MAX_COMPRESSED_BYTES
        assertTrue(PhotoLimits.acceptsCopiedBytes(limit - 65_536, 65_536))
        assertTrue(PhotoLimits.acceptsCopiedBytes(limit, 0))
        assertFalse(PhotoLimits.acceptsCopiedBytes(limit, 1))
        assertFalse(PhotoLimits.acceptsCopiedBytes(Long.MAX_VALUE, 65_536))
        assertFalse(PhotoLimits.acceptsCopiedBytes(-1, 1))
        assertFalse(PhotoLimits.acceptsCopiedBytes(0, -1))
    }

    @Test fun importSpaceReserves16MiBForKnownAndUnknownSizes() {
        val reserve = PhotoLimits.IMPORT_RESERVE_BYTES
        val interval = PhotoLimits.IMPORT_SPACE_CHECK_BYTES
        assertEquals(reserve + interval, PhotoLimits.importRequiredSpace(null))
        assertEquals(reserve + interval, PhotoLimits.importRequiredSpace(-1))
        assertTrue(PhotoLimits.hasImportSpace(reserve + 201_431_103, 201_431_103))
        assertFalse(PhotoLimits.hasImportSpace(reserve + 201_431_102, 201_431_103))
        assertFalse(PhotoLimits.hasImportSpace(reserve + interval - 1, null))
        assertEquals(reserve + PhotoLimits.MAX_COMPRESSED_BYTES, PhotoLimits.importRequiredSpace(Long.MAX_VALUE))
        assertEquals(0L, PhotoLimits.copyWindowBytes(reserve - 1))
        assertEquals(0L, PhotoLimits.copyWindowBytes(reserve))
        assertEquals(65_535L, PhotoLimits.copyWindowBytes(reserve + 65_535))
        assertEquals(interval, PhotoLimits.copyWindowBytes(Long.MAX_VALUE))
    }
}
