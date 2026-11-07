package dev.filmroom.io

import dev.filmroom.diagnostics.*
import dev.filmroom.io.formats.PhotoFormat
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ImportDiagnosticPrivacyTest {
    @Test fun causeCategoriesAreBoundedAndDoNotExposeMessages() {
        assertEquals(ImportFailure.PERMISSION_DENIED, classifyImportCause(SecurityException("content://secret/private.ARW")))
        assertEquals(ImportFailure.FILE_NOT_FOUND, classifyImportCause(FileNotFoundException("secret")))
        assertEquals(ImportFailure.IO, classifyImportCause(IOException("secret")))
        assertEquals(ImportFailure.MEMORY_ALLOCATION, classifyImportCause(OutOfMemoryError("secret")))
        assertEquals(ImportFailure.MEMORY_ALLOCATION, classifyImportCause(IOException("secret",OutOfMemoryError("secret"))))
        assertEquals(ImportFailure.OTHER, classifyImportCause(null))
        val cycleA = RuntimeException("secret-a")
        val cycleB = RuntimeException("secret-b", cycleA)
        cycleA.initCause(cycleB)
        assertEquals(ImportFailure.OTHER, classifyImportCause(cycleA))
        val nested = PhotoException(PhotoException.Reason.UNREADABLE,"secret",IOException("secret"))
        assertEquals(ImportFailure.IO,classifyImportCause(nested))
    }
    @Test fun nativeSubcodeRemainsDistinctFromProviderIo() {
        val native = PhotoException(PhotoException.Reason.UNREADABLE,"secret",
            codecCode=CodecErrorCode.NATIVE_HANDLE_CREATE_FAILED,codecStage=CodecStage.NATIVE_CREATE)
        assertEquals(ImportFailure.CODEC_REPORTED,classifyImportCause(native))
    }
    @Test fun safeInfoRoundTripsAllowedFieldsWithNumericByteCount() {
        val value = ImportDiagnosticInfo(ImportOrigin.FILES_PICKER,ImportPhase.SOURCE_READ,65536,
            PhotoFormat.TIFF,ImportRoute.RAW,ImportFailure.IO)
        val safe = SafeInfo(importDiagnostic=value)
        assertEquals(value,SafeInfo.parse(safe.fields()).importDiagnostic)
        assertTrue(safe.typedFields()["importCopiedBytes"] is Long)
        assertEquals(65536L,safe.typedFields()["importCopiedBytes"])
    }
    @Test fun unknownDiskValuesAndOutOfRangeCountersCannotEnterReports() {
        val unsafe = mapOf("importOrigin" to "content://secret/private.ARW", "importPhase" to "private path",
            "importFormat" to "image/private-raw", "importRoute" to "private-provider",
            "importFailure" to "IOException secret", "importCopiedBytes" to "536870913",
            "filename" to "private.ARW")
        assertNull(ImportDiagnosticInfo.fromFields(unsafe))
        assertNull(ImportDiagnosticInfo.fromFields(mapOf("importCopiedBytes" to "-1")))
        assertNull(ImportDiagnosticInfo.fromFields(mapOf("importCopiedBytes" to "1.5")))
        assertNull(ImportDiagnosticInfo.fromFields(mapOf("importCopiedBytes" to "9".repeat(9999))))
        assertTrue(SafeInfo.parse(unsafe).fields().isEmpty())
    }
    @Test fun suffixNeverCopiesNamesAndCannotChooseContentFormat() {
        val known=mapOf("secret.ARW" to ImportSuffix.ARW,"secret.multi.JPG" to ImportSuffix.JPG,"secret.3fr" to ImportSuffix.THREE_FR,"secret" to ImportSuffix.NONE)
        known.forEach{(name,value)->assertEquals(value,ImportSuffix.fromName(name))}
        for(name in listOf("private.SECRET","private.","private.后缀","private."+"x".repeat(10000),"private.jpg?token=secret"))assertEquals(ImportSuffix.UNKNOWN,ImportSuffix.fromName(name))
        val value=ImportDiagnosticInfo(format=PhotoFormat.PNG,suffix=ImportSuffix.fromName("private.ARW"),sourceBytes=123,width=32,height=16)
        val fields=SafeInfo(importDiagnostic=value).fields()
        assertEquals("PNG",fields["importFormat"]);assertEquals("ARW",fields["importSuffix"])
        assertFalse(fields.toString().contains("private"))
        assertEquals(value,SafeInfo.parse(fields).importDiagnostic)
        val raw=value.copy(format=PhotoFormat.TIFF,decodedRawFormat=dev.filmroom.codec.RawFormat.ARW)
        val rawFields=SafeInfo(importDiagnostic=raw).fields()
        assertEquals("HEADER_SIGNATURE",rawFields["importFormatBasis"])
        assertEquals("ARW",rawFields["importDecodedRawFormat"])
        assertEquals(raw,SafeInfo.parse(rawFields).importDiagnostic)
    }
    @Test fun memoryFactsRoundTripWithoutConflatingDecisionAndTerminalSamples() {
        val memory=ImportMemoryInfo(failure=ImportMemoryFailure.SHARED_ADMISSION,requestedBudgetMiB=128,selectedBudgetMiB=128,
            availableBytes=200,thresholdBytes=100,lowMemory=false,capacityBytes=0,reservedBytes=0,activeCount=0,
            snapshotStage=ImportMemorySnapshotStage.SHARED_ADMISSION,admitted=false,failureAvailableBytes=300,failureThresholdBytes=110,failureLowMemory=false)
        val value=ImportDiagnosticInfo(memoryStage=ImportMemoryStage.CODEC_ADMISSION,memory=memory)
        assertEquals(value,SafeInfo.parse(SafeInfo(importDiagnostic=value).fields()).importDiagnostic)
        val rejected=mapOf("importSuffix" to "private.ARW","importWidth" to "-1","importHeight" to "1000001",
            "importMemoryFailure" to "secret error","importSelectedBudgetMiB" to "513","importAvailableBytes" to "9".repeat(999),"importLowMemory" to "unknown")
        assertTrue(SafeInfo.parse(rejected).fields().isEmpty())
        assertNull(ImportDiagnosticInfo().width);assertNull(ImportDiagnosticInfo().sourceBytes)
    }
}
