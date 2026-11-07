package dev.filmroom.ui

import android.graphics.Bitmap
import android.graphics.Color
import dev.filmroom.EditorUiState
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.crop.CropSnapshot
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.CropMode
import dev.filmroom.model.CropSettings
import dev.filmroom.model.EditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewHistogramHostTest {
    private val photo = PhotoSource(File("histogram-synthetic"), "synthetic.png", "image/png", 256, 128, 0)
    private fun bitmap(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun input(b: Bitmap) = PreviewHistogramInput(b, 0, 0, b.width, b.height)
    private fun sample(input: PreviewHistogramInput) = runBlocking { PreviewHistogramSampler.sample(input)!! }

    @Test fun blackWhiteAndRgbUseDisplayBrightnessBins() {
        for ((color, bin) in listOf(Color.BLACK to 0, Color.WHITE to 127, Color.RED to 27, Color.GREEN to 91, Color.BLUE to 9)) {
            val b = bitmap(17, 11, color)
            val result = sample(input(b))
            assertEquals(187, result.samples); assertEquals(187, result.bins.sum())
            assertEquals(187, result.bins[bin]); assertEquals(187, result.peak)
            b.recycle()
        }
    }

    @Test fun wholePreviewIncludesBothExtremesAndDoesNotMutateThePhoto() {
        val b = bitmap(1536, 768, Color.WHITE)
        for (y in 0 until b.height) for (x in 0 until b.width / 2) b.setPixel(x, y, Color.BLACK)
        val generation = b.generationId
        val result = sample(input(b))
        assertEquals(b.width * b.height, result.samples)
        assertEquals(result.samples / 2, result.bins[0]); assertEquals(result.samples / 2, result.bins[127])
        assertEquals(generation, b.generationId); assertFalse(b.isRecycled)
        b.recycle()
    }

    @Test fun onePixelAndThinImagesAreValid() {
        for ((w, h) in listOf(1 to 1, 1 to 1024, 1024 to 1)) {
            val b = bitmap(w, h, Color.WHITE)
            val result = sample(input(b))
            assertEquals(w * h, result.samples)
            assertEquals(result.samples, result.bins[127]); b.recycle()
        }
    }

    @Test fun periodicTexturesCannotAliasToOnlyBlackOrOnlyWhite() {
        for ((w, h) in listOf(512 to 512, 768 to 512, 1536 to 1024)) {
            for (checkerboard in listOf(false, true)) {
                val pixels = IntArray(w * h) { index ->
                    val phase = index % w + if (checkerboard) index / w else 0
                    if (phase % 2 == 0) Color.BLACK else Color.WHITE
                }
                val b = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                try {
                    val data = sample(input(b))
                    assertEquals(w * h, data.samples)
                    assertEquals(w * h / 2, data.bins[0])
                    assertEquals(w * h / 2, data.bins[127])
                } finally { b.recycle() }
            }
        }
    }

    @Test fun everyPixelInAnOffsetCropMatchesDecimalRec709Reference() {
        val width = 256
        val pixels = IntArray(width * width) { index -> Color.rgb(index % width, index / width, 255 - index % width) }
        val b = Bitmap.createBitmap(pixels, width, width, Bitmap.Config.ARGB_8888)
        try {
            val request = PreviewHistogramInput(b, 7, 11, 249, 251)
            val expected = IntArray(128)
            for (y in request.top until request.bottom) for (x in request.left until request.right) {
                val c = pixels[y * width + x]
                val rounded = ((2126L * Color.red(c) + 7152L * Color.green(c) + 722L * Color.blue(c) + 5000L) / 10000L).toInt()
                expected[rounded / 2]++
            }
            val data = sample(request)
            assertArrayEquals(expected, data.bins)
            assertEquals((249 - 7) * (251 - 11), data.samples)
        } finally { b.recycle() }
    }

    @Test fun sourceSelectionFollowsComparisonAndAdoptedCropNotPendingSettings() {
        val original = bitmap(256, 128, Color.BLACK)
        val processed = bitmap(256, 128, Color.WHITE)
        for (y in 0 until 128) for (x in 0 until 128) processed.setPixel(x, y, Color.BLACK)
        val cropped = bitmap(128, 128, Color.WHITE)
        val crop = CropSettings.fromRect(CompositionTransform.Rect(.5, 0.0, .5, 1.0), 256, 128)
        val snapshot = CropSnapshot.create(256, 128, settings = crop)
        val state = EditorUiState(photo = photo, original = original, rendered = cropped, compositionSource = processed,
            cropPresentation = snapshot, renderedParameters = EditorState(crop = crop),
            parameters = EditorState(crop = crop))
        val request = PreviewHistogramInput.from(state)!!
        assertSame(processed, request.bitmap); assertEquals(128, request.left); assertEquals(256, request.right)
        assertEquals(sample(request).samples, sample(request).bins[127])
        assertSame(original, PreviewHistogramInput.from(state.copy(compare = true))!!.bitmap)
        assertSame(original, PreviewHistogramInput.from(state.copy(rendered = null))!!.bitmap)
        assertNull(PreviewHistogramInput.from(state.copy(photo = null)))
        assertNull(PreviewHistogramInput.from(state.copy(busy = "import")))
        assertNull(PreviewHistogramInput.from(state.copy(compositionEditing = true)))
        val pending = state.copy(parameters = state.parameters.copy(crop = CropSettings(CropMode.ORIGINAL)))
        assertNull("No old crop graph while a committed/cancelled/undone live crop awaits adoption", PreviewHistogramInput.from(pending))
        assertNotNull("Exposure waiting keeps the old photo and its graph", PreviewHistogramInput.from(state.copy(parameters = state.parameters.copy(brightness = 1f))))
        assertSame(original, PreviewHistogramInput.from(pending.copy(compare = true))!!.bitmap)
        original.recycle(); processed.recycle(); cropped.recycle()
    }

    @Test fun filmFrameNeverContributesToThePhotoHistogram() {
        val processed = bitmap(30, 20, Color.WHITE)
        val border = BorderSnapshot("kodak_portra_400", "test", photoWidth = 30, photoHeight = 20, rail = 4, end = 1)
        val framed = bitmap(border.outputWidth, border.outputHeight, Color.BLACK)
        val state = EditorUiState(photo = photo, rendered = framed, compositionSource = processed, presentation = border)
        val result = sample(PreviewHistogramInput.from(state)!!)
        assertEquals(result.samples, result.bins[127]); assertEquals(0, result.bins[0])
        // Compatibility fallback still limits a composited bitmap to its actual aperture.
        val fallback = PreviewHistogramInput.from(state.copy(compositionSource = null))!!
        assertEquals(1, fallback.left); assertEquals(4, fallback.top)
        assertEquals(31, fallback.right); assertEquals(24, fallback.bottom)
        processed.recycle(); framed.recycle()
    }

    @Test fun rotatedCropCountsEveryPixelInsideIndependentCornerPolygon(){
        val w=193;val h=131
        val colors=IntArray(w*h){i->when((i%w+i/w)%4){0->Color.BLACK;1->Color.WHITE;2->Color.RED;else->Color.BLUE}}
        val b=Bitmap.createBitmap(colors,w,h,Bitmap.Config.ARGB_8888)
        try{
            for(angle in listOf(-15.0,-1.0,1.0,15.0))for(zoom in listOf(1.0,2.7)){
                val transform=CompositionTransform(.13,.78,zoom,1.5,angle).clamped(6000,4000)
                val crop=CropSettings(CropMode.FREE,transform)
                val snapshot=CropSnapshot.create(w,h,6000,4000,crop)
                val state=EditorUiState(photo=PhotoSource(File("histogram-rotated"),"fixture","image/png",6000,4000,0),
                    original=b,rendered=b,compositionSource=b,cropPresentation=snapshot,parameters=EditorState(crop=crop),renderedParameters=EditorState(crop=crop))
                val request=PreviewHistogramInput.from(state)!!;assertEquals(transform,request.rotation)
                val vertices=listOf(0.0 to 0.0,1.0 to 0.0,1.0 to 1.0,0.0 to 1.0).map{transform.sourcePoint(it.first,it.second,6000,4000)}
                val bins=IntArray(128)
                for(y in 0 until h)for(x in 0 until w){
                    val px=(x+.5)/w;val py=(y+.5)/h
                    val crosses=vertices.indices.map{i->val a=vertices[i];val c=vertices[(i+1)%4];(c.first-a.first)*(py-a.second)-(c.second-a.second)*(px-a.first)}
                    if(crosses.all{it>0} || crosses.all{it<0}){
                        val color=colors[y*w+x];val luma=(2126*Color.red(color)+7152*Color.green(color)+722*Color.blue(color)+5000)/10000;bins[luma ushr 1]++
                    }
                }
                val actual=sample(request);assertEquals(bins.sum(),actual.samples);assertArrayEquals(bins,actual.bins)
                assertNull(PreviewHistogramInput.from(state.copy(compositionEditing=true)))
                assertNull(PreviewHistogramInput.from(state.copy(parameters=state.parameters.copy(crop=crop.copy(transform=transform.copy(straightenDegrees=0.0))))))
                assertNull(PreviewHistogramInput.from(state.copy(compare=true))!!.rotation)
            }
        }finally{b.recycle()}
    }

    @Test fun recycledAndCancelledInputsAreNotPublished() {
        val b = bitmap(4, 4, Color.WHITE)
        val request = input(b)
        b.recycle()
        assertNull(runBlocking { PreviewHistogramSampler.sample(request) })
        val valid = bitmap(4, 4, Color.BLACK)
        try {
            runBlocking(Job().apply { cancel() }) { PreviewHistogramSampler.sample(input(valid)) }
            fail("Cancelled sampling must stop")
        } catch (_: CancellationException) { /* expected */ }
        valid.recycle()
    }
}
