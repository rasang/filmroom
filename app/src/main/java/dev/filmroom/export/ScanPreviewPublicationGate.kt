package dev.filmroom.export

/** Per-attempt publication. Final finishing may replace an early scan once.
 * FilmEngine joins its polling worker before delivering the committed final.
 * Claims are attempts, so an optional JPEG failure cannot spin on every poll.
 */
internal class ScanPreviewPublicationGate {
    private var intermediateAttempted=false
    private var finalAttempted=false
    fun claim(final:Boolean):Boolean {
        if(finalAttempted || (!final && intermediateAttempted))return false
        if(final)finalAttempted=true else intermediateAttempted=true
        return true
    }
}
