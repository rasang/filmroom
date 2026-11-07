package dev.filmroom.io

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Container/remux admission only. No host test executes Android's HEVC pixel decoder. */
class HeicGridInputTest {
    @Test fun originalGridP3AndThumbnailFixturesProduceFourIndependentOriginalTiles() {
        for (name in listOf("corners-grid2x2.heic", "corners-grid-icc-p3-tiles.heic", "corners-grid-thumbnail.heic")) {
            val original = GridTestFixtures.fixture(name)
            GridTestFixtures.withSource(original) { root, source ->
                val info = HeicContainer.inspect(source)
                val plan = requireNotNull(HeicGridInput.planOrNull(source, info) {})
                assertEquals(info, plan.info)
                assertEquals(2, plan.columns); assertEquals(2, plan.rows); assertEquals(4, plan.tileCount)
                assertEquals(96, info.displayWidth); assertEquals(64, info.displayHeight)
                assertEquals(if (name.contains("p3")) HeicIccProfile.ColorIdentity.DISPLAY_P3 else HeicIccProfile.ColorIdentity.SRGB, info.sourceColor)
                assertEquals("Planning must not create a view", listOf("source.heic"), root.list()!!.toList())
                val sourcePayloads = GridTestFixtures.payloads(original)
                val sourceProperties = GridTestFixtures.properties(original)
                for (index in 0 until plan.tileCount) {
                    lateinit var view: File
                    plan.materialize(index) {}.use { tile ->
                        view = tile.file
                        assertNotEquals(source.canonicalPath, view.canonicalPath)
                        assertEquals(48, tile.info.encodedWidth); assertEquals(32, tile.info.encodedHeight)
                        assertEquals(0, tile.info.rotationDegreesClockwise)
                        assertEquals(48L * 32, tile.info.estimatedDecodePixels)
                        assertEquals(info.sourceProfile, tile.info.sourceProfile)
                        assertEquals((index % 2) * 48, tile.x); assertEquals((index / 2) * 32, tile.y)
                        assertEquals(48, tile.validWidth); assertEquals(32, tile.validHeight)
                        val bytes = view.readBytes()
                        val payloads = GridTestFixtures.payloads(bytes)
                        assertEquals(setOf(1), payloads.keys)
                        assertArrayEquals("Complete original coded tile $index, $name", sourcePayloads.getValue(index + 2), payloads.getValue(1))
                        for (type in listOf("hvcC", "colr", "pixi")) {
                            assertArrayEquals("Preserve exact $type, $name", sourceProperties.first { it.type == type }.raw(original),
                                GridTestFixtures.properties(bytes).single { it.type == type }.raw(bytes))
                        }
                        assertEquals(tile.info, HeicContainer.inspect(view))
                        assertTrue(view.length() <= plan.maximumViewBytes)
                    }
                    assertFalse("Closing releases the remux view", view.exists())
                }
                assertArrayEquals(original, source.readBytes())
                assertEquals(listOf("source.heic"), root.list()!!.toList())
            }
        }
    }

