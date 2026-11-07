package dev.filmroom.io

import dev.filmroom.engine.NativeMemorySnapshot
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Marker pixels deliberately replace Android decoding. These tests prove orchestration,
 * float placement and cleanup, not codec correctness, platform memory or color conversion. */
class HeicGridSpoolTest {
    private val plentiful = { NativeMemorySnapshot(8L * 1024 * 1024 * 1024, 128L * 1024 * 1024, false) }

    private class Backend : HeicBackend {
        override val supportsIndependentTiles = true
        override fun supported() = true
        var calls = 0
        var closed = 0
        var live = 0
        var maximumLive = 0
        var decodeFailure = -1
        var rowFailure = -1
        var sizeMismatch = -1
        var allocationFailure = -1
        var cancelOnRow: Job? = null
        var cancelAfterClose: Job? = null
        var onClose: (() -> Unit)? = null
        val events = ArrayList<String>()
        val views = ArrayList<File>()
        val rows = ArrayList<Pair<Int, Int>>()
        override fun decode(file: File, info: HeicContainer.Info, job: CoroutineContext): HeicPixels {
            val index = calls++
            assertEquals("Only one decoder tile may be live", 0, live)
            assertEquals("Previous pixels must already be closed", index, closed)
            assertTrue("Previous remux view must already be released", views.all { !it.exists() })
            assertTrue(file.isFile); assertTrue(file.name.startsWith("heic-primary-"))
            assertEquals(48, info.displayWidth); assertEquals(32, info.displayHeight)
            assertEquals(0, info.rotationDegreesClockwise)
            assertEquals(info, HeicContainer.inspect(file))
            views += file; events += "decode:$index"
            if (index == decodeFailure) throw IOException("injected decode failure")
            if (index == allocationFailure) throw OutOfMemoryError("injected platform allocation failure")
            live++; maximumLive = maxOf(maximumLive, live)
            return object : HeicPixels {
                private var released = false
                override val width = info.displayWidth + if (index == sizeMismatch) 1 else 0
                override val height = info.displayHeight
                override fun readRow(y: Int, argb: IntArray) {
                    assertFalse(released)
                    rows += index to y
                    if (index == rowFailure && y == 1) throw IOException("injected row failure")
                    for (x in argb.indices) argb[x] = marker(index, x, y)
                    cancelOnRow?.cancel()
                }
                override fun close() {
                    check(!released); released = true; live--; closed++; events += "close:$index"
                    cancelAfterClose?.cancel(); onClose?.invoke()
                }
            }
        }
    }

    @Test fun allPixelsInCroppedRectanglesAreWrittenExactlyOnceAtEveryRotation() = runBlocking {
        for (rotation in listOf(0, 90, 180, 270)) {
            val original = GridTestFixtures.grid(width = 78, height = 46, rotation = rotation)
            GridTestFixtures.withSource(original) { root, source ->
                val target = File(root, "canonical.rgb")
                val info = HeicContainer.inspect(source); val backend = Backend()
                HeicImport(plentiful, backend, gridEnabled = true).write(source, info, target, coroutineContext)
                assertEquals(4, backend.calls); assertEquals(4, backend.closed)
                assertEquals(0, backend.live); assertEquals(1, backend.maximumLive)
                assertEquals(listOf("decode:0", "close:0", "decode:1", "close:1", "decode:2", "close:2", "decode:3", "close:3"), backend.events)
                for (tile in 0..3) assertEquals((0 until if (tile < 2) 32 else 14).toList(), backend.rows.filter { it.first == tile }.map { it.second })
                assertSpool(target, info)
                assertTrue(backend.views.all { !it.exists() })
                assertEquals(setOf("source.heic", "canonical.rgb"), root.list()!!.toSet())
                assertArrayEquals(original, source.readBytes())
            }
        }
    }

    @Test fun oddUnrotatedGridPreservesEveryOriginalPixelAndWhiteAlphaPolicy() = runBlocking {
        GridTestFixtures.withSource(GridTestFixtures.grid(width = 95, height = 63)) { root, source ->
            val target = File(root, "canonical.rgb"); val info = HeicContainer.inspect(source)
            val backend = Backend()
            HeicImport(plentiful, backend, gridEnabled = true).write(source, info, target, coroutineContext)
            assertSpool(target, info)
            assertEquals(95L * 63 * 12, target.length())
            assertEquals(4, backend.closed)
        }
    }

    @Test fun directSpoolReportsWholeCompletedTileByteCountsOnly() = runBlocking {
        GridTestFixtures.withSource(GridTestFixtures.grid(width = 78, height = 46, rotation = 90)) { root, source ->
            val info = HeicContainer.inspect(source)
            val plan = requireNotNull(HeicGridInput.planOrNull(source, info) {})
            val target = File(root, "canonical.rgb").apply { assertTrue(createNewFile()) }
            val counts = ArrayList<Long>()
            HeicGridSpool.write(plan, Backend(), target, coroutineContext) { counts += it }
            assertEquals(listOf(0L, 48L * 32 * 12, (48L * 32 + 30L * 32) * 12,
                (48L * 32 + 30L * 32 + 48L * 14) * 12), counts)
            assertSpool(target, info)
        }
    }

