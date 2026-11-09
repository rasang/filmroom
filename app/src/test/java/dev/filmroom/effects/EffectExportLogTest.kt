package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EffectExportLogTest {
    @Test fun classifiesWithoutRetainingErrorText() {
        assertEquals(EffectExportReason.OUT_OF_MEMORY,EffectExportLog.reason(OutOfMemoryError("private")))
        assertEquals(EffectExportReason.IO_ERROR,EffectExportLog.reason(IOException("content://private")))
        assertEquals(EffectExportReason.INVALID_ARGUMENT,EffectExportLog.reason(IllegalArgumentException("secret")))
        assertEquals(EffectExportReason.INVALID_STATE,EffectExportLog.reason(IllegalStateException("file name")))
        assertEquals(EffectExportReason.PERMISSION_DENIED,EffectExportLog.reason(SecurityException("content://private")))
        assertEquals(EffectExportReason.UNKNOWN,EffectExportLog.reason(Exception("unknown")))
    }
    @Test fun liveFailureCodesArePreservedWithoutMessages() {
        val error=dev.filmroom.livephoto.core.LivePhotoException(
            dev.filmroom.livephoto.core.ErrorCode.VERIFICATION_FAILED,"content://private/output")
        assertEquals(EffectExportReason.LIVE_VERIFY_FAILED,EffectExportLog.reason(error))
    }
    @Test fun observerDoesNotChangeReturnValue() {
        val value=Any()
        assertSame(value,EffectExportLog.trace(EffectExportScope.VIDEO){value})
    }
    @Test fun observerPreservesSameFailureAndCancellation() {
        for(error in listOf(IOException("private"),CancellationException("private"),OutOfMemoryError("private"))){
            try {EffectExportLog.trace(EffectExportScope.INPUT_DECODE){throw error};fail("must rethrow")}
            catch(actual:Throwable){assertSame(error,actual)}
        }
    }
}
