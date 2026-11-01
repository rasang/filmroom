package dev.filmroom.io

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.filmroom.engine.NativeWorkReservations
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real platform-codec tests, not host/mock decoder evidence. Fixtures are original CC0 markers.
 * No MediaStore, user photographs, network, or fake unlimited memory snapshot is used.
 * An unavailable platform HEIF codec is explicitly skipped, never reported as decoded.
 */
@RunWith(AndroidJUnit4::class)
class HeicImportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun removingGainmapLeavesSoftwareSdrBasePixelsUnchanged() {
        assumeTrue(android.os.Build.VERSION.SDK_INT>=34)
        val base=Bitmap.createBitmap(intArrayOf(0xffb76063.toInt(),0xff48ad5b.toInt(),0xff6464b0.toInt(),0xffaea15d.toInt()),2,2,Bitmap.Config.ARGB_8888)
        val enhancement=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        try {
            enhancement.eraseColor(Color.WHITE)
            val before=IntArray(4);base.getPixels(before,0,2,0,0,2,2)
            base.setGainmap(android.graphics.Gainmap(enhancement).apply { setRatioMax(4f,3f,2f) })
            assertTrue(base.hasGainmap())
            HeicSdrBasePolicy.prepare(base)
            val after=IntArray(4);base.getPixels(after,0,2,0,0,2,2)
            assertArrayEquals(before,after);assertFalse(base.hasGainmap())
        } finally {base.recycle();enhancement.recycle()}
    }

    @Test fun primaryImageProducesFullSizeCanonicalSpool() = runBlocking {
        verify("corners-sdr8.heic", 96, 64, "RGBY")
    }

    @Test fun containerRotation90CounterclockwiseIsAppliedOnce() = runBlocking {
        verify("corners-rotate90ccw.heic", 64, 96, "GYRB")
    }

    @Test fun containerRotation180IsAppliedOnce() = runBlocking {
        verify("corners-rotate180.heic", 96, 64, "YBGR")
    }

    @Test fun containerRotation270CounterclockwiseIsAppliedOnce() = runBlocking {
        verify("corners-rotate270ccw.heic", 64, 96, "BRYG")
    }

    @Test fun gridPrimaryReconstructsAllFourCodedTiles() = runBlocking {
        verify("corners-grid2x2.heic", 96, 64, "RGBY")
    }

    @Test fun ordinaryThumbnailDoesNotReplaceTheGridPrimary() = runBlocking {
        verify("corners-grid-thumbnail.heic", 96, 64, "RGBY")
    }

    @Test fun verifiedIccP3IsConvertedNotRelabeledIncludingTileOnlyGrid()=runBlocking {
        assumeTrue("SKIPPED: device has no HEIF decoder",AndroidHeicBackend.supported())
        // Independently decoded lossy fixture + LittleCMS relative-colorimetric reference.
        val expected=listOf(intArrayOf(183,96,99),intArrayOf(72,173,91),intArrayOf(100,100,176),intArrayOf(174,161,93))
        for(name in listOf("corners-icc-p3.heic","corners-grid-icc-p3-tiles.heic")){
            val source=copyFixture(name);val spool=File(context.cacheDir,"heic-icc-${System.nanoTime()}.rgb")
            var preview:Bitmap?=null
            try{
                val before=sha256(source);val info=HeicContainer.inspect(source)
                assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,info.sourceColor)
                HeicImport({NativeWorkReservations.snapshot(context)}).write(source,info,spool,currentCoroutineContext())
                val full=LinearSpool.preview(spool,96,64,128,currentCoroutineContext());preview=full
                quarterPoints(96,64).forEachIndexed{i,(x,y)->
                    val color=full.getPixel(x,y);val rgb=intArrayOf(Color.red(color),Color.green(color),Color.blue(color))
                    repeat(3){c->assertTrue("ICC source must be converted, $name corner $i channel $c: ${rgb[c]}",kotlin.math.abs(rgb[c]-expected[i][c])<=5)}
                }
                assertEquals(before,sha256(source));assertEquals(96L*64*12,spool.length())
            }finally{preview?.recycle();spool.delete();source.delete()}
        }
    }

    @Test fun mirroredMain10AndWideGamutInputsAreRejectedBeforePlatformDecode() {
        for (name in listOf("corners-mirror.heic", "corners-main10.heic", "corners-display-p3.heic")) {
            val source = copyFixture(name)
            try {
                val before = sha256(source)
                assertEquals("Fixture identity", expectedHashes.getValue(name), before)
                try {
                    HeicContainer.inspect(source)
                    fail("$name must not silently discard unsupported metadata/precision")
                } catch (expected: HeicContainer.Rejected) {
                    assertEquals(HeicContainer.Failure.UNSUPPORTED, expected.failure)
                }
                assertEquals("Rejected input remains immutable", before, sha256(source))
            } finally { assertTrue("Remove private fixture", source.delete()) }
        }
    }

    private suspend fun verify(name: String, width: Int, height: Int, cornerLabels: String) {
        assumeTrue("SKIPPED: this device does not report image/heif decoder support",
            AndroidHeicBackend.supported())
        val source = copyFixture(name)
        val spool = File(context.cacheDir, "heic-instrumented-${System.nanoTime()}.rgb")
        var full: Bitmap? = null
        var small: Bitmap? = null
        try {
            val before = sha256(source)
            assertEquals("Fixture identity", expectedHashes.getValue(name), before)
            val info = HeicContainer.inspect(source)
            assertEquals(width, info.displayWidth)
            assertEquals(height, info.displayHeight)
            // Real ActivityManager state and the same process-wide admission used by production.
            HeicImport(memory = { NativeWorkReservations.snapshot(context) }, backend = AndroidHeicBackend)
                .write(source, info, spool, currentCoroutineContext())
            assertEquals("Full original pixels, not a thumbnail", width.toLong() * height * 12, spool.length())
            val previewFull = LinearSpool.preview(spool, width, height, 128, currentCoroutineContext()).also { full = it }
            val previewSmall = LinearSpool.preview(spool, width, height, 48, currentCoroutineContext()).also { small = it }
            assertEquals(width, previewFull.width)
            assertEquals(height, previewFull.height)
            assertEquals(Bitmap.Config.ARGB_8888, previewFull.config)
            assertEquals(48, maxOf(previewSmall.width, previewSmall.height))
            assertCorners(previewFull, cornerLabels)
            assertCorners(previewSmall, cornerLabels)
            // Full-resolution preview must encode the identical canonical linear pixel values.
            RandomAccessFile(spool, "r").use { input ->
                for ((x, y) in quarterPoints(width, height)) {
                    input.seek((y.toLong() * width + x) * 12)
                    val bytes = ByteArray(12); input.readFully(bytes)
                    val linear = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val rgb = IntArray(3) { LinearRgb.toSrgb8(linear.float) }
                    val preview = previewFull.getPixel(x, y)
                    assertEquals(rgb[0], Color.red(preview))
                    assertEquals(rgb[1], Color.green(preview))
                    assertEquals(rgb[2], Color.blue(preview))
                    assertEquals(255, Color.alpha(preview))
                }
            }
            assertEquals("Successful import never rewrites HEIC source", before, sha256(source))
        } finally {
            full?.recycle(); small?.recycle()
            if (spool.exists()) assertTrue("Remove private spool", spool.delete())
            assertTrue("Remove private fixture", source.delete())
        }
    }

    private fun assertCorners(bitmap: Bitmap, labels: String) {
        quarterPoints(bitmap.width, bitmap.height).forEachIndexed { i, (x, y) ->
            val pixel = bitmap.getPixel(x, y)
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            val matches = when (labels[i]) {
                'R' -> r > 160 && r > g + 80 && r > b + 80
                'G' -> g > 140 && g > r + 80 && g > b + 80
                'B' -> b > 160 && b > r + 80 && b > g + 80
                'Y' -> r > 150 && g > 150 && b < 100
                else -> false
            }
            assertTrue("Corner $i expected ${labels[i]}, got RGB($r,$g,$b) at $x,$y", matches)
        }
    }

    private fun quarterPoints(w: Int, h: Int) = listOf(
        w / 4 to h / 4, 3 * w / 4 to h / 4,
        w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4,
    )

    private fun copyFixture(name: String): File {
        val file = File.createTempFile("heic-instrumented-", ".heic", context.cacheDir)
        try {
            instrumentation.context.assets.open("heic/$name").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            return file
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private val expectedHashes = mapOf(
        "corners-grid-thumbnail.heic" to "69c9054ef627f3b70775e340269fd33d66025559112de98e3ed0bdb2f04ba47d",
        "corners-display-p3.heic" to "bfaf8cd1c1808f05bd0c3880e266205d5d6437ba82535b210cb77472eff2fa79",
        "corners-mirror.heic" to "e3614156f9b024267005e805edf8b0212faddbc17f08dd5a58e8e2bbc496a0a1",
        "corners-main10.heic" to "74c771638bb76e469bc7ed2705d093fce1223cb8d7caff4e3e2c0b3badb760d7",
        "corners-sdr8.heic" to "9c65cf368d5779fb709929a16397028772c2b59bedf02cf67f300295e710e432",
        "corners-rotate90ccw.heic" to "36202b2933d15bf417edafcda092efefb8a96fad3020feda413830ed186e9074",
        "corners-rotate180.heic" to "830549b7226b47c5effa733c91c5ed00cb0da7e073570bb49b26c328e66c306e",
        "corners-rotate270ccw.heic" to "b10e28ff9d6ebb16bdb8c84b9111109dfa90f3695c4dae8c2f59ee74dfbba7aa",
        "corners-grid2x2.heic" to "c66be65e05fb2eefdbe9b49f6e666b9959992f3bd81756146a949abb285992ac",
    )
}
