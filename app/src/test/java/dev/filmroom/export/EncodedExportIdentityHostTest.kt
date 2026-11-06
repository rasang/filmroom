package dev.filmroom.export

import android.app.Application
import dev.filmroom.io.*
import dev.filmroom.model.*
import dev.filmroom.imprint.ImprintSettings
import dev.filmroom.crop.CropSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class EncodedExportIdentityHostTest {
    private val metadata=PhotoMetadata.fromAttributes(mapOf("Make" to "Camera","DateTimeOriginal" to "2001:02:03 04:05:06","OffsetTimeOriginal" to "+01:00"))
    private fun job(p:EditorState=EditorState(),w:Int=32,h:Int=24)=ExportJob(ExportJobStore.newId(),1,p,w,h,ExportFormat.JPEG,95,crop=CropSnapshot.create(w,h,settings=p.crop),photoMetadata=metadata.toJson())
    private fun key(j:ExportJob=job(),source:String="source-sha",science:String="science+budget+data+backend",m:PhotoMetadata=metadata)=EncodedExportIdentity.key(j,source,science,m)
    @Test fun allSerializedNumericAndBooleanScientificControlsInvalidate() {
        val base=key();var checked=0
        for(field in EditorState::class.java.declaredFields) {
            if(field.name in setOf("filmBorder","mediumPortrait"))continue
            val state=EditorState();field.isAccessible=true
            when(field.type) {
                java.lang.Float.TYPE->field.setFloat(state,field.getFloat(state)+0.125f)
                java.lang.Integer.TYPE->field.setInt(state,field.getInt(state)+1)
                java.lang.Boolean.TYPE->field.setBoolean(state,!field.getBoolean(state))
                else->continue
            }
            if(state.json("export")!=EditorState().json("export")){assertNotEquals(field.name,base,key(job(state)));checked++}
        }
        assertTrue("Exercise the complete serialized control family",checked>=60)
    }
    @Test fun sourceOrientationMappingDimensionsFormatQualityIccAndRuntimeInvalidate() {
        val base=key();listOf(
            key(source="other-content-sha"),key(science="other-backend-or-budget-or-data-or-algorithm"),
            key(job(w=24,h=32)),key(job().copy(sourceWidth=64,sourceHeight=48,crop=CropSnapshot.create(32,24,64,48))),
            key(job().copy(inputKind="ENCODED")),key(job().copy(maxEdge=32)),
            key(job().copy(quality=94)),key(job().copy(format=ExportFormat.TIFF16)),key(job().copy(format=ExportFormat.PNG)),key(job(EditorState(outputColorSpace="Display P3"))),
            key(job(EditorState(filmStock="kodak_doublex"))),key(job(EditorState(paperStock="kodak_2302"))),
            key(job(EditorState(outputRoute="direct"))),key(job(EditorState(diffusionFilter="black_mist")))
        ).forEach{assertNotEquals(base,it)}
        assertTrue(EncodedExportIdentity.supported(job().copy(format=ExportFormat.PNG)))
    }
    @Test fun metadataGpsCaptureDateResolvedFallbackAndImprintAreIsolated() {
        val base=key()
        val gps=PhotoMetadata.fromAttributes(mapOf("GPSLatitude" to "1/1,2/1,3/1","GPSLatitudeRef" to "N"))
        assertNotEquals(base,key(m=gps));assertNotEquals(base,key(m=metadata.withImprintFallback("2010:01:02 03:04:05","modified")))
        val imprint=ImprintSettings(dateStyle="contax_t2",dateEnabled=true,manualDateTime="2020-01-02 03:04:05")
        val a=job(EditorState(imprint=imprint));val first=key(a)
        listOf(imprint.copy(dateStyle="yashica_t4"),imprint.copy(manualDateTime="2021-01-02 03:04:05"),imprint.copy(size=1.2),imprint.copy(glow=0.5),imprint.copy(corner="top_left"),imprint.copy(datePlacement="edge"),imprint.copy(exposure=true),imprint.copy(dateEnabled=false)).forEach{assertNotEquals(first,key(job(EditorState(imprint=it))))}
        val crop=CropSettings(CropMode.SQUARE)
        assertNotEquals(base,key(job(EditorState(crop=crop))))
    }
    @Test fun frozenBorderVersionGeometryAppearanceAndCompositionInvalidate() {
        val state=EditorState(filmBorder=true)
        val border=dev.filmroom.border.BorderSnapshot("kodak_portra_400","asset-v1",photoWidth=32,photoHeight=24,rail=1,end=1)
        val job=ExportJob(ExportJobStore.newId(),1,state,32,24,ExportFormat.JPEG,95,border=border)
        val base=key(job)
        assertNotEquals(base,key(job.copy(border=border.copy(assetVersion="asset-v2"))))
        assertNotEquals(base,key(job.copy(border=border.copy(featherTotalAt3072=6.0))))
        val composition=state.copy(composition=CompositionTransform(0.45,0.5,1.2))
        assertNotEquals(base,key(job.copy(parameters=composition)))
        assertNotEquals(base,key(job.copy(parameters=state.copy(negativeWhiteHoles=false))))
    }
    @Test fun perJobOutputNameAndFreshExportTimeDoNotChangePreMetadataPayloadKey() {
        val original=job();assertEquals(key(original),key(original.copy(id=ExportJobStore.newId(),createdAt=99,exportMillis=987654321,outputName="new-name")))
        assertFalse(metadata.exifBytes(32,24,"sRGB",1000).contentEquals(metadata.exifBytes(32,24,"sRGB",1001)))
    }
    @Test fun completeNativeProofIsRequiredButLegacyFakeSuccessIsOnlyACacheMiss() {
        val f=File.createTempFile("cache-proof", ".jpg").apply{writeBytes(byteArrayOf(1,2,3))}
        try {
            val report=JSONObject().put("ok",true).put("schemaVersion",1).put("operation","encode").put("stage","complete").put("outputValid",true).put("format","JPEG").put("width",32).put("height",24).put("colorSpace","sRGB").put("bitDepth",8).put("outputOrientation",1).put("outputBytes",3)
            assertTrue(EncodedPayloadProof.valid(report,f,32,24,ExportFormat.JPEG,"sRGB"))
            assertFalse(EncodedPayloadProof.valid(JSONObject("{\"ok\":true}"),f,32,24,ExportFormat.JPEG,"sRGB"))
            for(name in report.keys().asSequence().filter{it!="ok"}.toList()) {
                val changed=JSONObject(report.toString());changed.remove(name)
                assertFalse(name,EncodedPayloadProof.valid(changed,f,32,24,ExportFormat.JPEG,"sRGB"))
            }
            f.appendText("corruption");assertFalse(EncodedPayloadProof.valid(report,f,32,24,ExportFormat.JPEG,"sRGB"))
        }finally{f.delete()}
    }
}
