package dev.filmroom.presets

import dev.filmroom.diagnostics.RecipeReason
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class RecipeLogTest {
    @Test fun storeFailuresAreCategorizedWithoutText() {
        for (problem in SavedPresetStore.Problem.entries) {
            assertEquals(problem.name, RecipeLog.reason(SavedPresetStore.Failure(problem)).name)
        }
    }
    @Test fun qrFailuresAreSpecific() {
        assertEquals(RecipeReason.INVALID_QR, RecipeLog.reason(PresetQrCodec.Failure(PresetQrCodec.Problem.INVALID)))
        assertEquals(RecipeReason.INCOMPATIBLE, RecipeLog.reason(PresetQrCodec.Failure(PresetQrCodec.Problem.INCOMPATIBLE)))
        assertEquals(RecipeReason.TOO_LARGE, RecipeLog.reason(PresetQrCodec.Failure(PresetQrCodec.Problem.TOO_LARGE)))
    }
    @Test fun ioAndPermissionFailuresNeverUseMessageOrPath() {
        assertEquals(RecipeReason.PERMISSION_DENIED, RecipeLog.reason(SecurityException("content://private")))
        assertEquals(RecipeReason.FILE_NOT_FOUND, RecipeLog.reason(FileNotFoundException("/private/photo")))
        assertEquals(RecipeReason.IO_ERROR, RecipeLog.reason(IOException("private")))
        assertEquals(RecipeReason.OUT_OF_MEMORY, RecipeLog.reason(OutOfMemoryError("private")))
        assertEquals(RecipeReason.UNKNOWN, RecipeLog.reason(Exception("private")))
    }
}
