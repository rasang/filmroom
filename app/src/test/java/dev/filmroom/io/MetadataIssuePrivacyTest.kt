package dev.filmroom.io

import dev.filmroom.diagnostics.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class MetadataIssuePrivacyTest {
    @Test fun rejectedValuesNeverEnterSafeDiagnosticsAndCodesSurviveSnapshot() {
        val secrets=listOf("/private/photos/person.jpg","SECRET_SERIAL_123","GPS_PRIVATE_COORDINATE","PRIVATE_COMMENT")
        val metadata=PhotoMetadata.fromAttributes(mapOf(
            "Make" to "Valid camera value", "BodySerialNumber" to secrets[1]+"\u0000",
            "GPSLatitude" to secrets[2], "UserComment" to secrets[3].repeat(500),
            "DateTimeOriginal" to secrets[0], secrets[0] to secrets[1], "ExposureTime" to "NaN"))
        val durable=PhotoMetadata.fromJson(metadata.toJson())
        assertEquals(metadata.issues,durable.issues)
        val safe=SafeInfo(metadataIssues=durable.issues,metadataPhase=MetadataIssuePhase.READ_VALIDATION)
        val text=JSONObject(safe.typedFields()).toString()
        for(secret in secrets+"Valid camera value")assertFalse(text,text.contains(secret))
        assertTrue(text.contains("BodySerialNumber:INVALID_TEXT"));assertTrue(text.contains("GPSLatitude:INVALID_NUMERIC"))
        assertTrue(text.contains("UserComment:FIELD_TOO_LONG"));assertTrue(text.contains("DateTimeOriginal:INVALID_DATE"))
        assertTrue(text.contains("UNKNOWN:UNKNOWN_FIELD"));assertTrue(text.contains("ExposureTime:INVALID_NUMERIC"))
        assertEquals(safe.typedFields(),SafeInfo.parse(safe.fields()).typedFields())
    }
    @Test fun untrustedPersistedCodesAreAllowlistedBoundedAndCannotSmuggleValues() {
        val bad="Make:/private/secret,NotAField:READ_FAILED,Model:INVALID_TEXT;path=/private/secret,UserComment:FIELD_TOO_LONG"
        val safe=SafeInfo.parse(mapOf("metadataIssues" to bad,"metadataPhase" to "/private/secret"))
        assertEquals("UserComment:FIELD_TOO_LONG",safe.fields()["metadataIssues"])
        assertFalse(safe.fields().containsKey("metadataPhase"))
        assertTrue(MetadataIssues.parse("x".repeat(2049)).isEmpty())
        val all=MetadataField.entries.map{MetadataIssue(it,MetadataIssueReason.INVALID_TEXT)}
        val encoded=MetadataIssues.encode(all)
        assertEquals(MetadataIssues.MAX_ITEMS,MetadataIssues.parse(encoded).size)
        assertTrue(encoded.length<=MetadataIssues.MAX_TEXT)
        assertFalse(encoded.contains(';'));assertFalse(encoded.contains('='))
    }
    @Test fun noProblemFieldsDoNotCreateDiagnosticEventsOrLeakValues() {
        val metadata=PhotoMetadata.fromAttributes(mapOf("Make" to "PRIVATE CAMERA","DateTimeOriginal" to "2020:01:02 03:04:05"))
        assertTrue(metadata.issues.isEmpty())
        assertTrue(SafeInfo(metadataIssues=metadata.issues,metadataPhase=MetadataIssuePhase.READ_VALIDATION).fields().isEmpty())
        val legacy=PhotoMetadata.fromJson("""{"version":1,"attributes":{"Make":"Valid"}}""")
        assertTrue(legacy.issues.isEmpty())
    }
    @Test fun productionMetadataWriteFailuresReportOnlySafePhaseAndKeepImageBytes() {
        val app=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val repository=PhotoRepository(app)
        val method=PhotoRepository::class.java.declaredMethods.single{it.name=="applyMetadata"}.apply{isAccessible=true}
        val file=java.io.File.createTempFile("private-photo-path-", ".jpg")
        val original=byteArrayOf(1,2,3,4)
        val metadata=PhotoMetadata.fromAttributes(mapOf("BodySerialNumber" to "PRIVATE_SERIAL_VALUE"))
        val notices=mutableListOf<Pair<MetadataIssuePhase,MetadataIssue>>()
        val warn:(String)->Unit={}
        val issue:(MetadataIssuePhase,MetadataIssue)->Unit={phase,value->notices+=phase to value}
        try {
            file.writeBytes(original)
            method.invoke(repository,file,ExportFormat.JPEG,metadata,0,3,"sRGB",0L,warn,issue)
            assertEquals(MetadataIssuePhase.WRITE_VALIDATE,notices.single().first)
            assertEquals(MetadataIssueReason.OUTPUT_VALIDATION_FAILED,notices.single().second.reason)
            notices.clear()
            method.invoke(repository,file,ExportFormat.JPEG,metadata,2,3,"sRGB",0L,warn,issue)
            assertEquals(MetadataIssuePhase.WRITE_EXCEPTION,notices.single().first)
            assertEquals(MetadataIssueReason.CONTAINER_WRITE_FAILED,notices.single().second.reason)
            assertArrayEquals(original,file.readBytes())
            val text=SafeInfo(metadataIssues=notices.map{it.second},metadataPhase=notices.single().first).fields().toString()
            assertFalse(text.contains(file.path));assertFalse(text.contains("PRIVATE_SERIAL_VALUE"))
        } finally {file.delete()}
    }
    @Test fun diskDiagnosticRecordKeepsPhaseAndRemainsNonfatalAndBounded() {
        for(phase in MetadataIssuePhase.entries) {
            val info=SafeInfo(metadataPhase=phase,metadataIssues=MetadataField.entries.map{MetadataIssue(it,MetadataIssueReason.INVALID_TEXT)})
            val event=DiagnosticEvent(1000,1,"a".repeat(32),1,1,Operation.EXPORT_ENCODE,Stage.ENCODE_PROGRESS,info=info)
            val line=event.line();assertTrue(line.toByteArray().size<4096)
            val restored=checkNotNull(DiagnosticEvent.parse(line))
            assertEquals(phase,restored.info.metadataPhase);assertNull(restored.error);assertNull(restored.outcome)
            assertEquals(MetadataIssues.MAX_ITEMS,restored.info.metadataIssues.size)
            assertEquals(MetadataIssueReason.ISSUE_LIMIT,restored.info.metadataIssues.last().reason)
        }
    }
    @Test fun assumedDateDiagnosticContainsOnlyFixedProvenanceNeverZoneOrDateValue() {
        val metadata=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2016:01:08 15:08:01"))
        val info=SafeInfo(captureTimeBasis=metadata.diagnosticDateBasis)
        assertEquals(mapOf("captureTimeBasis" to "ASSUMED_IMPORT_ZONE"),info.fields())
        assertEquals(info.fields(),SafeInfo.parse(info.fields()).fields())
        assertTrue(SafeInfo.parse(mapOf("captureTimeBasis" to "Asia/Private_Zone")).fields().isEmpty())
        val known=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2016:01:08 15:08:01","OffsetTimeOriginal" to "+09:00"))
        assertNull(known.diagnosticDateBasis)
    }
    @Test fun snapshotBudgetRemainsUnchangedAndBudgetOmissionHasItsOwnCode() {
        val names=listOf("Make","Model","Artist","Copyright","CameraOwnerName","BodySerialNumber","LensMake","LensModel","LensSerialNumber","ImageUniqueID","ImageDescription","UserComment","GPSMapDatum","GPSSatellites")
        val metadata=PhotoMetadata.fromAttributes(names.associateWith{"x".repeat(4000)})
        assertEquals(48*1024,PhotoMetadata.BUDGET)
        assertTrue(metadata.issues.any{it.reason==MetadataIssueReason.SNAPSHOT_BUDGET})
        assertTrue(metadata.toJson().toByteArray().size<=PhotoMetadata.BUDGET)
        assertTrue(metadata.exifBytes(2,3,"sRGB",0).size<=PhotoMetadata.BUDGET)
    }
}
