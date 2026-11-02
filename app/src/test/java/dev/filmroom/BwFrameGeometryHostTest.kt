package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.model.CompositionTransform
import dev.filmroom.io.OutputColorSpace
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
class BwFrameGeometryHostTest {
    @Test fun immutableLayoutsRoundTripAndPreserveCropCenterWithout135Fallback(){
        val app=RuntimeEnvironment.getApplication();BorderCatalog.initialize(app.assets)
        for(stock in listOf("kodak_doublex","kodak_trix","kodak_2302"))for(portrait in listOf(false,true)){
            val route=if(stock=="kodak_2302")"print" else "direct"
            val s=BorderCatalog.snapshot(stock,if(portrait)240 else 360,if(portrait)360 else 240,900,600,CompositionTransform(.6,.4,2.0,BorderCatalog.frameAspect(stock,portrait)),false,route)
            assertEquals(4,s.geometryVersion);assertEquals(portrait,s.portrait)
            val logicalHeight=if(portrait)s.layout!!.apertureWidth else s.layout!!.apertureHeight
            assertTrue(kotlin.math.abs(s.photoHeight-logicalHeight*s.pixelsPerMm)<=.500001)
            assertEquals(.6,s.composition.centerX,0.0);assertEquals(.4,s.composition.centerY,0.0);assertEquals(2.0,s.composition.zoom,0.0)
            assertEquals(s,BorderSnapshot.fromJson(s.json()));BorderCatalog.verifySnapshot(s)
            assertEquals(BorderCatalog.frameAspect(stock,portrait),s.composition.targetAspect!!,0.0)
        }
        for(stock in listOf("kodak_doublex","kodak_trix","kodak_2302"))for(white in listOf(false,true)){
            val legacyStyle=BorderCatalog.snapshot(stock,180,120,180,120,CompositionTransform(),white)
            BorderCatalog.verifySnapshot(legacyStyle)
        }
        assertEquals("kodak_doublex",BorderCatalog.frameStock("kodak_doublex","kodak_2302","print"))
        assertEquals("kodak_doublex",BorderCatalog.frameStock("kodak_doublex","kodak_2302","direct"))
    }
    @Test fun allApprovedOriginalOverlaysDecodeAndHolesRemainDistinctFromImages(){
        val app=RuntimeEnvironment.getApplication()
        for(d in BwBorderDefinitions.definitions){
            val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
            val context=FrameAppearanceContext(if(d.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION || d.spec.stock=="kodak_trix")"direct" else "print",FrameProfileCatalog.kind(d.spec.stock),d.spec.holeMatteSrgb==0xffffff)
            val s=BorderSnapshot.authored(d.spec.stock,d.spec.version,1046,766,featherTotalAt3072=d.spec.featherTotalAt3072).copy(appearance=context)
            raster.validate(s)
            assertTrue(raster.photoCoverage(s,s.apertureLeft+s.photoWidth/2,s.apertureTop+s.photoHeight/2)!!>.99)
            val g=s.layout!!;val hole=when(d.spec.stock){"kodak_trix"->166.0 to 450.0;"kodak_2302"->95.0 to 95.0;else->96.0 to 100.0}
            val x=hole.first*s.pixelsPerMm+s.frameOriginX;val y=hole.second*s.pixelsPerMm+s.frameOriginY
            assertEquals(0.0,raster.photoCoverage(s,x,y)!!,0.0)
            val rgb=FloatArray(3);raster.sample(s,x,y,rgb)
            assertEquals(if(d.spec.holeMatteSrgb==0)0f else 1f,rgb[0],1e-6f)
        }
    }
    @Test fun super8AdjacentWindowsRepeatCurrentCropAtSameScaleLandscapeAndPortrait(){
        val d=BwBorderDefinitions.definitions.single{it.spec.stock=="kodak_trix"}
        val app=RuntimeEnvironment.getApplication();val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
        for(portrait in listOf(false,true)){
            val s=BorderSnapshot.authored("kodak_trix",d.spec.version,if(portrait)766 else 1046,if(portrait)1046 else 766,composition=CompositionTransform(targetAspect=BorderCatalog.frameAspect("kodak_trix",portrait)))
            val map=BorderSampling(s);val pitch=(796*s.pixelsPerMm).toInt()
            if(!portrait){
                val edge=20;assertEquals(map.row(edge+pitch),map.row(edge))
                assertTrue(raster.photoCoverage(s,s.apertureLeft+s.photoWidth/2,edge+.5)!!>.99)
            }else{
                val edge=20;assertEquals(map.x0[edge],map.x0[edge+pitch]);assertEquals(map.fraction[edge],map.fraction[edge+pitch],1e-10)
                assertTrue(raster.photoCoverage(s,edge+.5,s.apertureTop+s.photoHeight/2)!!>.99)
            }
        }
    }
    @Test fun sharedPreviewAndFloatExportAgreeForAllLayoutsAndOrientations(){
        val app=RuntimeEnvironment.getApplication();val dir=Files.createTempDirectory("bw-frames").toFile()
        try{for(d in BwBorderDefinitions.definitions)for(portrait in listOf(false,true)){
            val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
            val w=if(portrait)120 else 180;val h=if(portrait)180 else 120
            val s=BorderSnapshot.authored(d.spec.stock,d.spec.version,w,h,featherTotalAt3072=d.spec.featherTotalAt3072)
            val pixels=IntArray(w*h){i->val v=(i%w)*255/(w-1);0xff000000.toInt() or(v shl 16)or(v shl 8)or v}
            val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
            val preview=BorderPreview.compose(bitmap,s,raster)
            val values=FloatArray(w*h*3){i->dev.filmroom.io.LinearRgb.srgb8ToLinear[pixels[i/3]and 255]}
            val input=File(dir,"in");val output=File(dir,"out");val bytes=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN);bytes.asFloatBuffer().put(values);input.writeBytes(bytes.array())
            BorderCompositor.composite(input,output,s,raster,OutputColorSpace.SRGB)
            val result=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for(y in 0 until preview.height)for(x in 0 until preview.width){val pixel=preview.getPixel(x,y);for(c in 0..2)assertEquals((pixel ushr (16-c*8))and 255,OutputColorSpace.SRGB.encode8(result.get()))}
            preview.recycle();bitmap.recycle()
        }}finally{dir.deleteRecursively()}
    }
    @Test fun queuedBwContractsFreezeRoleGeometryAppearanceAcrossRestart(){
        val app=RuntimeEnvironment.getApplication();BorderCatalog.initialize(app.assets)
        val dir=Files.createTempDirectory("bw-queue-contracts").toFile()
        try {
            val store=dev.filmroom.export.ExportJobStore(dir)
            for((index,d) in BwBorderDefinitions.definitions.withIndex()){
                val stock=d.spec.stock
                val route=if(d.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION || stock=="kodak_trix")"direct" else "print"
                val p=dev.filmroom.model.EditorState(filmStock=if(stock=="kodak_2302")"kodak_doublex" else stock,paperStock=if(stock=="kodak_2302")stock else "kodak_portra_endura",filmBorder=true,outputRoute=route,negativeWhiteHoles=d.spec.holeMatteSrgb==0xffffff,composition=CompositionTransform(.6,.4,2.0))
                // Explicit stock retains the historical 2302 contract in this frozen-job matrix.
                val snap=BorderCatalog.snapshot(stock,180,120,180,120,p.composition,p.negativeWhiteHoles,p.outputRoute)
                assertEquals(d.spec.version,snap.assetVersion)
                val job=dev.filmroom.export.ExportJob(dev.filmroom.export.ExportJobStore.newId(),index.toLong()+1,p,180,120,dev.filmroom.io.ExportFormat.PNG,95,state=dev.filmroom.export.ExportJobState.PAUSED,border=snap)
                store.update(job)
            }
            val restored=dev.filmroom.export.ExportJobStore(dir).jobs.value
            assertEquals(6,restored.size)
            for(job in restored){assertEquals(4,job.border!!.geometryVersion);BorderCatalog.verifySnapshot(job.border!!);assertEquals(job.parameters.presentationTransformForFrame(180,120,job.border!!.stock),job.border!!.composition)}
        }finally{dir.deleteRecursively()}
    }

    @Test fun renderRuntimeVisualEvidenceFromCurrentPhotographNotBakedSamples(){
        val app=RuntimeEnvironment.getApplication();val dir=File("build/ui-host-renders/bw-runtime").apply{mkdirs()}
        for(d in BwBorderDefinitions.definitions)for(portrait in listOf(false,true)){
            val w=if(portrait)400 else 600;val h=if(portrait)600 else 400
            val pixels=IntArray(w*h){i->val x=i%w;val y=i/w;val v=if((x/40+y/40)%2==0)70+x*130/w else 25+y*180/h;0xff000000.toInt() or(v shl 16)or(v shl 8)or v}
            val photo=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
            val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
            val snap=BorderSnapshot.authored(d.spec.stock,d.spec.version,w,h,composition=CompositionTransform(.55,.45,1.3),featherTotalAt3072=d.spec.featherTotalAt3072)
            val output=BorderPreview.compose(photo,snap,raster)
            File(dir,"${d.spec.version}-${if(portrait)"portrait" else "landscape"}.png").outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)}
            val overlay=InteractiveBorderPreview.create(snap,raster)
            val view=dev.filmroom.ui.PhotoView(app).apply{layout(0,0,400,460);bitmap=output}
            try {
                var moved=snap.composition
                view.bindComposition(snap,photo,snap.composition,true,overlay){t,_->moved=t}
                assertTrue(view.compositionGeometryReady)
                view.pan(15.0,8.0);assertTrue(view.isLiveComposition)
                val live=Bitmap.createBitmap(400,460,Bitmap.Config.ARGB_8888)
                try{view.draw(android.graphics.Canvas(live));File(dir,"live-${d.spec.version}-${if(portrait)"portrait" else "landscape"}.png").outputStream().use{live.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{live.recycle()}
                view.finishCompositionGesture()
                assertNotEquals(snap.composition,moved)
                view.discardCompositionGesture();assertFalse(view.isLiveComposition)
            }finally{view.close();overlay.recycle();output.recycle();photo.recycle()}
        }
    }

}
