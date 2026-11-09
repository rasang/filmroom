package dev.filmroom.diagnostics

import org.junit.Assert.*
import org.junit.Test

class BackupDiagnosticInfoTest {
    @Test fun allStagesRoundTripWithoutDocumentContents() {
        for (action in BackupAction.entries) for (step in BackupStep.entries) for (failure in BackupFailure.entries) {
            val info = BackupDiagnosticInfo(action, step, failure, 4, 12, true)
            val fields = SafeInfo(backup=info).fields()
            assertEquals(info, SafeInfo.parse(fields).backup)
            assertEquals(fields, SafeInfo.parse(fields).fields())
        }
    }
    @Test fun privateAndMalformedFieldsAreDropped() {
        val input = mapOf("backupAction" to "RESTORE", "backupStep" to "HISTORY",
            "backupFailure" to "content://personal/name", "backupRecipes" to "-1",
            "backupHistory" to "100001", "backupWritesStarted" to "anything",
            "uri" to "content://private/album", "backupJson" to "secret")
        val clean = SafeInfo.parse(input).fields()
        assertEquals("UNKNOWN", clean["backupFailure"])
        assertFalse(clean.containsKey("backupRecipes"))
        assertFalse(clean.containsKey("backupHistory"))
        assertFalse(clean.values.any { "private" in it || "secret" in it || "personal" in it })
    }
    @Test fun unknownActionsAndStepsDoNotEnterReports() {
        assertNull(BackupDiagnosticInfo.parse(mapOf("backupAction" to "name", "backupStep" to "CODEC")))
        assertNull(BackupDiagnosticInfo.parse(mapOf("backupAction" to "READ", "backupStep" to "path")))
    }
}
