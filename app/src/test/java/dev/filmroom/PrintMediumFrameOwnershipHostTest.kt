package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrintMediumFrameOwnershipHostTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun snapshot(p:EditorState,w:Int=180,h:Int=120)=BorderCatalog.snapshot(p.frameStock,w,h,w,h,p.composition,p.negativeWhiteHoles,p.outputRoute)
    @Test fun everyCaptureFilmKeepsItsFrameAndCompositionWhenPaperChangesTo2302() {
        BorderCatalog.initialize(app.assets)
        val supported135=FrameProfileCatalog.kinds.filterKeys{BorderCatalog.available(it,null)}
        assertEquals(27,supported135.size)
        assertEquals(CandidateBorderDefinitions.profiles.keys+setOf("kodak_doublex","kodak_trix")+Reviewed135Definitions.stocks,supported135.keys)
        assertEquals(FrameProfileCatalog.kinds.keys,supported135.keys)
        for((stock,kind) in supported135)for(portrait in listOf(false,true)) {
            val routes=if(kind==FrameAppearanceContext.FilmKind.REVERSAL)listOf("direct")else listOf("print","direct")
            for(route in routes)for(white in listOf(false,true)) {
                val p=EditorState(filmStock=stock,outputRoute=route,filmBorder=true,negativeWhiteHoles=white,
                    composition=CompositionTransform(.61,.42,1.7,if(portrait)2.0/3 else 1.5))
                val paper=p.copy(paperStock="kodak_2302",paperDevelopmentTime=7f,paperGamma=1.2f)
                assertEquals(stock,paper.frameStock)
                assertEquals(p.presentationTransform(180,120),paper.presentationTransform(180,120))
                assertEquals(snapshot(p),snapshot(paper))
                assertEquals(p.negativeWhiteHolesSupported,paper.negativeWhiteHolesSupported)
                assertEquals(kind==FrameAppearanceContext.FilmKind.NEGATIVE,paper.negativeWhiteHolesSupported)
                val json=JSONObject(paper.json("export",includePresentation=false))
                assertEquals("kodak_2302",json.getString("paperStock"))
                assertEquals(7.0,json.getDouble("paperDevelopmentTime"),0.0)
                assertEquals(1.2,json.getDouble("paperGamma"),1e-6)
                assertEquals(paper,EditorState.fromJson(paper.json()))
            }
        }
    }
    @Test fun newJobsFreezeCaptureFilmButOld2302JobsKeepExactHistoricalFrame() {
        BorderCatalog.initialize(app.assets)
        val root=Files.createTempDirectory("2302-frame-ownership").toFile()
        try {
            val jobs=mutableListOf<ExportJob>()
            for(stock in listOf("kodak_doublex","kodak_portra_400"))for(portrait in listOf(false,true)) {
                val p=EditorState(filmStock=stock,paperStock="kodak_2302",filmBorder=true,
                    filmDevelopmentTime=9f,paperDevelopmentTime=7f,
                    composition=CompositionTransform(.61,.42,1.7,if(portrait)2.0/3 else 1.5))
                val old=BorderCatalog.snapshot("kodak_2302",180,120,180,120,p.composition,p.negativeWhiteHoles,"print")
                val fresh=snapshot(p)
                assertEquals(stock,fresh.stock);assertEquals("kodak_2302",old.stock)
                assertNotEquals(old.composition.targetAspect,fresh.composition.targetAspect)
                for(border in listOf(old,fresh)) {
                    BorderCatalog.verifySnapshot(border)
                    jobs+=ExportJob(ExportJobStore.newId(),jobs.size.toLong(),p,180,120,ExportFormat.PNG,95,state=ExportJobState.PAUSED,border=border)
                }
                val oldJob=jobs[jobs.size-2]
                assertTrue(runCatching{oldJob.copy(parameters=p.copy(paperStock="kodak_portra_endura"))}.isFailure)
                assertTrue(runCatching{oldJob.copy(parameters=p.copy(outputRoute="direct"))}.isFailure)
                assertTrue(runCatching{oldJob.copy(border=old.copy(assetVersion="unknown-2302"))}.isFailure)
                assertTrue(runCatching{oldJob.copy(border=old.copy(appearance=null))}.isFailure)
                assertTrue(runCatching{oldJob.copy(parameters=p.copy(filmBorder=false))}.isFailure)
            }
            val store=ExportJobStore(root);jobs.forEach(store::update)
            val reopened=ExportJobStore(root)
            assertEquals(8,reopened.jobs.value.size)
            jobs.forEach { before ->
                val after=requireNotNull(reopened.get(before.id))
                assertEquals(before.parameters,after.parameters);assertEquals(before.border,after.border)
                assertEquals(before.outputWidth,after.outputWidth);assertEquals(before.outputHeight,after.outputHeight)
                assertEquals(ExportJobState.PAUSED,after.state)
            }
            val off=EditorState(filmStock="kodak_doublex",paperStock="kodak_2302",filmBorder=false)
            val unframed=ExportJob(ExportJobStore.newId(),99,off,180,120,ExportFormat.PNG,95)
            assertNull(unframed.border);assertEquals(180,unframed.outputWidth);assertEquals(120,unframed.outputHeight)
        } finally {root.deleteRecursively()}
    }
    @Test fun previewAndFloatExportUseCaptureFrameFor2302AtBothOrientations() {
        BorderCatalog.initialize(app.assets)
        val dir=Files.createTempDirectory("2302-frame-pixels").toFile()
        try {
            for(stock in listOf("kodak_doublex","kodak_portra_400","kodak_trix"))for(portrait in listOf(false,true)) {
                val p=EditorState(filmStock=stock,paperStock="kodak_2302",filmBorder=true,
                    outputRoute=if(stock=="kodak_trix")"direct"else "print",
                    composition=CompositionTransform(.58,.44,1.5,if(portrait)2.0/3 else 1.5))
                val s=snapshot(p)
                val context=FrameAppearanceContext.forProfile(stock,p.outputRoute,p.negativeWhiteHoles)
                val d=if(stock=="kodak_doublex" || stock=="kodak_trix")BwBorderDefinitions.selected(stock,context)
                    else CandidateBorderDefinitions.selectedDefinition(stock,p.negativeWhiteHoles)
                assertEquals(d.spec.version,s.assetVersion)
                val r=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
                val pixels=IntArray(180*120){i-> val c=(i%180)*255/179;0xff000000.toInt() or(c shl 16)or(c shl 8)or c}
                val photo=Bitmap.createBitmap(pixels,180,120,Bitmap.Config.ARGB_8888)
                val preview=BorderPreview.compose(photo,s,r)
                val input=File(dir,"input");val output=File(dir,"output")
                val bytes=ByteBuffer.allocate(180*120*12).order(ByteOrder.LITTLE_ENDIAN)
                pixels.forEach{pixel->repeat(3){bytes.putFloat(LinearRgb.srgb8ToLinear[pixel and 255])}};input.writeBytes(bytes.array())
                BorderCompositor.composite(input,output,s,r,OutputColorSpace.SRGB)
                val values=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for(y in 0 until preview.height)for(x in 0 until preview.width)for(c in 0..2)
                    assertEquals((preview.getPixel(x,y) ushr(16-c*8))and 255,OutputColorSpace.SRGB.encode8(values.get()))
                preview.recycle();photo.recycle()
            }
        } finally {dir.deleteRecursively()}
    }
}
