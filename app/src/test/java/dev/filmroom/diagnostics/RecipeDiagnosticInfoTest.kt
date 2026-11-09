package dev.filmroom.diagnostics

import org.junit.Assert.*
import org.junit.Test

class RecipeDiagnosticInfoTest {
    @Test fun anonymousCorrelationRoundTripsWithoutAcceptingText() {
        val info = RecipeDiagnosticInfo(RecipeAction.SAVE, correlation = Long.MAX_VALUE)
        assertEquals(info, SafeInfo.parse(SafeInfo(recipe = info).fields()).recipe)
        for (bad in listOf("-1", "0", "content://private", "9223372036854775808")) {
            assertNull(RecipeDiagnosticInfo.fromFields(mapOf("recipeAction" to "SAVE", "recipeCorrelation" to bad))?.correlation)
        }
    }

    @Test fun allActionsStagesAndReasonsRoundTrip() {
        for (action in RecipeAction.entries) for (step in RecipeStep.entries) for (reason in RecipeReason.entries) {
            val info = RecipeDiagnosticInfo(action, step, reason, RecipeInput.ALBUM, 200, 1536, 1200, 800)
            assertEquals(info, SafeInfo.parse(SafeInfo(recipe = info).fields()).recipe)
        }
    }
    @Test fun numbersAreBoundedAndSecretsAreNotRetained() {
        val info = RecipeDiagnosticInfo(RecipeAction.QR_IMPORT, count = 201, width = -1, height = 20001, elapsedMs = -1)
        val fields = info.fields()
        assertFalse(fields.keys.any { it in listOf("recipeCount", "recipeWidth", "recipeHeight", "recipeElapsedMs") })
        val injected = mapOf("recipeAction" to "QR_IMPORT", "recipeStep" to "/storage/secret",
            "recipeReason" to "content://private", "recipeInput" to "secret-name", "recipeCount" to "1e2",
            "recipeWidth" to "NaN", "recipeHeight" to "-2", "recipeElapsedMs" to "2147483648",
            "recipePayload" to "FILMROOM:secret", "recipeName" to "private", "recipeUri" to "content://private")
        val clean = SafeInfo.parse(injected).fields()
        assertFalse(clean.values.any { it.contains("secret") || it.contains("private") || it.contains("storage") || it.contains("FILMROOM:") })
        assertFalse(clean.keys.any { it in listOf("recipePayload", "recipeName", "recipeUri", "recipeCount", "recipeWidth", "recipeHeight", "recipeElapsedMs") })
    }
    @Test fun unknownActionDropsSnapshot() {
        assertNull(RecipeDiagnosticInfo.fromFields(mapOf("recipeAction" to "user title")))
    }
    @Test fun recipeDoesNotOverwriteUnrelatedDiagnostics() {
        val old = SafeInfo(width = 42, height = 24).fields()
        val added = SafeInfo(width = 42, height = 24, recipe = RecipeDiagnosticInfo(RecipeAction.SAVE)).fields()
        assertEquals(old, added.filterKeys { !it.startsWith("recipe") })
    }
}