    @Test fun decodeRowDimensionAndAllocationFailuresDeletePartialAndAllowRetry() = runBlocking {
        for (failure in listOf("decode", "row", "size", "allocation")) GridTestFixtures.withSource { root, source ->
            val original = source.readBytes(); val info = HeicContainer.inspect(source)
            val target = File(root, "canonical.rgb")
            val backend = Backend().apply {
                when (failure) { "decode" -> decodeFailure = 1; "row" -> rowFailure = 1; "size" -> sizeMismatch = 1; else -> allocationFailure = 1 }
            }
            try { HeicImport(plentiful, backend, gridEnabled = true).write(source, info, target, coroutineContext); fail("$failure was published") }
            catch (caught: PhotoException) { assertEquals("allocation", failure); assertEquals(PhotoException.Reason.TOO_LARGE, caught.reason) }
            catch (_: IOException) { assertNotEquals("allocation", failure) }
            assertEquals(2, backend.calls)
            assertEquals(if (failure in listOf("row", "size")) 2 else 1, backend.closed)
            assertCleanFailure(root, target, backend)
            assertArrayEquals(original, source.readBytes())
            // No admission lease or temporary view is retained after a failure.
            val retry = Backend()
            HeicImport(plentiful, retry, gridEnabled = true).write(source, info, target, coroutineContext)
            assertEquals(4, retry.closed); assertSpool(target, info)
        }
    }

    @Test fun cancellationDuringReadOrAfterClosingTileNeverPublishesOrStartsNextTile() = runBlocking {
        for (duringRead in listOf(true, false)) GridTestFixtures.withSource { root, source ->
            val target = File(root, "canonical.rgb"); val job = Job()
            val backend = Backend().apply { if (duringRead) cancelOnRow = job else cancelAfterClose = job }
            try { HeicImport(plentiful, backend, gridEnabled = true).write(source, HeicContainer.inspect(source), target, job); fail() }
            catch (_: CancellationException) { }
            assertEquals(1, backend.calls); assertEquals(1, backend.closed)
            assertCleanFailure(root, target, backend)
        }
    }

    @Test fun alreadyCancelledImportNeverMaterializesOrDecodesATile() = runBlocking {
        GridTestFixtures.withSource { root, source ->
            val target = File(root, "canonical.rgb"); val backend = Backend()
            try { HeicImport(plentiful, backend, gridEnabled = true).write(source, HeicContainer.inspect(source), target, Job().apply { cancel() }); fail() }
            catch (_: CancellationException) { }
            assertEquals(0, backend.calls); assertCleanFailure(root, target, backend)
        }
    }

    @Test fun existingTargetIsNeverTruncatedAndNeverStartsTheBackend() = runBlocking {
        GridTestFixtures.withSource { root, source ->
            val target = File(root, "canonical.rgb").apply { writeText("preserve existing original") }
            val backend = Backend()
            try { HeicImport(plentiful, backend, gridEnabled = true).write(source, HeicContainer.inspect(source), target, coroutineContext); fail() }
            catch (_: IllegalArgumentException) { }
            assertEquals("preserve existing original", target.readText())
            assertEquals(0, backend.calls)
            val plan = requireNotNull(HeicGridInput.planOrNull(source, HeicContainer.inspect(source)) {})
            try { HeicGridSpool.write(plan, backend, target, coroutineContext); fail() }
            catch (_: IllegalStateException) { }
            assertEquals("preserve existing original", target.readText()); assertEquals(0, backend.calls)
        }
    }

    @Test fun lowMemoryBeforeAdmissionOrBetweenTilesSafelyRefusesWithoutDownsampling() = runBlocking {
        for (afterFirst in listOf(false, true)) GridTestFixtures.withSource { root, source ->
            val target = File(root, "canonical.rgb"); val backend = Backend()
            val memory = { if (!afterFirst || backend.closed > 0) NativeMemorySnapshot(128L * 1024 * 1024, 0, true) else plentiful() }
            try { HeicImport(memory, backend, gridEnabled = true).write(source, HeicContainer.inspect(source), target, coroutineContext); fail() }
            catch (caught: PhotoException) { assertEquals(PhotoException.Reason.TOO_LARGE, caught.reason) }
            assertEquals(if (afterFirst) 1 else 0, backend.calls)
            assertEquals(backend.calls, backend.closed); assertCleanFailure(root, target, backend)
        }
    }

    @Test fun changedSourceBetweenTilesDeletesPartialAndNeverDecodesChangedTile() = runBlocking {
        GridTestFixtures.withSource { root, source ->
            val target = File(root, "canonical.rgb")
            val backend = Backend().apply { onClose = { source.appendBytes(byteArrayOf(0)) } }
            try { HeicImport(plentiful, backend, gridEnabled = true).write(source, HeicContainer.inspect(source), target, coroutineContext); fail() }
            catch (_: IOException) { }
            assertEquals(1, backend.calls); assertEquals(1, backend.closed)
            assertCleanFailure(root, target, backend)
        }
    }

