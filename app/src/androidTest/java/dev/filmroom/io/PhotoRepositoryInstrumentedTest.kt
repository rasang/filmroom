package dev.filmroom.io

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic local pixels only. These tests never read the user's photo collection. */
@RunWith(AndroidJUnit4::class)
class PhotoRepositoryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val repo = PhotoRepository(context)
    private val created = mutableListOf<Uri>()
    private val loaded = mutableListOf<LoadedPhoto>()

    @After fun cleanUp() {
        loaded.forEach { it.preview.recycle(); repo.discard(it.source) }
        created.forEach { resolver.delete(it, null, null) }
        context.getSharedPreferences("filmroom-pending-exports", 0).edit().clear().commit()
    }

    @Test fun importsPngWithinExplicitPreviewBoundsAndReopens() = runBlocking {
        val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 100, 170))
        val uri = encodedSource(bitmap, ExportFormat.PNG)
        bitmap.recycle()
        val photo = repo.import(uri).also { loaded += it }
        assertEquals("image/png", photo.source.mimeType)
        assertEquals(1600, photo.source.originalWidth)
        assertEquals(800, photo.source.originalHeight)
        assertEquals(1280, photo.preview.width)
        assertEquals(640, photo.preview.height)
        assertEquals(Bitmap.Config.ARGB_8888, photo.preview.config)
        val reopened = repo.reopen(photo.source.cacheKey, photo.source.name).also { loaded += it }
        assertEquals(photo.preview.width, reopened.preview.width)
        assertEquals(photo.preview.getPixel(20, 20), reopened.preview.getPixel(20, 20))
    }

    @Test fun appliesJpegExifRotationExactlyOnce() = runBlocking {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        for (y in 0 until 80) for (x in 0 until 120) {
            bitmap.setPixel(x, y, if (x < 60) Color.RED else Color.BLUE)
        }
        val file = File.createTempFile("rotation-test-", ".jpg", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val uri = rawSource("image/jpeg", file.readBytes())
            val photo = repo.import(uri).also { loaded += it }
            assertEquals(80, photo.source.originalWidth)
            assertEquals(120, photo.source.originalHeight)
            assertEquals(80, photo.preview.width)
            assertEquals(120, photo.preview.height)
            assertTrue(Color.red(photo.preview.getPixel(40, 20)) > Color.blue(photo.preview.getPixel(40, 20)))
            assertTrue(Color.blue(photo.preview.getPixel(40, 100)) > Color.red(photo.preview.getPixel(40, 100)))
        } finally { file.delete(); bitmap.recycle() }
    }

    @Test fun transparentPngIsCompositedOntoWhiteInsteadOfDroppingAlpha() = runBlocking {
        val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.setPixel(10, 4, Color.argb(128, 255, 0, 0))
        try {
            val photo = repo.import(encodedSource(bitmap, ExportFormat.PNG)).also { loaded += it }
            assertTrue(photo.source.alphaCompositedOnWhite)
            assertFalse(photo.preview.hasAlpha())
            assertEquals(Color.WHITE, photo.preview.getPixel(1, 1))
            val blended = photo.preview.getPixel(10, 4)
            assertEquals(255, Color.red(blended))
            assertTrue(Color.green(blended) in 126..129)
            assertTrue(Color.blue(blended) in 126..129)
        } finally { bitmap.recycle() }
    }

    @Test fun corruptAndUnsupportedImportsDoNotDamageExistingPhoto() = runBlocking {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val photo = repo.import(encodedSource(bitmap, ExportFormat.PNG)).also { loaded += it }
        bitmap.recycle()
        for (bytes in listOf("GIF89a".toByteArray(), byteArrayOf(-1, -40, -1, 0, 1, 2, 3))) {
            val bad = rawSource("image/jpeg", bytes)
            try {
                repo.import(bad)
                fail("Invalid image must not be accepted")
            } catch (_: PhotoException) { }
        }
        val reopened = repo.reopen(photo.source.cacheKey).also { loaded += it }
        assertEquals(Color.GREEN, reopened.preview.getPixel(5, 5))
    }

    @Test fun restoreRejectsPathTraversal() = runBlocking {
        try {
            repo.reopen("../private.jpg")
            fail("Arbitrary paths must not be accepted")
        } catch (e: PhotoException) {
            assertEquals(PhotoException.Reason.UNREADABLE, e.reason)
        }
    }

    @Test fun exportsNewFinalizedMediaWithoutChangingInput() = runBlocking {
        val bitmap = Bitmap.createBitmap(31, 19, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(70, 130, 180))
        try {
            val result = repo.export(bitmap, ExportFormat.JPEG)
            created += result.uri
            assertEquals(31, result.width)
            assertEquals(19, result.height)
            assertFinalized(result.uri)
            val imported = repo.import(result.uri).also { loaded += it }
            assertEquals(31, imported.preview.width)
            assertEquals(19, imported.preview.height)
            assertEquals(Color.rgb(70, 130, 180), bitmap.getPixel(5, 5))
        } finally { bitmap.recycle() }
    }

    @Test fun exportDoesNotCopyLocationOwnerOrCameraMetadataOrRewriteOriginalBytes() = runBlocking {
        val bitmap = Bitmap.createBitmap(36, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(110, 80, 60))
        val file = File.createTempFile("metadata-test-", ".jpg", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            val sensitiveTags = arrayOf(
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_ARTIST, ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL,
            )
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "4/1,5/1,6/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                setAttribute(ExifInterface.TAG_ARTIST, "Synthetic Test Owner")
                setAttribute(ExifInterface.TAG_MAKE, "Synthetic Test Camera")
                setAttribute(ExifInterface.TAG_MODEL, "Synthetic Test Model")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
                saveAttributes()
            }
            val originalBytes = file.readBytes()
            val sourceUri = rawSource("image/jpeg", originalBytes)
            val photo = repo.import(sourceUri).also { loaded += it }
            val result = repo.export(photo.preview, ExportFormat.JPEG)
            created += result.uri
            resolver.openInputStream(result.uri)!!.use { input ->
                val metadata = ExifInterface(input)
                sensitiveTags.forEach { tag -> assertNull("Output must not copy $tag", metadata.getAttribute(tag)) }
            }
            resolver.openInputStream(sourceUri)!!.use { input ->
                assertArrayEquals("The original encoded file must not be rewritten", originalBytes, input.readBytes())
            }
        } finally { file.delete(); bitmap.recycle() }
    }

    @Test fun commitCallbackPreservesSuccessWhenCancellationRacesResultDelivery() = runBlocking {
        val bitmap = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888)
        val committed = AtomicReference<ExportResult?>()
        var worker: Job? = null
        try {
            worker = launch(start = CoroutineStart.LAZY) {
                repo.export(bitmap, ExportFormat.PNG, onCommitted = { result ->
                    committed.set(result)
                    worker!!.cancel()
                })
            }
            worker.start()
            worker.join()
            val result = committed.get()
            assertNotNull("Commit must be observable even when UI cancellation wins delivery", result)
            created += result!!.uri
            assertFinalized(result.uri)
        } finally { bitmap.recycle() }
    }

    @Test fun startupRecoveryDeletesOnlyJournaledOwnedPendingRows() = runBlocking {
        val uri = rawSource("image/jpeg", byteArrayOf(-1, -40, -1), finalize = false)
        journal(uri)
        val report = repo.cleanupPendingExports()
        assertEquals(1, report.removed)
        assertEquals(0, report.unresolved)
        resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)!!.use {
            assertFalse(it.moveToFirst())
        }
        assertTrue(context.getSharedPreferences("filmroom-pending-exports", 0).getStringSet("uris", emptySet())!!.isEmpty())
    }

    @Test fun originalSpoolMatchesImageDecoderForAllEightExifOrientations() = runBlocking {
        val raw = Bitmap.createBitmap(129, 73, Bitmap.Config.ARGB_8888)
        for (y in 0 until raw.height) for (x in 0 until raw.width) {
            raw.setPixel(x, y, Color.rgb((x * 3 + y) % 256, (y * 5 + x) % 256, (x * 2 + y * 2) % 256))
        }
        try {
            for (orientation in 1..8) {
                val file = File.createTempFile("exif-spool-test-", ".jpg", context.cacheDir)
                val spool = File(context.cacheDir, "spool-exif-${System.nanoTime()}.rgbf32")
                try {
                    file.outputStream().use { assertTrue(raw.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                    ExifInterface(file.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                        saveAttributes()
                    }
                    val photo = repo.import(rawSource("image/jpeg", file.readBytes()), 512).also { loaded += it }
                    val size = repo.spoolOriginal(photo.source, spool)
                    assertEquals(photo.preview.width, size.width)
                    assertEquals(photo.preview.height, size.height)
                    assertEquals(size.width.toLong() * size.height * 12, spool.length())
                    val floats = ByteBuffer.wrap(spool.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    for (y in 0 until size.height) for (x in 0 until size.width) {
                        val expected = photo.preview.getPixel(x, y)
                        val red = LinearRgb.toSrgb8(floats.float)
                        val green = LinearRgb.toSrgb8(floats.float)
                        val blue = LinearRgb.toSrgb8(floats.float)
                        assertTrue("EXIF $orientation red ($x,$y)", kotlin.math.abs(Color.red(expected) - red) <= 2)
                        assertTrue("EXIF $orientation green ($x,$y)", kotlin.math.abs(Color.green(expected) - green) <= 2)
                        assertTrue("EXIF $orientation blue ($x,$y)", kotlin.math.abs(Color.blue(expected) - blue) <= 2)
                    }
                } finally { spool.delete(); file.delete() }
            }
        } finally { raw.recycle() }
    }

    @Test fun originalPngSpoolUsesSameOpaqueWhiteAlphaPolicyAsPreview() = runBlocking {
        val raw = Bitmap.createBitmap(30, 70, Bitmap.Config.ARGB_8888)
        raw.eraseColor(Color.TRANSPARENT)
        raw.setPixel(10, 67, Color.argb(128, 255, 0, 0))
        val spool = File(context.cacheDir, "spool-alpha-${System.nanoTime()}.rgbf32")
        try {
            val photo = repo.import(encodedSource(raw, ExportFormat.PNG), 512).also { loaded += it }
            val size = repo.spoolOriginal(photo.source, spool)
            val floats = ByteBuffer.wrap(spool.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until size.height) for (x in 0 until size.width) {
                val expected = photo.preview.getPixel(x, y)
                assertTrue(kotlin.math.abs(Color.red(expected) - LinearRgb.toSrgb8(floats.float)) <= 1)
                assertTrue(kotlin.math.abs(Color.green(expected) - LinearRgb.toSrgb8(floats.float)) <= 1)
                assertTrue(kotlin.math.abs(Color.blue(expected) - LinearRgb.toSrgb8(floats.float)) <= 1)
            }
        } finally { spool.delete(); raw.recycle() }
    }

    @Test fun thirteenMegapixelOriginalPngKeeps5000By2600WithoutFullBitmap() = runBlocking {
        val width = 5000
        val height = 2600
        val expectedBytes = width.toLong() * height * 12
        val sourcePng = java.io.ByteArrayOutputStream()
        LinearRgbPng.encode(ZeroInput(expectedBytes), width, height, sourcePng)
        val source = repo.import(rawSource("image/png", sourcePng.toByteArray()), 512).also { loaded += it }
        assertEquals(512, source.preview.width)
        assertEquals(width, source.source.originalWidth)
        assertEquals(height, source.source.originalHeight)
        val spool = File(context.cacheDir, "spool-13mp-${System.nanoTime()}.rgbf32")
        try {
            val dimensions = repo.spoolOriginal(source.source, spool)
            assertEquals(PhotoLimits.Dimensions(width, height), dimensions)
            assertEquals(expectedBytes, spool.length())
            RandomAccessFile(spool, "r").use {
                for (offset in listOf(0L, expectedBytes / 2, expectedBytes - 12)) {
                    it.seek(offset)
                    assertEquals(0L, it.readLong())
                    assertEquals(0, it.readInt())
                }
            }
            val result = repo.exportLinearRgbPng(spool, width, height)
            created += result.uri
            assertFinalized(result.uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(result.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            assertEquals(width, bounds.outWidth)
            assertEquals(height, bounds.outHeight)
            assertEquals(width, result.width)
            assertEquals(height, result.height)
        } finally { spool.delete() }
    }

    @Test fun cancelledOriginalSpoolLeavesNeitherPartNorFinalTarget() = runBlocking {
        val png = java.io.ByteArrayOutputStream()
        LinearRgbPng.encode(ZeroInput(5000L * 2600 * 12), 5000, 2600, png)
        val photo = repo.import(rawSource("image/png", png.toByteArray()), 512).also { loaded += it }
        val target = File(context.cacheDir, "spool-cancel-${System.nanoTime()}.rgbf32")
        val before = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        val worker = launch { repo.spoolOriginal(photo.source, target) }
        try {
            withTimeout(30_000) {
                while (context.cacheDir.listFiles().orEmpty().none { it.name !in before && it.name.startsWith("original-spool-") && it.length() > 0 }) {
                    if (worker.isCompleted) fail("Spool completed before cancellation point was observed")
                    delay(1)
                }
            }
            worker.cancelAndJoin()
            assertFalse(target.exists())
            val remainingParts = context.cacheDir.listFiles().orEmpty().filter { it.name !in before && it.name.startsWith("original-spool-") }
            assertTrue("Cancelled .part files must be removed", remainingParts.isEmpty())
            assertTrue(photo.source.localFile.isFile)
        } finally { worker.cancelAndJoin(); target.delete() }
    }

    @Test fun cancelledStreamingPngRemovesItsPendingMediaRowAndJournal() = runBlocking {
        val file = File(context.cacheDir, "output-cancel-${System.nanoTime()}.rgbf32")
        RandomAccessFile(file, "rw").use { it.setLength(5000L * 2600 * 12) }
        val committed = AtomicReference<ExportResult?>()
        var pendingUri: Uri? = null
        var uncertain = false
        var worker: Job? = null
        try {
            worker = launch(start = CoroutineStart.LAZY) {
                repo.exportLinearRgbPng(file, 5000, 2600,
                    onCommitted = { committed.set(it) },
                    onCleanupUncertain = { uncertain = true },
                    onProgress = { rows, _ ->
                        if (rows == 2) {
                            val pending = context.getSharedPreferences("filmroom-pending-exports", 0).getStringSet("uris", emptySet()).orEmpty()
                            pendingUri = pending.singleOrNull()?.let(Uri::parse)
                            worker!!.cancel()
                        }
                    },
                )
            }
            worker.start(); worker.join()
            assertNull(committed.get())
            assertFalse(uncertain)
            assertNotNull("Cancellation must occur after insertion", pendingUri)
            resolver.query(pendingUri!!, arrayOf(MediaStore.Images.Media._ID), null, null, null)!!.use { assertFalse(it.moveToFirst()) }
            assertTrue(context.getSharedPreferences("filmroom-pending-exports", 0).getStringSet("uris", emptySet())!!.isEmpty())
        } finally { worker?.cancelAndJoin(); file.delete(); pendingUri?.let { resolver.delete(it, null, null) } }
    }

    private class ZeroInput(private var left: Long) : InputStream() {
        override fun read(): Int = if (left > 0) { left--; 0 } else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (left == 0L) return -1
            val count = minOf(length.toLong(), left).toInt()
            java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
            left -= count
            return count
        }
    }

    @Test fun crashAfterCommitBeforeJournalClearPreservesFinalizedPhoto() = runBlocking {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        try {
            val result = repo.export(bitmap, ExportFormat.PNG)
            created += result.uri
            journal(result.uri) // Simulate process death after commit, before clearing journal.
            val report = repo.cleanupPendingExports()
            assertEquals(0, report.removed)
            assertEquals(1, report.preservedFinalized)
            assertEquals(0, report.unresolved)
            assertFinalized(result.uri)
        } finally { bitmap.recycle() }
    }

    @Test fun recoveryRefusesNonMediaOrNonExactUrisAndKeepsUnresolvedJournal() = runBlocking {
        val unexpected = Uri.parse("content://unrelated-provider/private/42")
        journal(unexpected)
        val report = repo.cleanupPendingExports()
        assertEquals(0, report.removed)
        assertEquals(1, report.unresolved)
        assertTrue(context.getSharedPreferences("filmroom-pending-exports", 0).getStringSet("uris", emptySet())!!.contains(unexpected.toString()))
    }

    private fun journal(uri: Uri) {
        assertTrue(context.getSharedPreferences("filmroom-pending-exports", 0).edit().putStringSet("uris", setOf(uri.toString())).commit())
    }

    private fun assertFinalized(uri: Uri) {
        resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.SIZE), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
            assertTrue(it.getLong(1) > 0)
        }
    }

    private fun encodedSource(bitmap: Bitmap, format: ExportFormat): Uri {
        val bytes = java.io.ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(requireNotNull(format.compression), 100, it))
            it.toByteArray()
        }
        return rawSource(format.mimeType, bytes)
    }

    private fun rawSource(mime: String, bytes: ByteArray, finalize: Boolean = true): Uri {
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Filmroom_test_${System.nanoTime()}")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmroomTests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        if (finalize) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
