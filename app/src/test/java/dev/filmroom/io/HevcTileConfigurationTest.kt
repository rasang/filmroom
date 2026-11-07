package dev.filmroom.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Header-admission tests, not a claim that the synthetic CABAC bytes decode into an image. */
class HevcTileConfigurationTest {
    private data class Shape(
        val width: Int = 64, val height: Int = 64, val chroma: Int = 1,
        val depth: Int = 0, val buffering: Int = 4, val reorder: Int = 2,
        val spsBuffering: Int = buffering, val vpsId: Int = 0,
        val spsVpsId: Int = vpsId, val spsId: Int = 0,
        val ppsSpsId: Int = spsId, val ppsId: Int = 0,
        val dependent: Boolean = false, val layers: Int = 0,
        val sublayers: Int = 0, val crop: Int = 0,
        val vpsNested: Boolean = true, val spsNested: Boolean = true,
        val extension: Boolean = false, val scaling: Boolean = false,
        val internalTiles: Boolean = false, val wpp: Boolean = false,
        val shortRps: Int = 0, val negativeRps: Int = 0, val positiveRps: Int = 0,
        val longTermRps: Boolean = false,
        val profileId: Int = 1,
    )

    private class Writer {
        private val bits = StringBuilder()
        fun n(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.append((value ushr i) and 1) }
        fun flag(value: Boolean) = n(if (value) 1 else 0, 1)
        fun ue(value: Int) {
            val encoded = value + 1
            val count = 32 - Integer.numberOfLeadingZeros(encoded)
            repeat(count - 1) { n(0, 1) }
            n(encoded, count)
        }
        fun se(value: Int) = ue(if (value <= 0) -2 * value else 2 * value - 1)
        fun raw(values: ByteArray) { values.forEach { n(it.toInt() and 255, 8) } }
        fun align() { n(1, 1); while (bits.length % 8 != 0) n(0, 1) }
        fun bytes(): ByteArray {
            require(bits.length % 8 == 0)
            return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        }
    }

