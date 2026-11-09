package dev.filmroom.presets

/** Process-local anonymous random tokens, never derived from recipe contents or identity.
 * The bounded lookup is memory-only; neither its keys nor associations are persisted. */
internal object RecipeCorrelation {
    private val random = java.security.SecureRandom()
    private val active = linkedSetOf<Long>()
    private val imports = linkedMapOf<String, Long>()
    @Synchronized fun next(): Long {
        var value: Long
        do { value = random.nextLong() and Long.MAX_VALUE } while (value == 0L || value in active)
        active.add(value)
        while (active.size > 512) active.remove(active.first())
        return value
    }
    @Synchronized fun accept(value: Long): Long = value.takeIf { it in active } ?: next()
    @Synchronized fun saved(id: String, correlation: Long) {
        imports[id] = correlation
        while (imports.size > 200) imports.remove(imports.keys.first())
    }
    @Synchronized fun deleted(id: String) { imports.remove(id) }
    @Synchronized fun forApply(id: String): Long = imports[id] ?: next()
}
