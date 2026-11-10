package dev.filmroom.livephoto.core

import org.junit.Assert.*
import org.junit.Test

class OppoHonorMimoTest {
    @Test fun oplusDeclaresActualCallerAndCleanInputVersion() {
        val x = Xmp.inspect(Xmp.build(LivePhotoProfile.OPLUS, 123, 123, 0, 456, "dev.filmroom.experiment"))
        assertEquals("dev.filmroom.experiment", x.fields[Xmp.O to "MotionPhotoOwner"])
        assertEquals("1", x.fields[Xmp.O to "OLivePhotoVersion"])
        assertNull(x.fields[Xmp.O to "MotionPhotoFeatureFlag"])
        assertEquals("0", x.fields[Xmp.O to "VideoLength"])
        // Independent reference-reader rule: zero auxiliary length means use Container length.
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }.newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(Xmp.build(LivePhotoProfile.OPLUS,123,123,0,456).toByteArray()))
        val items=doc.getElementsByTagNameNS("http://ns.google.com/photos/1.0/container/", "Item")
        val motion=items.item(1) as org.w3c.dom.Element
        val containerLength=motion.getAttributeNS("http://ns.google.com/photos/1.0/container/item/", "Length").toLong()
        val declared=x.fields[Xmp.O to "VideoLength"]!!.toLong()
        assertEquals(123L, if(declared==0L)containerLength else declared)
        assertEquals("456", x.fields[Xmp.O to "MotionPhotoPrimaryPresentationTimestampUs"])
        assertTrue(runCatching { Xmp.build(LivePhotoProfile.OPLUS,123,123,0,0,"bad\"owner") }.isFailure)
    }
    @Test fun honorMatchesMimoDefaultMetadataBytesExactly() {
        // Independent literal expected bytes from Honor SDK's three padded 20-byte fields.
        val expected = "v2_f00              " + "0:1000              " + "LIVE_143            "
        assertEquals(60, expected.length)
        assertArrayEquals(expected.toByteArray(Charsets.US_ASCII), Honor.tail(123))
    }
}