    @Test fun croppedGridKeepsOriginalDimensionsAndTileExtentsForEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) GridTestFixtures.withSource(GridTestFixtures.grid(width = 78, height = 46, rotation = rotation)) { _, source ->
            val info = HeicContainer.inspect(source)
            val plan = requireNotNull(HeicGridInput.planOrNull(source, info) {})
            assertEquals(rotation, info.rotationDegreesClockwise)
            assertEquals(if (rotation % 180 == 0) 78 else 46, info.displayWidth)
            assertEquals(if (rotation % 180 == 0) 46 else 78, info.displayHeight)
            repeat(4) { index -> plan.materialize(index) {}.use { tile ->
                assertEquals(if (index % 2 == 0) 48 else 30, tile.validWidth)
                assertEquals(if (index / 2 == 0) 32 else 14, tile.validHeight)
                assertEquals(48, tile.info.displayWidth); assertEquals(32, tile.info.displayHeight)
                assertEquals(0, tile.info.rotationDegreesClockwise)
            } }
        }
    }

    @Test fun oddPrimaryDimensionsAreOnlyAdmittedWithoutRotation() {
        for ((width, height) in listOf(95 to 64, 96 to 63, 95 to 63)) for (rotation in listOf(0, 90, 180, 270)) {
            GridTestFixtures.withSource(GridTestFixtures.grid(width = width, height = height, rotation = rotation)) { _, source ->
                val info = HeicContainer.inspect(source)
                if (rotation == 0) assertNotNull("$width x $height at $rotation", HeicGridInput.planOrNull(source, info) {})
                else assertGridRejected(source, info)
            }
        }
    }

    @Test fun dimgOrderAndFragmentedMdatOrIdatPreserveRealTilePayloads() {
        val order = listOf(5, 2, 4, 3)
        val original = GridTestFixtures.payloads(GridTestFixtures.fixture("corners-grid2x2.heic"))
        for (idat in listOf(false, true)) GridTestFixtures.withSource(GridTestFixtures.grid(order = order, idat = idat, fragmented = true)) { _, source ->
            val plan = requireNotNull(HeicGridInput.planOrNull(source, HeicContainer.inspect(source)) {})
            repeat(4) { index -> plan.materialize(index) {}.use { tile ->
                assertArrayEquals(original.getValue(order[index]), GridTestFixtures.payloads(tile.file.readBytes()).getValue(1))
                assertEquals(index % 2 * 48, tile.x); assertEquals(index / 2 * 32, tile.y)
            } }
        }
    }

    @Test fun primaryOnlyColorIsCopiedToEveryIndependentTileWithoutRelabeling() {
        for (fixture in listOf("corners-grid2x2.heic", "corners-grid-icc-p3-tiles.heic")) {
            val original = GridTestFixtures.fixture(fixture)
            val colr = GridTestFixtures.properties(original).first { it.type == "colr" }.raw(original)
            GridTestFixtures.withSource(GridTestFixtures.grid(fixture = fixture, tileColor = false)) { _, source ->
                val info = HeicContainer.inspect(source)
                val plan = requireNotNull(HeicGridInput.planOrNull(source, info) {})
                repeat(4) { index -> plan.materialize(index) {}.use { tile ->
                    val bytes = tile.file.readBytes()
                    assertArrayEquals(colr, GridTestFixtures.properties(bytes).single { it.type == "colr" }.raw(bytes))
                    assertEquals(info.sourceProfile, tile.info.sourceProfile)
                } }
            }
        }
    }

    @Test fun unknownNonessentialGridPropertyRejectsInsteadOfFallingBackToIspeOnlyBudget() {
        GridTestFixtures.withSource(GridTestFixtures.grid(unknownProperty = true)) { root, source ->
            val info = HeicContainer.inspect(source) // Ordinary metadata inspection alone is insufficient.
            assertGridRejected(source, info)
            assertEquals(1, root.list()!!.size)
        }
    }

    @Test fun declaredTileShapeMustAgreeWithTheActualCodedSps() {
        // Real 48x32 SPS/payload, falsely advertised as 32x32 in every tile's ispe.
        GridTestFixtures.withSource(GridTestFixtures.grid(width = 60, tileWidth = 32)) { _, source ->
            val info = HeicContainer.inspect(source)
            assertEquals(4L * 32 * 32, info.estimatedDecodePixels)
            assertGridRejected(source, info)
        }
        GridTestFixtures.withSource(GridTestFixtures.grid(width = 3000, height = 1500, tileWidth = 2048, tileHeight = 1024)) { _, source ->
            assertGridRejected(source)
        }
    }

    @Test fun corruptPayloadLengthsAndConfigurationNeverBecomeAPlan() {
        val good = GridTestFixtures.grid()
        val ranges = GridTestFixtures.payloadRanges(good)
        val badLength = good.copyOf().also { GridTestFixtures.putInt(it, ranges.getValue(2).first, Int.MAX_VALUE) }
        val badConfiguration = good.copyOf().also {
            val config = GridTestFixtures.properties(it).single { box -> box.type == "hvcC" }
            it[config.body + 22] = 0 // Missing VPS/SPS/PPS, ordinary container metadata still looks Main8.
        }
        for (bytes in listOf(badLength, badConfiguration, good.copyOf(good.size - 1))) {
            GridTestFixtures.withSource(bytes) { root, source ->
                val info = try { HeicContainer.inspect(source) } catch (_: HeicContainer.Rejected) { null }
                if (info != null) assertGridRejected(source, info)
                assertEquals(1, root.list()!!.size)
            }
        }
    }

    @Test fun onlyNonGridReturnsNullAndMismatchedInspectedInfoThrowsIoFailure() {
        GridTestFixtures.withSource(GridTestFixtures.fixture("corners-sdr8.heic")) { _, source ->
            assertNull(HeicGridInput.planOrNull(source, HeicContainer.inspect(source)) {})
        }
        GridTestFixtures.withSource { _, source ->
            val info = HeicContainer.inspect(source)
            try { HeicGridInput.planOrNull(source, info.copy(encodedWidth = info.encodedWidth - 1)) {}; fail("Stale or substituted info admitted") }
            catch (_: IOException) { }
        }
    }

    @Test fun invalidGridGeometryRejectsInsteadOfFallingBackToTheWholeImageDecoder() {
        val bytes = GridTestFixtures.grid()
        val grid = GridTestFixtures.payloadRanges(bytes).getValue(1)
        bytes[grid.first + 5] = 95 // Grid declares 95-wide while primary ispe remains 96-wide.
        GridTestFixtures.withSource(bytes) { root, source ->
            assertGridRejected(source, failure = HeicContainer.Failure.INVALID)
            assertEquals(1, root.list()!!.size)
        }
    }

    private fun assertGridRejected(source: File, info: HeicContainer.Info = HeicContainer.inspect(source),
                                   failure: HeicContainer.Failure = HeicContainer.Failure.UNSUPPORTED) {
        try { HeicGridInput.planOrNull(source, info) {}; fail("Identified grid must not return null or a plan") }
        catch (caught: HeicGridInput.Rejected) { assertEquals(failure, caught.failure) }
    }

    @Test fun cancellationAndIoFailuresPropagateInsteadOfLookingLikeUnsupportedLayouts() {
        GridTestFixtures.withSource { root, source ->
            val info = HeicContainer.inspect(source)
            val cancelled = CancellationException("test cancellation")
            try { HeicGridInput.planOrNull(source, info) { throw cancelled }; fail() }
            catch (caught: CancellationException) { assertSame(cancelled, caught) }
            val failure = IOException("test I/O failure")
            try { HeicGridInput.planOrNull(source, info) { throw failure }; fail() }
            catch (caught: IOException) { assertSame(failure, caught) }
            assertEquals(1, root.list()!!.size)
        }
    }

    @Test fun cancellationWhileMaterializingDeletesTheOwnedView() {
        GridTestFixtures.withSource { root, source ->
            val original = source.readBytes()
            val plan = requireNotNull(HeicGridInput.planOrNull(source, HeicContainer.inspect(source)) {})
            try { plan.materialize(0) { if (root.list()!!.size > 1) throw CancellationException("during copy") }; fail() }
            catch (_: CancellationException) { }
            assertEquals(listOf("source.heic"), root.list()!!.toList())
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun changedSourceLengthAndTimestampAreRejectedBeforeCreatingAView() {
        for (lengthChange in listOf(false, true)) GridTestFixtures.withSource { root, source ->
            val plan = requireNotNull(HeicGridInput.planOrNull(source, HeicContainer.inspect(source)) {})
            if (lengthChange) source.appendBytes(byteArrayOf(0))
            else assertTrue(source.setLastModified(source.lastModified() + 60_000))
            try { plan.materialize(0) {}; fail("Changed source admitted") } catch (_: IOException) { }
            assertEquals(listOf("source.heic"), root.list()!!.toList())
        }
    }

    @Test fun payloadMutationWithSameLengthAndTimestampIsRejectedByDigestAndCleanedUp() {
        GridTestFixtures.withSource { root, source ->
            val plan = requireNotNull(HeicGridInput.planOrNull(source, HeicContainer.inspect(source)) {})
            val modified = source.lastModified()
            val bytes = source.readBytes()
            val at = GridTestFixtures.payloadRanges(bytes).getValue(2).last
            bytes[at] = (bytes[at].toInt() xor 1).toByte()
            source.writeBytes(bytes); assertTrue(source.setLastModified(modified))
            try { plan.materialize(0) {}; fail("Changed coded bytes admitted") } catch (_: IOException) { }
            assertEquals(listOf("source.heic"), root.list()!!.toList())
        }
    }
}

/** Small test-only BMFF builder. Every hvcC and coded payload comes from original real fixtures;
 * it does not synthesize CABAC or assert that host metadata tests decoded those pictures. */
internal object GridTestFixtures {
    data class Box(val type: String, val start: Int, val body: Int, val end: Int) {
        fun raw(bytes: ByteArray) = bytes.copyOfRange(start, end)
    }
    fun fixture(name: String): ByteArray = requireNotNull(listOf(
        File("src/androidTest/assets/heic", name), File("app/src/androidTest/assets/heic", name),
    ).firstOrNull { it.isFile }) { "Missing original fixture: $name" }.readBytes()
    fun number(bytes: ByteArray, at: Int, size: Int = 4): Int = (0 until size).fold(0) { value, i -> (value shl 8) or (bytes[at + i].toInt() and 255) }
    fun putInt(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(at, value) }
    fun boxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<Box> {
        val out = ArrayList<Box>(); var at = start
        while (at < end) {
            val size = number(bytes, at)
            require(size >= 8 && size <= end - at)
            out += Box(String(bytes, at + 4, 4, Charsets.US_ASCII), at, at + 8, at + size)
            at += size
        }
        require(at == end); return out
    }
    private fun metaParts(bytes: ByteArray): List<Box> = boxes(bytes).single { it.type == "meta" }.let { boxes(bytes, it.body + 4, it.end) }
    fun properties(bytes: ByteArray): List<Box> = metaParts(bytes).single { it.type == "iprp" }.let { boxes(bytes, it.body, it.end) }
        .single { it.type == "ipco" }.let { boxes(bytes, it.body, it.end) }
    // Original fixture and independent materialized views use one method-0 extent per item.
    fun payloadRanges(bytes: ByteArray): Map<Int, IntRange> {
        val iloc = metaParts(bytes).single { it.type == "iloc" }
        require(number(bytes, iloc.body) == 0 && number(bytes, iloc.body + 4, 2) == 0x4400)
        val count = number(bytes, iloc.body + 6, 2); var at = iloc.body + 8
        return buildMap { repeat(count) {
            val id = number(bytes, at, 2)
            require(number(bytes, at + 2, 2) == 0 && number(bytes, at + 4, 2) == 1)
            val offset = number(bytes, at + 6); val size = number(bytes, at + 10)
            require(offset >= 0 && size > 0 && offset <= bytes.size - size)
            put(id, offset until offset + size); at += 14
        }; require(at == iloc.end) }
    }
    fun payloads(bytes: ByteArray): Map<Int, ByteArray> = payloadRanges(bytes).mapValues { (_, range) -> bytes.copyOfRange(range.first, range.last + 1) }
    private fun data(block: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    private fun box(type: String, body: ByteArray) = data { writeInt(body.size + 8); writeBytes(type); write(body) }
    private fun full(type: String, body: ByteArray, version: Int = 0, flags: Int = 0) = box(type, data { writeInt((version shl 24) or flags); write(body) })
    private fun ispe(width: Int, height: Int) = full("ispe", data { writeInt(width); writeInt(height) })
    fun grid(width: Int = 96, height: Int = 64, rotation: Int = 0,
             tileWidth: Int = 48, tileHeight: Int = 32,
             fixture: String = "corners-grid2x2.heic", tileColor: Boolean = true,
             order: List<Int> = listOf(2, 3, 4, 5), idat: Boolean = false, fragmented: Boolean = false,
             unknownProperty: Boolean = false): ByteArray {
        require(rotation in listOf(0, 90, 180, 270))
        val original = fixture(fixture); val originals = properties(original); val payloads = payloads(original)
        val props = mutableListOf(ispe(width, height), ispe(tileWidth, tileHeight),
            originals.first { it.type == "hvcC" }.raw(original), originals.first { it.type == "colr" }.raw(original),
            originals.first { it.type == "pixi" }.raw(original))
        val primaryLinks = mutableListOf(0x81, 0x84, 0x85)
        if (rotation != 0) { props += box("irot", byteArrayOf(((360 - rotation) / 90).toByte())); primaryLinks += 0x80 or props.size }
        if (unknownProperty) { props += box("zzzz", byteArrayOf(1)); primaryLinks += props.size }
        val orderedPayloads = linkedMapOf(1 to data { writeByte(0); writeByte(0); writeByte(1); writeByte(1); writeShort(width); writeShort(height) })
        for (id in 2..5) orderedPayloads[id] = payloads.getValue(id)
        val ftyp = box("ftyp", data { writeBytes("heic"); writeInt(0); writeBytes("mif1"); writeBytes("heic") })
        fun metadata(start: Int) = full("meta", data {
            write(full("hdlr", data { writeInt(0); writeBytes("pict"); repeat(3) { writeInt(0) }; writeByte(0) }))
            write(full("pitm", data { writeShort(1) }))
            write(full("iinf", data {
                writeShort(5)
                for (id in 1..5) write(full("infe", data { writeShort(id); writeShort(0); writeBytes(if (id == 1) "grid" else "hvc1"); writeByte(0) }, 2, if (id == 1) 0 else 1))
            }))
            write(full("iref", box("dimg", data { writeShort(1); writeShort(order.size); order.forEach { writeShort(it) } })))
            write(box("iprp", box("ipco", data { props.forEach { write(it) } }) + full("ipma", data {
                writeInt(5)
                for (id in 1..5) {
                    val links = if (id == 1) primaryLinks else listOf(0x82, 0x83, 0x85) + if (tileColor) listOf(0x84) else emptyList()
                    writeShort(id); writeByte(links.size); links.forEach { writeByte(it) }
                }
            })))
            write(full("iloc", data {
                writeByte(0x44); writeByte(0); writeShort(5); var offset = if (idat) 0 else start
                for ((id, payload) in orderedPayloads) {
                    writeShort(id); if (idat) writeShort(1); writeShort(0); writeShort(if (fragmented) 2 else 1)
                    if (fragmented) {
                        val first = payload.size / 2
                        writeInt(offset); writeInt(first); writeInt(offset + first); writeInt(payload.size - first)
                    } else { writeInt(offset); writeInt(payload.size) }
                    offset += payload.size
                }
            }, if (idat) 1 else 0))
            if (idat) write(box("idat", data { orderedPayloads.values.forEach { write(it) } }))
        })
        val first = metadata(0)
        return ftyp + metadata(ftyp.size + first.size + 8) + box("mdat", if (idat) byteArrayOf() else data { orderedPayloads.values.forEach { write(it) } })
    }
    inline fun <T> withSource(bytes: ByteArray = fixture("corners-grid2x2.heic"), block: (File, File) -> T): T {
        val root = kotlin.io.path.createTempDirectory("heic-grid-test-").toFile()
        return try { block(root, File(root, "source.heic").apply { writeBytes(bytes) }) } finally { root.deleteRecursively() }
    }
}