    private fun write(block: Writer.() -> Unit) = Writer().apply(block).bytes()
    private fun ptl(id: Int = 1) = byteArrayOf(id.toByte(), 0x60, 0, 0, 0, 0x90.toByte(), 0, 0, 0, 0, 0, 30)
    private fun escape(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (value in bytes) {
            val n = value.toInt() and 255
            if (zeros == 2 && n <= 3) { out.write(3); zeros = 0 }
            out.write(n)
            zeros = if (n == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
    private fun nal(type: Int, bytes: ByteArray) = byteArrayOf((type shl 1).toByte(), 1) + escape(bytes)
    private fun sets(s: Shape = Shape()): List<ByteArray> {
        fun Writer.order(buffering: Int) { flag(true); ue(buffering); ue(s.reorder); ue(5) }
        val vps = nal(32, write {
            n(s.vpsId, 4); n(3, 2); n(s.layers, 6); n(s.sublayers, 3); flag(s.vpsNested); n(0xffff, 16)
            raw(ptl(s.profileId)); order(s.buffering); n(0, 6); ue(0); flag(false); flag(false); align()
        })
        val sps = nal(33, write {
            n(s.spsVpsId, 4); n(s.sublayers, 3); flag(s.spsNested); raw(ptl(s.profileId))
            ue(s.spsId); ue(s.chroma); ue(s.width); ue(s.height)
            flag(s.crop != 0); if (s.crop != 0) { ue(0); ue(s.crop); ue(0); ue(0) }
            ue(s.depth); ue(s.depth); ue(4); order(s.spsBuffering)
            ue(0); ue(3); ue(0); ue(3); ue(0); ue(0)
            flag(s.scaling); flag(false); flag(false); flag(false)
            ue(s.shortRps)
            repeat(s.shortRps) { index ->
                if (index != 0) flag(false) // explicit, not predicted
                ue(s.negativeRps); ue(s.positiveRps)
                repeat(s.negativeRps) { ue(0); flag(true) }
                repeat(s.positiveRps) { ue(0); flag(true) }
            }
            flag(s.longTermRps); if (s.longTermRps) ue(0)
            flag(false); flag(false); flag(false); flag(s.extension); align()
        })
        val pps = nal(34, write {
            ue(s.ppsId); ue(s.ppsSpsId); flag(s.dependent); flag(false); n(0, 3)
            flag(false); flag(false); ue(0); ue(0); se(0)
            flag(false); flag(false); flag(false); se(0); se(0); flag(false); n(0, 3)
            flag(s.internalTiles); flag(s.wpp); flag(false); flag(false); flag(false)
            flag(false); ue(0); flag(false); flag(false); align()
        })
        return listOf(vps, sps, pps)
    }
    private fun config(s: Shape = Shape(), parameters: List<ByteArray> = sets(s), lengthSize: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1) + ptl(s.profileId) + byteArrayOf(
            0xf0.toByte(), 0, 0xfc.toByte(), (0xfc or s.chroma).toByte(),
            (0xf8 or s.depth).toByte(), (0xf8 or s.depth).toByte(), 0, 0,
            (0x0c or (lengthSize - 1)).toByte(), parameters.size.toByte(),
        ))
        for (parameter in parameters) {
            out.write(0x80 or ((parameter[0].toInt() ushr 1) and 63))
            out.write(0); out.write(1)
            out.write(parameter.size ushr 8); out.write(parameter.size)
            out.write(parameter)
        }
        return out.toByteArray()
    }
    private fun slice(first: Boolean = true, address: Int = 0, addressBits: Int = 0,
                      pps: Int = 0, type: Int = 20, sliceType: Int = 2, dataSize: Int = 1,
                      noPriorOutput: Boolean = false): ByteArray = nal(type, write {
        flag(first); flag(noPriorOutput); ue(pps)
        if (!first) n(address, addressBits)
        ue(sliceType); se(0); align(); raw(ByteArray(dataSize) { 0x55 })
    })
    private fun payload(vararg nals: ByteArray, lengthSize: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            for (i in lengthSize - 1 downTo 0) out.write(nal.size ushr (8 * i))
            out.write(nal)
        }
        return out.toByteArray()
    }
    private fun valid(s: Shape = Shape(), bytes: ByteArray = payload(slice()), hvcc: ByteArray = config(s),
                      width: Int = s.width, height: Int = s.height): Boolean =
        HevcTileConfiguration.validate(hvcc, bytes.inputStream(), bytes.size.toLong(), width, height) {}

