package dev.filmroom.io

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PngColorProfileHostTest {
    private fun profile(space:OutputColorSpace)=ApplicationProvider.getApplicationContext<Application>().assets.open("color-profiles/${space.iccAsset}").use{it.readBytes()}
    private fun linear()=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(.18f).putFloat(.4f).putFloat(.7f).array()
    @Test fun nonSrgbPngEmbedsExactIccAndDoesNotClaimSrgb() {
        for(space in listOf(OutputColorSpace.DISPLAY_P3,OutputColorSpace.ADOBE_RGB)) {
            val icc=profile(space);val out=ByteArrayOutputStream()
            LinearRgbPng.encode(ByteArrayInputStream(linear()),1,1,out,colorSpace=space,iccProfile=icc)
            val stream=DataInputStream(ByteArrayInputStream(out.toByteArray()));stream.skipBytes(8)
            val names=mutableListOf<String>();val idat=ByteArrayOutputStream();var found=false
            while(stream.available()>0){
                val n=stream.readInt();val type=ByteArray(4).also{stream.readFully(it)};val data=ByteArray(n).also{stream.readFully(it)};val crc=stream.readInt()
                assertEquals(crc,CRC32().apply{update(type);update(data)}.value.toInt())
                val name=type.toString(Charsets.US_ASCII);names+=name
                if(name=="iCCP"){
                    val nul=data.indexOf(0);assertEquals(space.id,String(data,0,nul,Charsets.US_ASCII));assertEquals(0,data[nul+1].toInt())
                    val actual=InflaterInputStream(ByteArrayInputStream(data,nul+2,data.size-nul-2)).readBytes();assertArrayEquals(icc,actual);found=true
                }
                if(name=="IDAT")idat.write(data)
            }
            assertTrue(found);assertFalse("sRGB" in names);assertFalse("gAMA" in names)
            val pixels=InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).readBytes()
            assertEquals(0,pixels[0].toInt())
            val expected=if(space==OutputColorSpace.ADOBE_RGB)intArrayOf(117,168,217) else intArrayOf(118,170,218)
            for(i in 0..2)assertEquals(expected[i],pixels[i+1].toInt() and 255)
        }
    }
    @Test fun mismatchedOrAbsentProfileFailsRatherThanMislabelsPixels() {
        for(icc in listOf<ByteArray?>(null,profile(OutputColorSpace.ADOBE_RGB))) {
            assertThrows(IOException::class.java){LinearRgbPng.encode(ByteArrayInputStream(linear()),1,1,ByteArrayOutputStream(),colorSpace=OutputColorSpace.DISPLAY_P3,iccProfile=icc)}
        }
        assertThrows(IOException::class.java){OutputColorSpace.parse("Rec.2020")}
    }
    @Test fun bundledProfilesArePinnedAndDifferent() {
        for(space in OutputColorSpace.entries)space.validateProfile(profile(space))
        assertFalse(profile(OutputColorSpace.DISPLAY_P3).contentEquals(profile(OutputColorSpace.SRGB)))
    }
}
