package dev.filmroom

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.codec.*
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.pow

/** Explicitly opt-in FIXED135 post-film crop/resize stress, separate from historical uncropped test. No 50 MP film simulation, no device or production asset claim.
 * Source floats are row-generated. Real compositor -> real native encoder -> real metadata writer
 * -> streamed publication copy; TIFF strips and JPEG source regions are independently read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class Fixed135LargeExportStressHostTest {
    private fun rssKb():Long=File("/proc/self/status").readLines().first{it.startsWith("VmRSS:")}.filter{it.isDigit()}.toLong()
    private fun gray(x:Int,y:Int)=.12f+((x/64+y/64)%5)*.08f
    @Test fun real80to84mpPostFilmChainPreservesSamplesMetadataAndBoundedWork()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_FIXED135_STRESS")=="1")
        assertTrue(NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        val stressRoot=System.getenv("FILMROOM_FIXED135_STRESS_ROOT")?.let{File(it).canonicalFile.apply{check(mkdirs() || isDirectory)}} ?: app.cacheDir
        val dir=java.nio.file.Files.createTempDirectory(stressRoot.toPath(),"fixed135-large-authored-").toFile()
        check(dir.usableSpace>=4L*1024*1024*1024) { "Stress requires >=4 GiB available before source generation" }
        val report=File(checkNotNull(System.getenv("FILMROOM_FIXED135_STRESS_REPORT")))
        report.writeText("orientation,space,format,sourceWidth,sourceHeight,outputWidth,outputHeight,outputPixels,compositeMs,compositePeakRssKiB,encodeMs,metadataPublishMs,baselineRssKiB,peakRssKiB,peakTempLogicalBytes,knownConservativeTempBytes\n")
        val peakRss=java.util.concurrent.atomic.AtomicLong(rssKb());val peakDisk=java.util.concurrent.atomic.AtomicLong(0L)
        val running=AtomicBoolean(true)
        val monitor=thread(name="fixed135-stress-monitor"){
            while(running.get()){
                peakRss.accumulateAndGet(rssKb(),::maxOf)
                peakDisk.accumulateAndGet(dir.walkTopDown().filter{it.isFile}.sumOf{it.length()},::maxOf)
                Thread.sleep(10)
            }
        }
        val codec=PhotoCodecs(stressRoot,memorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)})
        val metadata=PhotoMetadata.fromAttributes(mapOf("Make" to "Authored stress", "DateTimeOriginal" to "2001:02:03 04:05:06","OffsetTimeOriginal" to "+09:00","PhotographicSensitivity" to "400"))
        try {
            for((orientation,size) in listOf("landscape135" to (8829 to 5886),"portrait135" to (5886 to 8829))) {
                check(dir.usableSpace>=4L*1024*1024*1024)
                val (w,h)=size
                val source=File(dir,"post-film.rgb")
                source.outputStream().buffered(128*1024).use{out->
                    val row=ByteBuffer.allocate(w*12).order(ByteOrder.LITTLE_ENDIAN)
                    for(y in 0 until h){row.clear();for(x in 0 until w)repeat(3){row.putFloat(gray(x,y))};out.write(row.array())}
                }
                val sourceHash=sha256(source)
                val asset=object:BorderRaster {
                    override val stock="fixture_only";override val version="authored-fixed135-large-v2"
                    override val railRatio=.23;override val endRatio=.04;override val featherTotalAt3072=4.0;override val residentBytes=12L
                    override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb[0]=.8f;rgb[1]=.3f;rgb[2]=.1f}
                }
                val crop=dev.filmroom.model.CompositionTransform(.6,.4,1.5).clamped(w,h)
                val s=BorderSnapshot.fixed135(asset.stock,asset.version,w,h,w,h,crop,4.0)
                assertEquals(51_967_494L,w.toLong()*h)
                assertEquals(if(w>h)9320 else 8584,s.outputWidth)
                assertEquals(if(w>h)8584 else 9320,s.outputHeight)
                assertEquals(80_002_880L,s.outputWidth.toLong()*s.outputHeight)
                assertTrue(s.outputWidth.toLong()*s.outputHeight in 80_000_000L..84_000_000L)
                for(space in OutputColorSpace.entries) {
                    val framed=File(dir,"framed.rgb")
                    val compositeStart=System.nanoTime();peakRss.set(rssKb())
                    BorderCompositor.composite(source,framed,s,asset,space)
                    val compositeMs=(System.nanoTime()-compositeStart)/1_000_000;val compositePeak=peakRss.get()
                    // Independent analytic bilinear crop oracle, not same-coordinate source copying.
                    RandomAccessFile(framed,"r").use{f->
                        for((x,y) in listOf(aperturePoint(s,.5,.5),aperturePoint(s,.25,.75),aperturePoint(s,.8,.2),bilinearBoundaryPoint(s))) {
                            f.seek((y.toLong()*s.outputWidth+x)*12)
                            repeat(3){assertEquals(expectedGray(s,x,y),Float.fromBits(Integer.reverseBytes(f.readInt())),1e-7f)}
                        }
                    }
                    for(format in listOf(ExportFormat.JPEG,ExportFormat.TIFF16)) {
                        val encoded=File(dir,"encoded.${format.extension}");val published=File(dir,"published.${format.extension}")
                        val baseline=rssKb();peakRss.set(baseline);peakDisk.set(source.length()+framed.length())
                        val start=System.nanoTime()
                        codec.encode(framed,encoded,s.outputWidth,s.outputHeight,if(format==ExportFormat.JPEG)"JPEG" else "TIFF16",95,space.id)
                        val encodedAt=System.nanoTime();val encodedBytes=encoded.length()
                        PhotoMetadataContainer.inject(encoded,format,metadata.exifBytes(s.outputWidth,s.outputHeight,space.id,1793444462987L),1793444462987L)
                        encoded.inputStream().buffered().use{input->published.outputStream().buffered().use{input.copyTo(it,128*1024)}}
                        val publishedAt=System.nanoTime()
                        if(format==ExportFormat.TIFF16)verifyTiff(published,s,space) else {
                            val exif=androidx.exifinterface.media.ExifInterface(published.absolutePath)
                            assertEquals(s.outputWidth,exif.getAttributeInt("PixelXDimension",0));assertEquals(s.outputHeight,exif.getAttributeInt("PixelYDimension",0))
                            assertEquals("2001:02:03 04:05:06",exif.getAttribute("DateTimeOriginal"));assertEquals(400,exif.getAttributeInt("PhotographicSensitivity",0))
                            verifyJpeg(published,s,space)
                        }
                        val conservative=source.length()+framed.length()+2*maxOf(encodedBytes,encoded.length())+128*1024
                        report.appendText("$orientation,${space.id},${format.name},$w,$h,${s.outputWidth},${s.outputHeight},${s.outputWidth.toLong()*s.outputHeight},$compositeMs,$compositePeak,${(encodedAt-start)/1_000_000},${(publishedAt-encodedAt)/1_000_000},$baseline,${peakRss.get()},${peakDisk.get()},$conservative\n")
                        encoded.delete();published.delete()
                    }
                    framed.delete()
                }
                assertArrayEquals(sourceHash,sha256(source))
                source.delete()
            }
        } finally {running.set(false);monitor.join();dir.deleteRecursively()}
    }
    private fun aperturePoint(s:BorderSnapshot,x:Double,y:Double)=
        (s.apertureLeft+s.photoWidth*x).toInt() to (s.apertureTop+s.photoHeight*y).toInt()
    /** Independent pixel-center affine oracle. These sources already have exactly the 135 aspect. */
    private fun sourcePoint(s:BorderSnapshot,x:Int,y:Int):Pair<Double,Double> {
        val zoom=s.composition.zoom
        val u=s.composition.centerX+((x+.5-s.apertureLeft)/s.photoWidth-.5)/zoom
        val v=s.composition.centerY+((y+.5-s.apertureTop)/s.photoHeight-.5)/zoom
        return (u*s.sourceWidth-.5).coerceIn(0.0,s.sourceWidth-1.0) to
            (v*s.sourceHeight-.5).coerceIn(0.0,s.sourceHeight-1.0)
    }
    private fun expectedGray(s:BorderSnapshot,x:Int,y:Int):Float {
        val(sx,sy)=sourcePoint(s,x,y);val ix=kotlin.math.floor(sx).toInt();val iy=kotlin.math.floor(sy).toInt()
        val fx=sx-ix;val fy=sy-iy;val nx=minOf(ix+1,s.sourceWidth-1);val ny=minOf(iy+1,s.sourceHeight-1)
        return ((gray(ix,iy)*(1-fx)+gray(nx,iy)*fx)*(1-fy)+(gray(ix,ny)*(1-fx)+gray(nx,ny)*fx)*fy).toFloat()
    }
    private fun bilinearBoundaryPoint(s:BorderSnapshot):Pair<Int,Int> {
        val(cx,cy)=aperturePoint(s,.5,.5)
        for(dx in 0..192){
            val(sx,sy)=sourcePoint(s,cx+dx,cy)
            val ix=kotlin.math.floor(sx).toInt();val iy=kotlin.math.floor(sy).toInt()
            if(ix%64==63 && sx-ix in .1.. .9 && gray(ix,iy)!=gray(ix+1,iy))return cx+dx to cy
        }
        error("No bilinear block-boundary validation sample")
    }
    /** Keep JPEG's 8×8 region and nearby DCT support well inside a flat source block. */
    private fun flatJpegRegion(s:BorderSnapshot):Pair<Int,Int> {
        val(cx,cy)=aperturePoint(s,.5,.5)
        for(dy in 0..192 step 8)for(dx in 0..192 step 8){
            val(x,y)=sourcePoint(s,cx+dx,cy+dy)
            if(x%64 in 18.0..32.0 && y%64 in 18.0..32.0)return cx+dx to cy+dy
        }
        error("No independently flat JPEG validation region")
    }
    private fun sha256(file:File):ByteArray {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use{input->val b=ByteArray(128*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        return digest.digest()
    }
    private fun verifyTiff(file:File,s:BorderSnapshot,space:OutputColorSpace) {
        RandomAccessFile(file,"r").use{f->
            fun u16()=java.lang.Short.reverseBytes(f.readShort()).toInt()and 65535
            fun u32()=Integer.reverseBytes(f.readInt()).toLong()and 0xffffffffL
            assertEquals(0x4949,f.readUnsignedShort());assertEquals(42,u16());val root=u32();f.seek(root)
            val entries=mutableMapOf<Int,Triple<Int,Long,Long>>()
            repeat(u16()){val tag=u16();val type=u16();val count=u32();val value=u32();entries[tag]=Triple(type,count,value)}
            assertEquals(s.outputWidth.toLong(),entries.getValue(256).third);assertEquals(s.outputHeight.toLong(),entries.getValue(257).third)
            val bits=entries.getValue(258);assertEquals(3L,bits.second);f.seek(bits.third);repeat(3){assertEquals(16,u16())}
            val sampleFormat=entries[339]
            if(sampleFormat!=null){
                if(sampleFormat.second==1L)assertEquals(1L,sampleFormat.third)
                else {f.seek(sampleFormat.third);repeat(sampleFormat.second.toInt()){assertEquals(1,u16())}}
            } // TIFF default SampleFormat is unsigned integer when absent, never floating point.
            val icc=entries.getValue(34675);f.seek(icc.third);val profile=ByteArray(icc.second.toInt());f.readFully(profile);space.validateProfile(profile)
            val rows=entries.getValue(278).third.toInt();val offsets=entries.getValue(273)
            val (x,y)=aperturePoint(s,.5,.5)
            val strip=y/rows
            val offset=if(offsets.second==1L)offsets.third else {f.seek(offsets.third+strip*4L);u32()}
            f.seek(offset+((y%rows).toLong()*s.outputWidth+x)*6)
            val v=expectedGray(s,x,y).toDouble()
            val expected=kotlin.math.round((if(space==OutputColorSpace.ADOBE_RGB)v.pow(256.0/563.0)else 1.055*v.pow(1.0/2.4)-.055)*65535).toInt()
            repeat(3){assertTrue("TIFF16 integer crop sample",kotlin.math.abs(expected-u16())<=1)}
            assertEquals(1L,entries.getValue(274).third)
            f.seek(entries.getValue(34665).third)
            val capture=mutableMapOf<Int,Triple<Int,Long,Long>>()
            repeat(u16()){val tag=u16();val type=u16();val count=u32();val value=u32();capture[tag]=Triple(type,count,value)}
            assertEquals(s.outputWidth.toLong(),capture.getValue(40962).third)
            assertEquals(s.outputHeight.toLong(),capture.getValue(40963).third)
            assertEquals(400L,capture.getValue(34855).third)
            val date=capture.getValue(36867);assertEquals(20L,date.second);f.seek(date.third)
            val text=ByteArray(20);f.readFully(text);assertEquals("2001:02:03 04:05:06\u0000",String(text,Charsets.US_ASCII))
            val offsetTag=capture.getValue(36881);f.seek(offsetTag.third);val offsetText=ByteArray(offsetTag.second.toInt());f.readFully(offsetText)
            assertEquals("+09:00\u0000",String(offsetText,Charsets.US_ASCII))
        }
    }
    private fun verifyJpeg(file:File,s:BorderSnapshot,space:OutputColorSpace) {
        val profile=ByteArrayOutputStream()
        DataInputStream(file.inputStream().buffered()).use{input->
            assertEquals(0xffd8,input.readUnsignedShort())
            while(true){
                assertEquals(255,input.readUnsignedByte());val marker=input.readUnsignedByte()
                if(marker==0xda || marker==0xd9)break
                val length=input.readUnsignedShort();val segment=ByteArray(length-2);input.readFully(segment)
                if(marker==0xe2 && segment.size>=14 && String(segment,0,12,Charsets.ISO_8859_1)=="ICC_PROFILE\u0000")profile.write(segment,14,segment.size-14)
            }
        }
        space.validateProfile(profile.toByteArray())
        // ImageIO source-region decode requests only a small raster, not an 84 MP Bitmap.
        val io=Class.forName("javax.imageio.ImageIO")
        val stream=io.getMethod("createImageInputStream",Any::class.java).invoke(null,file)
        val iterator=io.getMethod("getImageReaders",Any::class.java).invoke(null,stream) as Iterator<*>
        val reader=iterator.next()!!;val readerClass=Class.forName("javax.imageio.ImageReader")
        try {
            readerClass.getMethod("setInput",Any::class.java).invoke(reader,stream)
            assertEquals(s.outputWidth,readerClass.getMethod("getWidth",Int::class.javaPrimitiveType).invoke(reader,0))
            assertEquals(s.outputHeight,readerClass.getMethod("getHeight",Int::class.javaPrimitiveType).invoke(reader,0))
            val param=readerClass.getMethod("getDefaultReadParam").invoke(reader)
            val (x,y)=flatJpegRegion(s)
            val rect=Class.forName("java.awt.Rectangle").getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).newInstance(x,y,8,8)
            Class.forName("javax.imageio.IIOParam").getMethod("setSourceRegion",Class.forName("java.awt.Rectangle")).invoke(param,rect)
            val image=readerClass.getMethod("read",Int::class.javaPrimitiveType,Class.forName("javax.imageio.ImageReadParam")).invoke(reader,0,param)
            assertEquals(8,image.javaClass.getMethod("getWidth").invoke(image))
            val rgb=image.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(image,3,3) as Int
            val expected=OutputColorSpace.SRGB.encode8(expectedGray(s,x+3,y+3))
            for(shift in listOf(0,8,16))assertTrue("JPEG managed neutral ${space.id}: $rgb / $expected",kotlin.math.abs(((rgb ushr shift)and 255)-expected)<=4)
        }finally{readerClass.getMethod("dispose").invoke(reader);(stream as java.io.Closeable).close()}
    }
}
