package dev.filmroom.io

import dev.filmroom.diagnostics.CodecErrorCode
import dev.filmroom.diagnostics.CodecStage
import dev.filmroom.diagnostics.SafeInfo
import dev.filmroom.io.formats.PhotoFormat
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ImportFailureDiagnosticsTest {
    @Test fun distinguishesBoundedCauseCategoriesWithoutMessage() {
        assertEquals(CodecErrorCode.IMPORT_PERMISSION_DENIED, classifyImportCause(SecurityException("private path")))
        assertEquals(CodecErrorCode.IMPORT_SOURCE_NOT_FOUND, classifyImportCause(FileNotFoundException("private uri")))
        assertEquals(CodecErrorCode.IMPORT_IO, classifyImportCause(IOException("private path")))
        assertEquals(CodecErrorCode.IMPORT_UNEXPECTED, classifyImportCause(null))
    }
    @Test fun preservesExactCodecAndStageWhileAddingHeaderHint() {
        val original = PhotoException(PhotoException.Reason.UNSUPPORTED, "secret", codecCode=CodecErrorCode.HEIC_SOURCE_TRANSFER_MISMATCH, codecStage=CodecStage.VALIDATE)
        val result = enrichImportFailure(original, CodecStage.INPUT_PROCESS, PhotoFormat.HEIF)
        assertEquals(original.reason, result.reason)
        assertEquals(original.codecCode, result.codecCode)
        assertEquals(original.codecStage, result.codecStage)
        assertEquals(PhotoFormat.HEIF, result.inputFormatHint)
        val fields = SafeInfo(codecCode=result.codecCode, codecStage=result.codecStage, inputFormatHint=result.inputFormatHint).fields()
        assertFalse(fields.toString().contains("secret"))
        assertEquals(PhotoFormat.HEIF, SafeInfo.parse(fields).inputFormatHint)
        assertNull(SafeInfo.parse(mapOf("inputFormatHint" to "private-file-path")).inputFormatHint)
    }
}