    @Test fun identifiedUnsupportedOrCorruptGridNeverFallsBackToAnyPixelBackend() = runBlocking {
        val good = GridTestFixtures.grid()
        val badLength = good.copyOf().also {
            GridTestFixtures.putInt(it, GridTestFixtures.payloadRanges(it).getValue(2).first, Int.MAX_VALUE)
        }
        val badConfiguration = good.copyOf().also {
            val config = GridTestFixtures.properties(it).single { box -> box.type == "hvcC" }
            it[config.body + 22] = 0
        }
        val badGridGeometry = good.copyOf().also {
            it[GridTestFixtures.payloadRanges(it).getValue(1).first + 5] = 95
        }
        for (bytes in listOf(
            GridTestFixtures.grid(width = 95, rotation = 90),
            GridTestFixtures.grid(unknownProperty = true),
            GridTestFixtures.grid(width = 60, tileWidth = 32),
            badLength, badConfiguration, badGridGeometry,
        )) GridTestFixtures.withSource(bytes) { root, source ->
            val target = File(root, "canonical.rgb"); val backend = Backend()
            val info = HeicContainer.inspect(source)
            try { HeicImport(plentiful, backend, gridEnabled = true).write(source, info, target, coroutineContext); fail("Rejected grid reached pixel decoding") }
            catch (_: PhotoException) { /* Controlled import failure, not an escaping planner exception. */ }
            assertEquals("Never retry the whole-image route", 0, backend.calls)
            assertCleanFailure(root, target, backend)
            assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun onlyNonGridAndExplicitlyDisabledCandidatesKeepTheWholeImagePath() = runBlocking {
        for ((bytes, enabled) in listOf(
            GridTestFixtures.fixture("corners-sdr8.heic") to true,
            GridTestFixtures.grid() to false,
        )) GridTestFixtures.withSource(bytes) { root, source ->
            val target = File(root, "canonical.rgb"); val info = HeicContainer.inspect(source)
            var calls = 0; var closed = false
            val backend = object : HeicBackend {
                override val supportsIndependentTiles = true
                override fun supported() = true
                override fun decode(file: File, info: HeicContainer.Info, job: CoroutineContext): HeicPixels {
                    calls++; assertEquals(source.canonicalPath, file.canonicalPath)
                    return object : HeicPixels {
                        override val width = info.displayWidth; override val height = info.displayHeight
                        override fun readRow(y: Int, argb: IntArray) { argb.fill(0xffffffff.toInt()) }
                        override fun close() { closed = true }
                    }
                }
            }
            HeicImport(plentiful, backend, gridEnabled = enabled).write(source, info, target, coroutineContext)
            assertEquals(1, calls); assertTrue(closed)
            assertEquals(info.displayWidth.toLong() * info.displayHeight * 12, target.length())
            assertEquals(setOf("source.heic", "canonical.rgb"), root.list()!!.toSet())
        }
    }

    private fun assertCleanFailure(root: File, target: File, backend: Backend) {
        assertFalse("No partial source is publishable", target.exists())
        assertEquals(0, backend.live); assertTrue(backend.views.all { !it.exists() })
        assertEquals(setOf("source.heic"), root.list()!!.toSet())
    }

    private fun assertSpool(target: File, info: HeicContainer.Info) {
        assertEquals(info.encodedWidth.toLong() * info.encodedHeight * 12, target.length())
        val values = ByteBuffer.wrap(target.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        for (dy in 0 until info.displayHeight) for (dx in 0 until info.displayWidth) {
            // Independent inverse mapping from display to original grid coordinates.
            val (sx, sy) = when (info.rotationDegreesClockwise) {
                0 -> dx to dy
                90 -> dy to info.encodedHeight - 1 - dx
                180 -> info.encodedWidth - 1 - dx to info.encodedHeight - 1 - dy
                270 -> info.encodedWidth - 1 - dy to dx
                else -> error("Unexpected test rotation")
            }
            val pixel = marker(sy / 32 * 2 + sx / 48, sx % 48, sy % 32)
            val alpha = pixel ushr 24
            for (channel in 2 downTo 0) {
                val code = (((pixel ushr (channel * 8)) and 255) * alpha + 255 * (255 - alpha) + 127) / 255
                assertEquals("Original ($sx,$sy) displayed at ($dx,$dy), channel $channel", LinearRgb.srgb8ToLinear[code], values.float, 0f)
            }
        }
        assertFalse(values.hasRemaining())
    }

    companion object {
        private fun marker(tile: Int, x: Int, y: Int): Int {
            val alpha = when ((x + y) % 3) { 0 -> 255; 1 -> 128; else -> 0 }
            return (alpha shl 24) or ((20 + tile * 43 + x) shl 16) or ((30 + y * 5) shl 8) or (40 + (x + y) % 130)
        }
    }
}
