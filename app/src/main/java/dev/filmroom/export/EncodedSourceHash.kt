package dev.filmroom.export

import dev.filmroom.io.PhotoLimits
import java.io.File

/** Full-content identity, never a sampled or metadata-only identity. Large linear sources
 * use the existing image admission limits, not an increased generic-file allowance.
 * One attempt shares one cooperative budget across lookup and publication revalidation.
 * Blocking filesystem calls cannot be preempted, but no further chunk is read after expiry.
 */
internal class EncodedSourceHash(
    private val inputKind:String,
    private val width:Int,
    private val height:Int,
    private val nanoTime:()->Long=System::nanoTime,
) {
    private var remainingNanos=HASH_BUDGET_NANOS

    fun hash(file:File,checkActive:()->Unit):String? {
        checkActive()
        val limit=sourceLimit(inputKind,width,height,file.length()) ?: return null
        if(remainingNanos<=0)return null
        val started=nanoTime()
        return try {
            EncodedPayloadCache.hashFile(file,limit,{
                nanoTime()-started < remainingNanos
            },checkActive)
        } finally {
            remainingNanos=(remainingNanos-(nanoTime()-started).coerceAtLeast(0)).coerceAtLeast(0)
        }
    }

    companion object {
        // Optional cache work gets at most one second of measured hashing per attempt.
        // Rendering/encoding between hashes does not consume this allowance.
        const val HASH_BUDGET_NANOS=1_000_000_000L
        fun sourceLimit(kind:String,width:Int,height:Int,bytes:Long):Long? {
            if(kind in setOf("LINEAR","LINEAR_SOURCE","RAW")) {
                if(!PhotoLimits.validSource(width,height))return null
                val expected=width.toLong()*height*12
                return expected.takeIf{bytes==it}
            }
            return EncodedPayloadCache.MAX_SOURCE_BYTES.takeIf{kind=="ENCODED" && bytes in 1..it}
        }
    }
}
