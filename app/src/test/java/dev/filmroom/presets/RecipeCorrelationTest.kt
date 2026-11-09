package dev.filmroom.presets

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class RecipeCorrelationTest {
    @Test fun scannerAndStoreHandoffKeepSameAnonymousToken() {
        val token = RecipeCorrelation.next()
        assertEquals(token, RecipeCorrelation.accept(token))
        RecipeCorrelation.saved("test-private-recipe-id", token)
        assertEquals(token, RecipeCorrelation.forApply("test-private-recipe-id"))
        RecipeCorrelation.deleted("test-private-recipe-id")
        assertNotEquals(token, RecipeCorrelation.forApply("test-private-recipe-id"))
    }
    @Test fun independentAndRepeatedAttemptsNeverShareTokens() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tokens = pool.invokeAll((1..100).map { java.util.concurrent.Callable { RecipeCorrelation.next() } }).map { it.get() }
            assertEquals(100, tokens.toSet().size)
            assertTrue(tokens.all { it > 0 })
        } finally { pool.shutdownNow() }
    }
    @Test fun unknownOrOldProcessTokenIsNotAdopted() {
        assertNotEquals(0L, RecipeCorrelation.accept(0L))
        assertNotEquals(-1L, RecipeCorrelation.accept(-1L))
        // Eviction models a token no longer in this process's issued-token registry.
        val old = RecipeCorrelation.next()
        repeat(513) { RecipeCorrelation.next() }
        assertNotEquals(old, RecipeCorrelation.accept(old))
    }
    @Test fun rememberedImportsAreBounded() {
        val first = RecipeCorrelation.next()
        RecipeCorrelation.saved("evicted-private-id", first)
        repeat(201) { RecipeCorrelation.saved("private-$it", RecipeCorrelation.next()) }
        assertNotEquals(first, RecipeCorrelation.forApply("evicted-private-id"))
    }
}
