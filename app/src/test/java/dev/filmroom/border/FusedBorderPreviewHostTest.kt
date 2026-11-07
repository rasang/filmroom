package dev.filmroom.border

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.EditorUiState
import dev.filmroom.imprint.ImprintPlan
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.CompositionTransform
import dev.filmroom.imprint.ImprintSettings
import dev.filmroom.ui.PreviewHistogramInput
import dev.filmroom.ui.PreviewHistogramSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FusedBorderPreviewHostTest {
    private fun photo(w:Int=180,h:Int=120)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {
        val row=IntArray(w)
        for(y in 0 until h){for(x in 0 until w)row[x]=android.graphics.Color.rgb(x*255/w,y*255/h,(x+y)%256);setPixels(row,0,w,0,y,w,1)}
    }
    private fun verify(photo:Bitmap,s:BorderSnapshot,raster:BorderRaster,imprint:ImprintPlan?=null,checkGrayHistogram:Boolean=false) {
        val overlay=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888)
        val referenceOverlay=InteractiveBorderPreview.create(s,imprint?.frameRaster(s,raster) ?: raster)
        val reference=BorderPreview.compose(photo,s,raster,imprint)
        val actual=BorderPreview.composeWithOverlay(photo,s,raster,imprint,overlay)
        try {
            assertTrue("Exact preview changed: ${s.stock}/${s.assetVersion}/${s.portrait}",reference.sameAs(actual))
            assertTrue("Gesture overlay changed: ${s.stock}/${s.assetVersion}/${s.portrait}",referenceOverlay.sameAs(overlay))
            if(checkGrayHistogram) {
                val state=EditorUiState(photo=PhotoSource(File("fused-synthetic"),"synthetic.png","image/png",photo.width,photo.height,0),
                    rendered=actual,compositionSource=photo,presentation=s)
                val input=checkNotNull(PreviewHistogramInput.from(state))
                assertSame("Histogram must retain the pre-frame photo",photo,input.bitmap)
                val histogram=runBlocking{checkNotNull(PreviewHistogramSampler.sample(input))}
                assertEquals("New BW120 marks/date/exposure must not enter the histogram",histogram.samples,histogram.bins[64])
            }
        }finally{overlay.recycle();referenceOverlay.recycle();reference.recycle();actual.recycle()}
    }
    @Test fun current135ContractsMatchSeparatePassesInBothOrientations() {
        val app=RuntimeEnvironment.getApplication()
        val definitions=(CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions+
            DirectBorderDefinitions.definitions+BwBorderDefinitions.definitions+BwBorderDefinitions.legacyDefinitions+
            Reviewed135Definitions.definitions).associateBy{it.spec.version}
        val profiles=CandidateBorderDefinitions.profiles+BwBorderDefinitions.profiles+Reviewed135Definitions.profiles
        val registry=LazyBorderRegistry(definitions.values.toList(),profiles){d,active->StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)},active)}
        val stocks=profiles.keys
        val photo=photo()
        try {
            for(stock in stocks){
                val routes=when(FrameProfileCatalog.kind(stock)){
                    FrameAppearanceContext.FilmKind.NEGATIVE->listOf("print","direct")
                    FrameAppearanceContext.FilmKind.REVERSAL->listOf("direct")
                    else->listOf("print")
                }
                for(route in routes)for(white in listOf(false,true)) {
                    val initial=BorderCatalog.snapshot(stock,photo.width,photo.height,1800,1200,CompositionTransform(),white,route)
                    val raster=registry.resolve(initial)
                    for(portrait in listOf(false,true)){
                        val s=BorderCatalog.snapshot(stock,photo.width,photo.height,1800,1200,CompositionTransform(.44,.57,1.7,targetAspect=if(portrait)2.0/3 else 1.5),white,route)
                        val imprint=ImprintPlan.create(ImprintSettings("contax_t2",exposure=true),PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2025:04:28 12:34:56", "PhotographicSensitivity" to "400", "ExposureTime" to "1/250", "FNumber" to "56/10")),s.outputWidth,s.outputHeight,s)
                        verify(photo,s,raster,imprint)
                    }
                }
            }
        }finally{photo.recycle()}
    }
    @Test fun mediumImprintStandardizationKeepsSeparateOverlayContract()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        BorderCatalog.initialize(app.assets){NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val photo=photo()
        try {withContext(Dispatchers.Default){
            for(portrait in listOf(false,true))for(enabled in listOf(false,true)){
                val s=BorderCatalog.snapshot("kodak_portra_400",photo.width,photo.height,1800,1200,CompositionTransform(.44,.57,1.2),true,"print",MediumFormat.MF66,portrait)
                val raster=BorderCatalog.resolve(s)
                val imprint=ImprintPlan.create(if(enabled)ImprintSettings("contax_t2",exposure=true) else ImprintSettings(),PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2025:04:28 12:34:56", "PhotographicSensitivity" to "400", "ExposureTime" to "1/250", "FNumber" to "56/10")),s.outputWidth,s.outputHeight,s)
                verify(photo,s,raster,imprint)
            }
        }}finally{photo.recycle();BorderCatalog.clearCache()}
    }
    @Test fun newBw120ImprintsKeepFusedParityAndStayOutsideHistogram()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        BorderCatalog.initialize(app.assets){NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val photo=photo().apply{eraseColor(0xff808080.toInt())}
        var cases=0
        try {withContext(Dispatchers.Default){
            for(stock in listOf("ilford_hp5_plus_400","kodak_tmax_100","kodak_tri_x_400"))
                for(format in MediumFormat.entries)for(portrait in listOf(false,true))
                    for(route in listOf("print","direct"))for(enabled in listOf(false,true)){
                        val s=BorderCatalog.snapshot(stock,photo.width,photo.height,1800,1200,
                            CompositionTransform(.44,.57,1.2),true,route,format,portrait)
                        val raster=BorderCatalog.resolve(s)
                        val settings=if(enabled)ImprintSettings("contax_t2",exposure=true,datePlacement="edge") else ImprintSettings()
                        val metadata=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2025:04:28 12:34:56",
                            "PhotographicSensitivity" to "400","ExposureTime" to "1/250","FNumber" to "56/10"))
                        verify(photo,s,raster,ImprintPlan.create(settings,metadata,s.outputWidth,s.outputHeight,s),true)
                        cases++
                    }
        }}finally{photo.recycle();BorderCatalog.clearCache()}
        assertEquals(120,cases)
    }
    @Test fun fusedPassSamplesOnlyOnceAndCancellationKeepsCallerOwnership() {
        var samples=0;var coverage=0
        val raster=object:BorderRaster {
            override val stock="kodak_portra_400";override val version="fused-fixture"
            override val railRatio=.2;override val endRatio=.03;override val residentBytes=12L;override val featherTotalAt3072=4.0
            override fun photoCoverage(snapshot:BorderSnapshot,x:Double,y:Double):Double?{coverage++;return null}
            override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){samples++;rgb[0]=.25f;rgb[1]=.5f;rgb[2]=.75f}
        }
        val photo=photo();val s=BorderSnapshot.fixed135(raster.stock,raster.version,photo.width,photo.height)
        val reference=BorderPreview.compose(photo,s,raster);val exactSamples=samples;val exactCoverage=coverage
        val overlay=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888)
        try {
            samples=0;coverage=0
            val actual=BorderPreview.composeWithOverlay(photo,s,raster,null,overlay)
            try{assertTrue(reference.sameAs(actual));assertEquals(exactSamples,samples);assertEquals(exactCoverage,coverage)}finally{actual.recycle()}
            var checks=0
            assertThrows(CancellationException::class.java){BorderPreview.composeWithOverlay(photo,s,raster,null,overlay){if(++checks>8)throw CancellationException("test cancellation")}}
            assertFalse("Never recycle published science",photo.isRecycled)
            assertFalse("Caller owns incomplete overlay",overlay.isRecycled)
        }finally{photo.recycle();reference.recycle();overlay.recycle()}
    }
}
