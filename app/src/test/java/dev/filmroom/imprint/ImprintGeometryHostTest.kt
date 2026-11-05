package dev.filmroom.imprint

import dev.filmroom.border.*
import dev.filmroom.io.PhotoMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Small masks only: the complete stock/format/orientation sweep never allocates an output photo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class ImprintGeometryHostTest {
    private val styles=ImprintSettings.DATE_STYLES.filter{it!="off"}
    private val settings=ImprintSettings("contax_t2",exposure=true)
    private val base=mapOf("DateTimeOriginal" to "2025:04:28 12:34:56","PhotographicSensitivity" to "400",
        "MeteringMode" to "5","ExposureTime" to "1/250","FNumber" to "56/10","ExposureBiasValue" to "1/2","FocalLength" to "800/1")
    private fun metadata(extra:Map<String,String> = emptyMap())=PhotoMetadata.fromAttributes(base+extra)
    private fun medium(stock:String,format:MediumFormat,portrait:Boolean,w:Int=1280,h:Int=960):BorderSnapshot {
        val physical=MediumFormatStocks.physicalStock(stock)
        return BorderSnapshot.medium120(stock,MediumStripDefinitions.definitions.getValue(physical).version,w,h,
            mediumFormat=format,mediumPortrait=portrait).copy(appearance=FrameAppearanceContext.forProfile(stock,if(FrameProfileCatalog.kind(stock)==FrameAppearanceContext.FilmKind.REVERSAL)"direct"else "print",true))
    }
    private fun plan(b:BorderSnapshot,s:ImprintSettings=settings,m:PhotoMetadata=metadata())=
        ImprintPlan.create(s,m,b.outputWidth,b.outputHeight,b)
    private fun bounded(p:ImprintPlan) {
        assertTrue(p.residentBytes<=ImprintPlan.MAX_WORKING_BYTES)
        p.stamps.forEach { stamp ->
            assertTrue("full mask outside ${p.width}x${p.height}: $stamp",stamp.left>=0&&stamp.top>=0&&stamp.right<=p.width&&stamp.bottom<=p.height)
            assertTrue(stamp.raster.residentBytes<=ImprintRaster.MAX_MASK_BYTES)
        }
    }
    /** Convert the full padded raster rectangle back to physical, unrotated film coordinates. */
    private fun physical(b:BorderSnapshot,s:ImprintPlan.Stamp):DoubleArray {
        val ppm=b.pixelsPerMm
        val x0=(s.left-b.frameOriginX)/ppm;val x1=(s.right-b.frameOriginX)/ppm
        val y0=(s.top-b.frameOriginY)/ppm;val y1=(s.bottom-b.frameOriginY)/ppm
        return if(b.mediumLayout!!.rotated)doubleArrayOf(y0,61-x1,y1,61-x0)else doubleArrayOf(x0,y0,x1,y1)
    }
    private fun clearEdge(b:BorderSnapshot,p:ImprintPlan,dateOnPhoto:Boolean) {
        bounded(p)
        val l=checkNotNull(MediumImprintMarks.layout(b))
        val tolerance=.51/b.pixelsPerMm // Integer placement rounds to the nearest pixel.
        p.stamps.drop(if(dateOnPhoto)1 else 0).forEach { stamp ->
            val r=physical(b,stamp)
            assertTrue("${b.stock}/${b.mediumFormat}: full mask collides with left arrow/number",r[0]>=l.clearStartMm-tolerance)
            assertTrue("${b.stock}/${b.mediumFormat}: full mask collides with right arrow/number",r[2]<=l.clearEndMm+tolerance)
            assertTrue("edge mask overlaps photograph",r[1]>=58.5-tolerance)
            assertTrue("edge mask exceeds film",r[3]<=61.0+tolerance)
        }
    }
    @Test fun all135CatalogStocksBothOrientationsAllStylesAndPlacementsAreBounded() {
        var supported=0
        for(stock in FrameProfileCatalog.kinds.keys + "kodak_2302") {
            val landscape=BorderSnapshot.authored(stock,"test",1280,960)
            if(ImprintPlan.exposureSupported(landscape))supported++
            for(portrait in listOf(false,true))for(style in styles)for(placement in ImprintSettings.PLACEMENTS) {
                val b=BorderSnapshot.authored(stock,"test",if(portrait)960 else 1280,if(portrait)1280 else 960)
                val p=plan(b,settings.copy(dateStyle=style,datePlacement=placement))
                bounded(p)
                assertEquals("$stock/$style/$placement",1+(if(ImprintPlan.exposureSupported(b))5 else 0),p.stamps.size)
                assertEquals(0,p.omittedFieldCount)
            }
        }
        assertEquals(21,supported)
        assertFalse(ImprintPlan.exposureSupported(BorderSnapshot.authored("kodak_2302","test",1280,960)))
    }
    @Test fun nine120StocksFiveFormatsTwoOrientationsFourStylesAndBothDatePlacementsClearAllMarks() {
        val stocks=MediumFormatStocks.profiles.values.toSet();assertEquals(9,stocks.size)
        for(stock in stocks)for(format in MediumFormat.entries)for(portrait in listOf(false,true))
            for(style in styles)for(placement in ImprintSettings.PLACEMENTS) {
                val b=medium(stock,format,portrait)
                val p=plan(b,settings.copy(dateStyle=style,datePlacement=placement))
                assertEquals("$stock/$format/$portrait/$style/$placement",6,p.stamps.size)
                assertEquals(0,p.omittedFieldCount)
                clearEdge(b,p,placement=="photo")
            }
    }
    @Test fun extremeWholeMetadataValuesFitWithoutMutatingSource() {
        // Pairwise representative combinations avoid a huge repeated raster Cartesian product.
        val isos=listOf("6400","102400","4294967295")
        val shutters=listOf("1/8000","1/32000","3600/1")
        val focals=listOf("400","800","1200","2000","4294967295")
        for(i in 0 until 15) {
            val iso=isos[i%isos.size];val shutter=shutters[i%shutters.size];val focal=focals[i%focals.size]
            val m=metadata(mapOf("PhotographicSensitivity" to "65535","SensitivityType" to "3","ISOSpeed" to iso,"ExposureTime" to shutter,"FocalLength" to focal))
            val frozen=m.toJson();val content=ImprintContent.from(m,settings)
            assertEquals("ISO$iso",content.fields.first().text)
            assertEquals("${focal}mm",content.fields.last().text)
            assertEquals(if(shutter=="3600/1")"3600s"else shutter.removePrefix("1/"),content.fields[1].text)
            val b=medium("fujifilm_provia_100f",MediumFormat.entries[i%5],i%2==0)
            val p=plan(b,settings.copy(datePlacement="edge",dateStyle=styles[i%4],size=1.6),m)
            assertEquals(6,p.stamps.size);assertEquals(0,p.omittedFieldCount);clearEdge(b,p,false)
            val b135=BorderSnapshot.authored("kodak_portra_400","test",1280,960)
            val p135=plan(b135,settings.copy(size=1.6),m)
            assertEquals(6,p135.stamps.size);bounded(p135)
            assertEquals(frozen,m.toJson())
        }
    }
    @Test fun dateSizeAndGlowNeverChangeCaptureDataPixelsIn135Or120() {
        for(portrait in listOf(false,true)) {
            val frames=listOf(
                BorderSnapshot.authored("kodak_portra_400","test",if(portrait)960 else 1280,if(portrait)1280 else 960),
                medium("kodak_portra_400",MediumFormat.MF66,portrait))
            for(b in frames)for(placement in ImprintSettings.PLACEMENTS)for(dateEnabled in listOf(false,true)) {
                val plans=listOf(.6,1.0,1.6).flatMap{size->listOf(0.0,.5,1.0).map{glow->plan(b,settings.copy(size=size,glow=glow,datePlacement=placement,dateEnabled=dateEnabled))}}
                val edges=plans.map{it.stamps.drop(if(dateEnabled)1 else 0)}
                assertTrue(edges.all{it.size==5})
                val dimensions=edges.map{set->set.map{it.raster.width to it.raster.height}}
                assertTrue(dimensions.all{it==dimensions[0]})
                for(index in edges[0].indices)for(variant in edges.drop(1)) {
                    val reference=edges[0][index].raster;val actual=variant[index].raster
                    for(y in 0 until reference.height) {
                        val expectedRow=IntArray(reference.width);val actualRow=IntArray(actual.width)
                        reference.writeArgbRow(y,expectedRow);actual.writeArgbRow(y,actualRow)
                        assertArrayEquals("Date size/glow must not change capture-data pixels",expectedRow,actualRow)
                    }
                }
                for(p in plans){assertEquals(0,p.omittedFieldCount);bounded(p);if(b.geometryVersion==5)clearEdge(b,p,dateEnabled && placement=="photo")}
                if(dateEnabled && b.geometryVersion==5 && placement=="edge") {
                    assertTrue("Edge-date size is fixed",plans.all{it.stamps.first().raster.width==plans.first().stamps.first().raster.width})
                    val dim=plans.first().stamps.first().raster
                    val bright=plans[2].stamps.first().raster
                    var differs=false
                    for(y in 0 until dim.height) {
                        val a=IntArray(dim.width);val b=IntArray(bright.width)
                        dim.writeArgbRow(y,a);bright.writeArgbRow(y,b)
                        if(!a.contentEquals(b))differs=true
                    }
                    assertTrue("Edge-date glow remains adjustable",differs)
                } else if(dateEnabled) {
                    assertNotEquals("Photo date itself must still resize",plans.first().stamps.first().raster.coreWidth,plans.last().stamps.first().raster.coreWidth)
                }
            }
        }
    }

    @Test fun missingFieldsCenterOn135And120WithoutReservedSlots() {
        val s=settings.copy(dateEnabled=false)
        val b135=BorderSnapshot.authored("kodak_portra_400","test",1280,960)
        val full=plan(b135,s)
        for((key,value) in base.filterKeys{it!="DateTimeOriginal" && it!="MeteringMode"}) {
            val only=PhotoMetadata.fromAttributes(mapOf(key to value));val p=plan(b135,s,only)
            assertEquals("$key should create exactly one packed field",1,p.stamps.size)
            val first=p.stamps.single();val original=full.stamps[full.stamps.size/2]
            val center=first.left+first.raster.coreLeft+first.raster.coreWidth/2.0
            val originalCenter=original.left+original.raster.coreLeft+original.raster.coreWidth/2.0
            assertEquals(originalCenter,center,1.0)
            val b=medium("kodak_portra_400",MediumFormat.MF67,false)
            val mp=plan(b,s,only);assertEquals(1,mp.stamps.size);clearEdge(b,mp,false)
            val r=physical(b,mp.stamps.single())
            assertEquals(MediumImprintMarks.layout(b)!!.widthMm/2,(r[0]+r[2])/2,.51/b.pixelsPerMm)
        }
        assertTrue(plan(b135,s,PhotoMetadata.unavailable()).isEmpty)
        val b=medium("kodak_portra_400",MediumFormat.MF67,false)
        assertTrue(plan(b,s,PhotoMetadata.unavailable()).isEmpty)
    }
    @Test fun edgePreferenceFallsBackInsidePhotoWhen120FrameIsRemoved() {
        val edge=settings.copy(datePlacement="edge")
        for(portrait in listOf(false,true)) {
            val b=BorderSnapshot.authored("kodak_portra_400","test",if(portrait)960 else 1280,if(portrait)1280 else 960)
            val actual=plan(b,edge);val photo=plan(b,edge.copy(datePlacement="photo"))
            assertEquals(photo.stamps.map{it.left to it.top},actual.stamps.map{it.left to it.top})
            assertEquals(6,actual.stamps.size);bounded(actual)
        }
        val plain=ImprintPlan.create(edge,metadata(),960,1280)
        assertEquals(1,plain.stamps.size);bounded(plain)
        val b=medium("kodak_portra_400",MediumFormat.MF67,false)
        clearEdge(b,plan(b,edge),false)
        assertEquals("edge",edge.datePlacement)
    }

    @Test fun parametersShareOneHeightAndCenterBetweenPerforationsInBothOrientations() {
        for(portrait in listOf(false,true))for(count in 1..5)for(iso in listOf("100","6400","65534")) {
            val b=BorderSnapshot.authored("kodak_portra_400","test",if(portrait)960 else 1280,if(portrait)1280 else 960)
            val facts=linkedMapOf("PhotographicSensitivity" to iso,"ExposureTime" to "1/60",
                "FNumber" to "5579/1000","ExposureBiasValue" to "0","FocalLength" to "50")
            val m=PhotoMetadata.fromAttributes(facts.entries.take(count).associate{it.toPair()}+mapOf("MeteringMode" to "2"))
            val p=plan(b,settings.copy(dateEnabled=false),m)
            assertEquals(count,p.stamps.size);assertEquals(0,p.omittedFieldCount);bounded(p)
            val heights=p.stamps.map{if(portrait)it.raster.coreWidth else it.raster.coreHeight}
            assertEquals("All parameters must have one glyph height",1,heights.toSet().size)
            val centers=p.stamps.map{stamp ->
                if(portrait)(stamp.top+stamp.raster.coreTop+stamp.raster.coreHeight/2.0-b.frameOriginY)/b.pixelsPerMm
                else (stamp.left+stamp.raster.coreLeft+stamp.raster.coreWidth/2.0-b.frameOriginX)/b.pixelsPerMm
            }
            assertEquals("Parameter group must be centered",19.0,(centers.first()+centers.last())/2,.51/b.pixelsPerMm)
            centers.forEach{center -> assertEquals("Stay between perforations",kotlin.math.round(center/4.75),center/4.75,.51/b.pixelsPerMm/4.75)}
        }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun screenshotParameterValuesRenderUniformlyForVisualReview() {
        val b=BorderSnapshot.authored("kodak_portra_400","test",1280,960)
        val m=PhotoMetadata.fromAttributes(mapOf("PhotographicSensitivity" to "100","MeteringMode" to "2",
            "ExposureTime" to "1/60","FNumber" to "5579/1000","ExposureBiasValue" to "0","FocalLength" to "50"))
        val p=plan(b,settings.copy(dateEnabled=false),m)
        assertEquals(5,p.stamps.size)
        assertEquals(1,p.stamps.map{it.raster.coreHeight}.toSet().size)
        System.getProperty("filmroom.imprint.evidenceDir")?.let { directory ->
            val first=p.stamps.minOf{it.top};val last=p.stamps.maxOf{it.bottom}
            val image=android.graphics.Bitmap.createBitmap(p.width,last-first+32,android.graphics.Bitmap.Config.ARGB_8888)
            for(y in first-16 until last+16) {
                val row=FloatArray(p.width*3)
                p.blendRow(y,row,dev.filmroom.io.OutputColorSpace.SRGB)
                for(x in 0 until p.width) {
                    val rgb=(dev.filmroom.io.OutputColorSpace.SRGB.encode8(row[x*3]) shl 16) or
                        (dev.filmroom.io.OutputColorSpace.SRGB.encode8(row[x*3+1]) shl 8) or dev.filmroom.io.OutputColorSpace.SRGB.encode8(row[x*3+2])
                    image.setPixel(x,y-first+16,rgb or (0xff shl 24))
                }
            }
            val file=java.io.File(directory,"uniform-parameter-raster.png");file.parentFile.mkdirs()
            try { file.outputStream().use{check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))} } finally { image.recycle() }
        }
    }

    @Test fun tinyAndLargeOutputsStayWithinMaskBudgetWithoutAllocatingOutputImages() {
        for(size in listOf(1,16,64,192))for(style in styles) {
            val p=ImprintPlan.create(settings.copy(dateStyle=style,size=1.6),metadata(),size,size)
            bounded(p)
            assertEquals(1,p.stamps.size+p.omittedFieldCount)
        }
        // At thumbnail/minimum sizes a whole field may be omitted, never clipped or truncated.
        for(format in MediumFormat.entries)for(portrait in listOf(false,true)) {
            val b=medium("kodak_portra_400",format,portrait,192,128)
            val p=plan(b,settings.copy(datePlacement="edge"))
            bounded(p);assertEquals(6,p.stamps.size+p.omittedFieldCount)
            clearEdge(b,p,false)
        }
        for(b in listOf(BorderSnapshot.authored("kodak_portra_400","test",8192,5460),
            medium("kodak_portra_400",MediumFormat.MF645,true,8192,5460),
            medium("fujifilm_provia_100f",MediumFormat.MF617,false,8192,5460))) {
            val p=plan(b,settings.copy(datePlacement=if(b.geometryVersion==5)"edge"else "photo",size=1.6))
            assertEquals(6,p.stamps.size);bounded(p)
            if(b.geometryVersion==5)clearEdge(b,p,false)
        }
    }
}
