package dev.filmroom

import dev.filmroom.border.*
import dev.filmroom.crop.*
import dev.filmroom.io.OutputColorSpace
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class OutputRowProgressHostTest {
    private class Fixture:BorderRaster {
        override val stock="fixture_only";override val version="progress-v1"
        override val railRatio=.2;override val endRatio=.03
        override val featherTotalAt3072=0.0;override val residentBytes=12L
        override fun holeCoverage(snapshot:BorderSnapshot,x:Double,y:Double)=if(y<1)1.0 else 0.0
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(.3f)}
    }
    private fun source(file:File,w:Int,h:Int){
        val bytes=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
        repeat(w*h*3){bytes.putFloat(.1234567f)};file.writeBytes(bytes.array())
    }
    private fun assertRows(rows:List<Pair<Int,Int>>,height:Int)=assertEquals((0..height).map{it to height},rows)

    @Test fun cropReportsRowsWithoutChangingOutputAndKeepsFinalizationSeparate(){
        val dir=Files.createTempDirectory("crop-progress").toFile()
        try {
            val input=File(dir,"input");source(input,12,8)
            val snapshot=CropSnapshot.create(12,8);val expected=File(dir,"expected")
            CropCompositor.composite(input,expected,snapshot)
            val out=File(dir,"out");val rows=mutableListOf<Pair<Int,Int>>()
            CropCompositor.composite(input,out,snapshot,onProgress={done,total->
                rows+=done to total
                // Even the last row callback is not a claim that the atomic move is complete.
                assertFalse(out.exists())
            })
            assertRows(rows,8);assertArrayEquals(expected.readBytes(),out.readBytes())
        }finally{dir.deleteRecursively()}
    }
    @Test fun borderPreImagingAndHoleRestorationEachReportExactWrittenRows(){
        val dir=Files.createTempDirectory("border-progress").toFile()
        try {
            val input=File(dir,"input");source(input,24,16)
            val snapshot=BorderSnapshot.create("fixture_only","progress-v1",24,16,.2,.03,0.0)
            val raster=Fixture();val rows=mutableListOf<Pair<Int,Int>>()
            BorderCompositor.composite(input,File(dir,"border"),snapshot,raster,OutputColorSpace.SRGB,onProgress={d,t->rows+=d to t})
            assertRows(rows,snapshot.outputHeight);rows.clear()
            val pre=File(dir,"pre")
            PreImagingInput.file(input,pre,snapshot,raster,onProgress={d,t->rows+=d to t})
            assertRows(rows,snapshot.outputHeight);rows.clear()
            PreImagingInput.restoreHoles(pre,File(dir,"restored"),snapshot,raster,onProgress={d,t->rows+=d to t})
            assertRows(rows,snapshot.outputHeight)
        }finally{dir.deleteRecursively()}
    }
    @Test fun cancellationFromProgressPreservesCommittedDestinationAndStopsCounting(){
        val dir=Files.createTempDirectory("row-progress-cancel").toFile()
        try {
            val input=File(dir,"input");source(input,12,8)
            val out=File(dir,"out");out.writeBytes(byteArrayOf(4,5,6));val rows=mutableListOf<Pair<Int,Int>>()
            assertThrows(java.util.concurrent.CancellationException::class.java){
                CropCompositor.composite(input,out,CropSnapshot.create(12,8),onProgress={d,t->
                    rows+=d to t;if(d==3)throw java.util.concurrent.CancellationException()
                })
            }
            assertEquals((0..3).map{it to 8},rows)
            assertArrayEquals(byteArrayOf(4,5,6),out.readBytes());assertFalse(File(dir,"out.part").exists())
        }finally{dir.deleteRecursively()}
    }
    @Test fun failedRowWriteIsNotCounted(){
        val snapshot=CropSnapshot.create(12,8);val map=CropCompositor.map(snapshot)
        val rows=mutableListOf<Pair<Int,Int>>()
        assertThrows(java.io.IOException::class.java){
            FinalImageRows.walk(12,12,8,map,{},read={_,row->row.fill(.1f)},
                compose={_,a,_,_,row->a.copyInto(row)},write={y,_->if(y==2)throw java.io.IOException("test")},
                onProgress={d,t->rows+=d to t})
        }
        assertEquals((0..2).map{it to 8},rows)
    }
}
