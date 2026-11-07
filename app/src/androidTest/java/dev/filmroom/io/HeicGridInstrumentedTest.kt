package dev.filmroom.io

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.filmroom.engine.NativeWorkReservations
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android codec comparison, separate from host marker-pixel tests. Original CC0 fixtures
 * only. This is a test to RUN on a device, not evidence of a device/PSS/export validation run. */
@RunWith(AndroidJUnit4::class)
class HeicGridInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun realGridAndAttachedThumbnailMatchTheWholeImageDecode() = runBlocking {
        for (name in listOf("corners-grid2x2.heic", "corners-grid-thumbnail.heic")) verify(fixture(name))
    }

    @Test fun realTileOnlyDisplayP3ProfileMatchesWholeImageSrgbConversion() = runBlocking {
        verify(fixture("corners-grid-icc-p3-tiles.heic"))
    }

    @Test fun croppedOriginalTilesMatchWholeImageAtAllFourRotations() = runBlocking {
        // Each real 48x32 tile remains intact. Only the derived grid crops its right/bottom.
        for (rotation in listOf(0, 90, 180, 270)) verify(croppedGrid(rotation))
    }

    private suspend fun verify(bytes: ByteArray) {
        assumeTrue("SKIPPED: device does not report an HEIF decoder", AndroidHeicBackend.supported())
        val directory = File(context.cacheDir, "heic-grid-device-${System.nanoTime()}")
        assertTrue(directory.mkdir())
        val source = File(directory, "source.heic").apply { writeBytes(bytes) }
        val whole = File(directory, "whole.rgb"); val tiled = File(directory, "tiled.rgb")
        try {
            val info = HeicContainer.inspect(source)
            val plan = requireNotNull(HeicGridInput.planOrNull(source, info) {})
            assertEquals(4, plan.tileCount)
            val memory = { NativeWorkReservations.snapshot(context) }
            HeicImport(memory, AndroidHeicBackend, gridEnabled = false)
                .write(source, info, whole, currentCoroutineContext())
            var calls = 0; var live = 0; var closed = 0
            val views = ArrayList<File>()
            val countedAndroid = object : HeicBackend {
                override val supportsIndependentTiles = true
                override fun supported() = AndroidHeicBackend.supported()
                override fun decode(file: File, info: HeicContainer.Info, job: CoroutineContext): HeicPixels {
                    assertEquals(0, live); assertEquals(calls, closed)
                    assertTrue(views.all { !it.exists() })
                    assertNotEquals(source.canonicalPath, file.canonicalPath)
                    assertEquals(48, info.displayWidth); assertEquals(32, info.displayHeight)
                    val actual = AndroidHeicBackend.decode(file, info, job)
                    views += file; calls++; live++
                    return object : HeicPixels {
                        override val width = actual.width; override val height = actual.height
                        override fun readRow(y: Int, argb: IntArray) = actual.readRow(y, argb)
                        override fun close() { try { actual.close() } finally { live--; closed++ } }
                    }
                }
            }
            HeicImport(memory, countedAndroid, gridEnabled = true)
                .write(source, info, tiled, currentCoroutineContext())
            assertEquals(4, calls); assertEquals(4, closed); assertEquals(0, live)
            assertTrue(views.all { !it.exists() })
            val expectedBytes = info.displayWidth.toLong() * info.displayHeight * 12
            assertEquals(expectedBytes, whole.length()); assertEquals(expectedBytes, tiled.length())
            val expected = ByteBuffer.wrap(whole.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val actual = ByteBuffer.wrap(tiled.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            var channel = 0
            while (expected.hasRemaining()) {
                val a = expected.float; val b = actual.float
                assertTrue("Finite normalized linear value at channel $channel", a.isFinite() && b.isFinite() && a in 0f..1f && b in 0f..1f)
                assertTrue("Whole/tile color at channel $channel: $a versus $b",
                    kotlin.math.abs(LinearRgb.toSrgb8(a) - LinearRgb.toSrgb8(b)) <= 1)
                channel++
            }
            assertFalse(actual.hasRemaining())
            assertArrayEquals("Never rewrite the original HEIC", bytes, source.readBytes())
            assertEquals(setOf("source.heic", "whole.rgb", "tiled.rgb"), directory.list()!!.toSet())
        } finally { assertTrue("Clean private test files", directory.deleteRecursively()) }
    }

    private fun fixture(name: String) = instrumentation.context.assets.open("heic/$name").use { it.readBytes() }
    private fun data(block: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    private fun box(type: String, body: ByteArray) = data { writeInt(body.size + 8); writeBytes(type); write(body) }
    private fun full(type: String, body: ByteArray, version: Int = 0, flags: Int = 0) = box(type, data { writeInt((version shl 24) or flags); write(body) })
    private fun number(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(at)

    private fun croppedGrid(rotation: Int): ByteArray {
        val original = fixture("corners-grid2x2.heic")
        fun leaf(type: String): ByteArray {
            val tag = type.toByteArray(Charsets.US_ASCII)
            val at = (4 until original.size - 4).first { offset -> tag.indices.all { original[offset + it] == tag[it] } }
            return original.copyOfRange(at + 4, at - 4 + number(original, at - 4))
        }
        val media = leaf("mdat"); val tiles = ArrayList<ByteArray>(); var at = 8
        // Checked-in original fixtures have exactly one length-prefixed IDR NAL per tile.
        repeat(4) { val size = number(media, at) + 4; tiles += media.copyOfRange(at, at + size); at += size }
        require(at == media.size)
        val props = mutableListOf(
            full("ispe", data { writeInt(78); writeInt(46) }),
            full("ispe", data { writeInt(48); writeInt(32) }),
            box("hvcC", leaf("hvcC")), box("colr", leaf("colr")), full("pixi", byteArrayOf(3, 8, 8, 8)),
        )
        if (rotation != 0) props += box("irot", byteArrayOf(((360 - rotation) / 90).toByte()))
        val payloads = listOf(byteArrayOf(0, 0, 1, 1, 0, 78, 0, 46)) + tiles
        val ftyp = box("ftyp", data { writeBytes("heic"); writeInt(0); writeBytes("mif1heic") })
        fun meta(start: Int) = full("meta", data {
            write(full("hdlr", data { writeInt(0); writeBytes("pict"); repeat(3) { writeInt(0) }; writeByte(0) }))
            write(full("pitm", data { writeShort(1) }))
            write(full("iinf", data {
                writeShort(5)
                for (id in 1..5) write(full("infe", data { writeShort(id); writeShort(0); writeBytes(if (id == 1) "grid" else "hvc1"); writeByte(0) }, 2, if (id == 1) 0 else 1))
            }))
            write(full("iref", box("dimg", data { writeShort(1); writeShort(4); for (id in 2..5) writeShort(id) })))
            write(box("iprp", box("ipco", data { props.forEach { write(it) } }) + full("ipma", data {
                writeInt(5)
                for (id in 1..5) {
                    val links = if (id == 1) listOf(0x81, 0x84, 0x85) + if (rotation == 0) emptyList() else listOf(0x86) else listOf(0x82, 0x83, 0x84, 0x85)
                    writeShort(id); writeByte(links.size); links.forEach { writeByte(it) }
                }
            })))
            write(full("iloc", data {
                writeByte(0x44); writeByte(0); writeShort(5); var offset = start
                payloads.forEachIndexed { index, payload ->
                    writeShort(index + 1); writeShort(0); writeShort(1); writeInt(offset); writeInt(payload.size); offset += payload.size
                }
            }))
        })
        return ftyp + meta(ftyp.size + meta(0).size + 8) + box("mdat", data { payloads.forEach { write(it) } })
    }
}
