package dev.filmroom

import android.app.Application
import android.net.Uri
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.PhotoException
import dev.filmroom.io.PhotoRepository
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

/**
 * Host Android-framework component tests with real NativeGraphics ImageDecoder.
 * ContentResolver is a host test substitute, NOT a PhotoPicker/provider/device
 * verification. BitmapRegionDecoder is NOT exercised;4.14.1 has a blank shadow.
 * Fixtures are locally generated synthetic noise, never user photographs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighResolutionImportHostTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repo = PhotoRepository(context)
    private val fixtureRoot: File by lazy {
        val configured = System.getProperty("filmroom.highres.fixtures")
        assumeTrue("可选12–50MP主机套件未执行：请显式设置filmroom.highres.fixtures并生成合成图片。", !configured.isNullOrBlank())
        File(checkNotNull(configured)).also { assertTrue("已配置的合成图片目录必须存在", it.isDirectory) }
    }
    private var nextId = 1

    private fun register(input: InputStream): Uri {
        val uri = Uri.parse("content://dev.filmroom.synthetic-test/${nextId++}")
        shadowOf(context.contentResolver).registerInputStream(uri, input)
        return uri
    }

    private fun verifyImport(name: String, expectedWidth: Int, expectedHeight: Int) = runBlocking {
        val input = File(fixtureRoot, name)
        assertTrue("Synthetic fixture must exist: $name", input.isFile)
        val closed = AtomicBoolean(false)
        val stream = object : FilterInputStream(input.inputStream()) {
            override fun close() { closed.set(true); super.close() }
        }
        val loaded = repo.import(register(stream), 512)
        try {
            assertTrue(closed.get())
            assertEquals(expectedWidth, loaded.source.originalWidth)
            assertEquals(expectedHeight, loaded.source.originalHeight)
            assertEquals(input.length(), loaded.source.encodedBytes)
            assertEquals(512, maxOf(loaded.preview.width, loaded.preview.height))
            val sampled = mutableSetOf<Int>()
            for (y in listOf(0, loaded.preview.height / 3, loaded.preview.height - 1)) {
                for (x in listOf(0, loaded.preview.width / 3, loaded.preview.width - 1)) sampled += loaded.preview.getPixel(x, y)
            }
            assertTrue("Noise preview must contain real, nonuniform pixels", sampled.size >= 3)
            assertTrue(loaded.source.localFile.isFile)
            assertEquals(input.length(), loaded.source.localFile.length())
            assertTrue(input.isFile)
        } finally {
            loaded.preview.recycle()
            repo.discard(loaded.source)
            assertFalse(loaded.source.localFile.exists())
        }
    }

    @Test fun imports12MegapixelHighEntropyPngThroughActualProductionRepository() = verifyImport("synthetic-noise-12mp-rgb.png", 4000, 3000)
    @Test fun importsNominal50Megapixel151MBRgbPngThroughActualProductionRepository() = verifyImport("synthetic-noise-50mp-rgb.png", 8192, 6144)
    @Test fun importsNominal50Megapixel201MBRgbaPngThroughActualProductionRepository() = verifyImport("synthetic-noise-50mp-rgba.png", 8192, 6144)
    @Test fun imports12MegapixelJpegThroughActualProductionRepository() = verifyImport("synthetic-noise-12mp-orientation1.jpg", 4000, 3000)
    @Test fun importsNominal50MegapixelJpegThroughActualProductionRepository() = verifyImport("synthetic-noise-50mp-orientation1.jpg", 8192, 6144)
    @Test fun nominal50MegapixelJpegExifRotationIsAppliedExactlyOnce() = verifyImport("synthetic-noise-50mp-orientation6.jpg", 6144, 8192)

    @Test fun twelveMegapixelJpegAllEightExifOrientationsMatchIndependentQuadrantOracle() = runBlocking {
        val colors = listOf(Color.rgb(210, 50, 20), Color.rgb(30, 170, 60), Color.rgb(25, 70, 210), Color.rgb(220, 180, 35))
        val layouts = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2))
        for (orientation in 1..8) {
            val input = File(fixtureRoot, "synthetic-quadrants-12mp-orientation$orientation.jpg")
            assertTrue("八方向图片缺失，请运行生成脚本的--orientations-only选项", input.isFile)
            val loaded = repo.import(register(input.inputStream()), 512)
            try {
                assertEquals(if (orientation >= 5) 3000 else 4000, loaded.source.originalWidth)
                assertEquals(if (orientation >= 5) 4000 else 3000, loaded.source.originalHeight)
                val image = loaded.preview
                val points = listOf(image.width / 4 to image.height / 4, image.width * 3 / 4 to image.height / 4,
                    image.width / 4 to image.height * 3 / 4, image.width * 3 / 4 to image.height * 3 / 4)
                for ((corner, point) in points.withIndex()) {
                    val actual = image.getPixel(point.first, point.second)
                    val expected = colors[layouts[orientation - 1][corner]]
                    assertTrue("EXIF$orientation 象限$corner 红通道不一致", kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 3)
                    assertTrue("EXIF$orientation 象限$corner 绿通道不一致", kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 3)
                    assertTrue("EXIF$orientation 象限$corner 蓝通道不一致", kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 3)
                }
            } finally { loaded.preview.recycle(); repo.discard(loaded.source) }
        }
    }

    @Test fun cancelledLargeImportClosesStreamRemovesPartialCopyAndPreservesPriorSource() = runBlocking {
        val previousInput = File(fixtureRoot, "synthetic-noise-12mp-rgb.png")
        val previous = repo.import(register(previousInput.inputStream()), 512)
        val sourceFolder = File(context.cacheDir, "photo-sources")
        val before = sourceFolder.listFiles().orEmpty().map { it.name }.toSet()
        val closed = AtomicBoolean(false)
        var worker: Job? = null
        val input = object : FilterInputStream(File(fixtureRoot, "synthetic-noise-50mp-rgba.png").inputStream()) {
            private var bytes = 0L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) bytes += count
                if (bytes >= 1024 * 1024) worker!!.cancel()
                return count
            }
            override fun close() { closed.set(true); super.close() }
        }
        try {
            val uri = register(input)
            worker = launch(start = CoroutineStart.LAZY) { repo.import(uri, 512); fail("Cancelled import must not return a photo") }
            worker.start(); worker.join()
            assertTrue(worker.isCancelled)
            assertTrue(closed.get())
            assertEquals(before, sourceFolder.listFiles().orEmpty().map { it.name }.toSet())
            assertTrue(previous.source.localFile.isFile)
            assertFalse(previous.preview.isRecycled)
        } finally { input.close(); previous.preview.recycle(); repo.discard(previous.source) }
    }

    @Test fun failingLargeInputStreamRemovesPartialCopyWithoutReplacingPriorSource() = runBlocking {
        val previousInput = File(fixtureRoot, "synthetic-noise-12mp-rgb.png")
        val previous = repo.import(register(previousInput.inputStream()), 512)
        val sourceFolder = File(context.cacheDir, "photo-sources")
        val before = sourceFolder.listFiles().orEmpty().map { it.name }.toSet()
        val input = object : FilterInputStream(File(fixtureRoot, "synthetic-noise-50mp-rgb.png").inputStream()) {
            private var bytes = 0L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (bytes >= 512 * 1024) throw IOException("synthetic provider interruption")
                return super.read(buffer, offset, length).also { if (it > 0) bytes += it }
            }
        }
        try {
            try { repo.import(register(input), 512); fail("Expected source IO failure") } catch (_: PhotoException) { }
            assertEquals(before, sourceFolder.listFiles().orEmpty().map { it.name }.toSet())
            assertTrue(previous.source.localFile.isFile)
        } finally { input.close(); previous.preview.recycle(); repo.discard(previous.source) }
    }
}