    @Test fun admitsOnlyBoundedMainAndStillPictureShapes() {
        assertTrue(valid())
        assertTrue(valid(Shape(profileId = 3)))
        assertTrue(valid(Shape(buffering = 5)))
        assertFalse(valid(Shape(profileId = 2)))
        assertFalse(valid(Shape(profileId = 4)))
        assertFalse(valid(Shape(depth = 2)))
        assertFalse(valid(Shape(chroma = 2)))
    }
    @Test fun allNalLengthFieldWidthsExceptReservedWidthAreSupported() {
        for (size in listOf(1, 2, 4)) assertTrue(valid(bytes = payload(slice(), lengthSize = size), hvcc = config(lengthSize = size)))
        assertFalse(valid(bytes = payload(slice(), lengthSize = 3), hvcc = config(lengthSize = 3)))
    }
    @Test fun dimensionsComeFromTheCodedSpsAndMustMatchExactly() {
        assertFalse(valid(width = 32))
        assertFalse(valid(height = 32))
        assertFalse(valid(Shape(width = 4096)))
        assertFalse(valid(Shape(width = 2048, height = 2048)))
        assertTrue(valid(Shape(width = 2048, height = 512)))
        assertFalse(valid(width = 0))
        assertFalse(valid(height = Int.MAX_VALUE))
        assertFalse(valid(Shape(crop = 1)))
        assertFalse(valid(Shape(width = 63)))
    }
    @Test fun boundsDpbAndChecksVpsSpsConsistency() {
        assertTrue(valid(Shape(vpsId = 3, spsId = 2, ppsId = 5), bytes = payload(slice(pps = 5))))
        assertFalse(valid(Shape(buffering = 6)))
        assertFalse(valid(Shape(reorder = 5)))
        assertFalse(valid(Shape(spsBuffering = 5)))
        assertFalse(valid(Shape(spsVpsId = 1)))
        assertFalse(valid(Shape(ppsSpsId = 1)))
        assertFalse(valid(Shape(layers = 1)))
        assertFalse(valid(Shape(sublayers = 1)))
    }
    @Test fun unsupportedSyntaxDoesNotGainATileBudget() {
        assertFalse(valid(Shape(dependent = true)))
        assertFalse(valid(Shape(extension = true)))
        assertFalse(valid(Shape(scaling = true)))
        assertFalse(valid(Shape(internalTiles = true)))
        assertFalse(valid(Shape(wpp = true)))
    }
    @Test fun onlyOneExplicitEmptyShortTermRpsIsAdmitted() {
        assertTrue(valid(Shape(shortRps = 0)))
        assertTrue(valid(Shape(shortRps = 1)))
        assertFalse(valid(Shape(shortRps = 2)))
        assertFalse(valid(Shape(shortRps = 1, negativeRps = 1)))
        assertFalse(valid(Shape(shortRps = 1, positiveRps = 1)))
        assertFalse(valid(Shape(shortRps = 1, longTermRps = true)))
        val still = Shape(profileId = 3, buffering = 0, reorder = 0, shortRps = 1)
        assertTrue(valid(still, hvcc = config(still).also { it[21] = 3 }))
        assertFalse(valid(still.copy(scaling = true)))
        assertFalse(valid(still.copy(wpp = true)))
    }
    @Test fun requiresExactlyOneVpsSpsPpsAndCompleteConfiguration() {
        val ps = sets()
        assertFalse(valid(hvcc = config(parameters = ps.dropLast(1))))
        assertFalse(valid(hvcc = config(parameters = ps + ps.first())))
        assertFalse(valid(hvcc = config() + byteArrayOf(0)))
        assertFalse(valid(hvcc = config().copyOf(23)))
        assertFalse(valid(hvcc = config().also { it[23] = 32 })) // incomplete VPS array
        assertFalse(valid(hvcc = config().also { it[21] = 0x17 })) // two temporal layers
        assertFalse(valid(hvcc = ByteArray(65_537)))
        val complete = config()
        for (length in complete.indices) assertFalse(valid(hvcc = complete.copyOf(length)))
    }
    @Test fun configurationProfileMustMatchTheActualParameterSets() {
        val mismatched = config().also { it[1] = 3 }
        assertFalse(valid(hvcc = mismatched))
        assertFalse(valid(hvcc = config().also { it[6] = 0xd0.toByte() })) // interlaced
        assertFalse(valid(hvcc = config().also { it[12] = 255.toByte() })) // unbounded still-picture level
    }
    @Test fun unspecifiedTemporalSummariesStillRequireActualSingleLayerProof() {
        fun summary(bytes: ByteArray, layers: Int = 0, nested: Boolean = false): ByteArray =
            bytes.also { it[21] = ((layers shl 3) or (if (nested) 4 else 0) or 3).toByte() }
        for (layers in 0..1) for (nested in listOf(false, true)) {
            assertTrue(valid(hvcc = summary(config(), layers, nested)))
        }
        for (layers in 2..7) assertFalse(valid(hvcc = summary(config(), layers)))
        for (s in listOf(Shape(layers = 1), Shape(sublayers = 1), Shape(vpsNested = false), Shape(spsNested = false))) {
            assertFalse(valid(s, hvcc = summary(config(s))))
        }
        for (index in 0..2) for (header in listOf(2, 9)) {
            val parameters = sets().mapIndexed { i, nal ->
                nal.also { if (i == index) it[1] = header.toByte() }
            }
            assertFalse(valid(hvcc = summary(config(parameters = parameters))))
        }
        for (header in listOf(2, 9)) {
            assertFalse(valid(hvcc = summary(config()), bytes = payload(slice().also { it[1] = header.toByte() })))
        }
        assertFalse(valid(hvcc = summary(config(Shape(buffering = 6)))))
        assertFalse(valid(hvcc = summary(config()), width = 32))
    }
    @Test fun oneIdrPictureMayContainOrderedIndependentISlices() {
        val s = Shape(width = 128, height = 128)
        assertTrue(valid(s, payload(slice(), slice(false, 2, 2))))
        assertFalse(valid(s, payload(slice(), slice(false, 2, 2), slice(false, 1, 2))))
        assertFalse(valid(s, payload(slice(), slice(false, 2, 2), slice(false, 2, 2))))
        assertFalse(valid(s, payload(slice(), slice())))
        assertFalse(valid(s, payload(slice(), slice(false, 2, 2, noPriorOutput = true))))
        assertFalse(valid(s, payload(slice(false, 1, 2))))
        assertFalse(valid(bytes = payload(slice(), slice(false)))) // one-CTB picture has no second address
        val threeCtbs = Shape(width = 192, height = 64)
        assertFalse(valid(threeCtbs, payload(slice(), slice(false, 3, 2))))
    }
    @Test fun rejectsOtherPicturesChangedParameterSetsAndLayerIds() {
        for (type in listOf(0, 1, 16, 18, 21, 35, 48)) assertFalse(valid(bytes = payload(slice(type = type))))
        assertFalse(valid(bytes = payload(slice(type = 19), slice(false, type = 20))))
        assertFalse(valid(bytes = payload(slice(sliceType = 1))))
        assertFalse(valid(bytes = payload(slice(pps = 1))))
        assertFalse(valid(bytes = payload(sets()[1], slice())))
        for (header in listOf(0, 2, 9)) assertFalse(valid(bytes = payload(slice().also { it[1] = header.toByte() })))
        assertFalse(valid(bytes = payload(slice().also { it[0] = (it[0].toInt() or 0x80).toByte() })))
    }
    @Test fun boundedUserDataSeiIsAllowedButNotHdrOrRecoverySei() {
        val user = nal(39, byteArrayOf(5, 16) + ByteArray(16) { 0x55 } + byteArrayOf(0x80.toByte()))
        val suffix = user.copyOf().also { it[0] = 80 }
        assertTrue(valid(hvcc = config(parameters = sets() + user)))
        assertTrue(valid(bytes = payload(user, slice(), suffix)))
        assertFalse(valid(bytes = payload(suffix, slice())))
        assertFalse(valid(bytes = payload(slice(), user)))
        assertFalse(valid(bytes = payload(slice(), suffix, slice(false))))
        for (type in listOf(0, 1, 6, 137, 144)) {
            val unsupported = nal(39, byteArrayOf(type.toByte(), 0, 0x80.toByte()))
            assertFalse(valid(hvcc = config(parameters = sets() + unsupported)))
            assertFalse(valid(bytes = payload(unsupported, slice())))
        }
        val shortUuid = nal(39, byteArrayOf(5, 15) + ByteArray(15) { 0x55 } + byteArrayOf(0x80.toByte()))
        assertFalse(valid(bytes = payload(shortUuid, slice())))
    }
    @Test fun rejectsTruncationOversizedLengthsAndEmptyCodedPictures() {
        val original = payload(slice())
        for (length in original.indices) assertFalse(valid(bytes = original.copyOf(length)))
        assertFalse(valid(bytes = byteArrayOf(-1, -1, -1, -1)))
        assertFalse(valid(bytes = byteArrayOf(0, 0, 0, 0)))
        assertFalse(valid(bytes = payload(slice(dataSize = 0))))
        assertFalse(HevcTileConfiguration.validate(config(), original.inputStream(), original.size.toLong() + 1, 64, 64) {})
        assertFalse(HevcTileConfiguration.validate(config(), original.inputStream(), 16L * 1024 * 1024 + 1, 64, 64) {})
    }
    @Test fun enforcesNalAndSliceCounts() {
        val user = nal(39, byteArrayOf(5, 16) + ByteArray(16) { 0x55 } + byteArrayOf(0x80.toByte()))
        assertFalse(valid(bytes = payload(*(List(64) { user } + slice()).toTypedArray())))
        val s = Shape(width = 2048, height = 512)
        val slices = (0..32).map { slice(first = it == 0, address = it, addressBits = 8) }
        assertFalse(valid(s, payload(*slices.toTypedArray())))
    }
    @Test fun validatesEbspEscapesEvenAfterTheParsedSliceHeader() {
        for (tail in listOf(byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 2), byteArrayOf(0, 0, 3), byteArrayOf(0, 0, 3, 4))) {
            assertFalse(valid(bytes = payload(slice() + tail)))
        }
        assertTrue(valid(bytes = payload(slice() + byteArrayOf(0, 0, 3, 1))))
        assertFalse(valid(hvcc = config(parameters = sets().mapIndexed { i, n -> if (i == 1) n + byteArrayOf(0) else n })))
    }
    @Test fun overlongExpGolombNeverOverflowsOrEscapesTheNal() {
        val sps = nal(33, write { n(1, 8); raw(ptl()); raw(ByteArray(5)); align() })
        assertFalse(valid(hvcc = config(parameters = listOf(sets()[0], sps, sets()[2]))))
    }
    @Test fun neverReadsBeyondDeclaredPayloadAndNeverClosesTheCallerStream() {
        val bytes = payload(slice())
        var closed = false
        val stream = object : ByteArrayInputStream(bytes + byteArrayOf(77, 88)) {
            override fun close() { closed = true; super.close() }
        }
        assertTrue(HevcTileConfiguration.validate(config(), stream, bytes.size.toLong(), 64, 64) {})
        assertFalse(closed)
        assertEquals(77, stream.read())
        assertEquals(88, stream.read())
    }
    @Test fun shortAndZeroLengthReadsAreHandledWithoutSpinning() {
        val bytes = payload(slice())
        val stream = object : ByteArrayInputStream(bytes) {
            var zero = true
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (zero) { zero = false; return 0 }
                return super.read(b, off, minOf(1, len))
            }
        }
        assertTrue(HevcTileConfiguration.validate(config(), stream, bytes.size.toLong(), 64, 64) {})
    }
    @Test fun cancellationPropagatesDuringLargePayloadScanning() {
        val bytes = payload(slice(dataSize = 20_000))
        val stream = ByteArrayInputStream(bytes)
        try {
            HevcTileConfiguration.validate(config(), stream, bytes.size.toLong(), 64, 64) {
                if (stream.available() < bytes.size - 8192) throw CancellationException("cancel")
            }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun ioFailuresAreNotRelabeledAsUnsupportedSyntax() {
        val input = object : InputStream() { override fun read(): Int = throw IOException("source unavailable") }
        try {
            HevcTileConfiguration.validate(config(), input, 20, 64, 64) {}
            fail("I/O exception swallowed")
        } catch (_: IOException) { }
    }

    @Test fun acceptsExistingOriginalEightBitFixturesWithoutDecodingThem() {
        for ((name, width, height) in listOf(Triple("corners-sdr8.heic", 96, 64), Triple("corners-grid2x2.heic", 48, 32))) {
            val bytes = fixture(name)
            val config = leaf(bytes, "hvcC")
            val media = leaf(bytes, "mdat")
            var at = if (name.contains("grid")) 8 else 0
            var count = 0
            // These original fixtures have exactly one length-prefixed IDR NAL per item.
            while (at < media.size) {
                val size = number(media, at)
                val sample = media.copyOfRange(at, at + 4 + size)
                assertTrue(name, valid(bytes = sample, hvcc = config, width = width, height = height))
                at += 4 + size; count++
            }
            assertEquals(if (name.contains("grid")) 4 else 1, count)
        }
    }
    @Test fun existingTenBitFixtureIsNotAdmittedByTheSmallerBudget() {
        val bytes = fixture("corners-main10.heic")
        assertFalse(valid(bytes = leaf(bytes, "mdat"), hvcc = leaf(bytes, "hvcC"), width = 96, height = 64))
    }
    private fun fixture(name: String): ByteArray {
        val file = listOf(File("src/androidTest/assets/heic", name), File("app/src/androidTest/assets/heic", name)).firstOrNull { it.isFile }
        requireNotNull(file) { "Missing original fixture $name" }
        return file.readBytes()
    }
    private fun number(bytes: ByteArray, at: Int): Int = (0..3).fold(0) { value, i -> (value shl 8) or (bytes[at + i].toInt() and 255) }
    private fun leaf(bytes: ByteArray, type: String): ByteArray {
        val tag = type.toByteArray(Charsets.US_ASCII)
        val at = (4 until bytes.size - 4).first { offset -> tag.indices.all { bytes[offset + it] == tag[it] } }
        val length = number(bytes, at - 4)
        return bytes.copyOfRange(at + 4, at - 4 + length)
    }
}
