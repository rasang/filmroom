package dev.filmroom.ui

/** Adapt to the current app window, not the device model or physical orientation.
 * A medium-width portrait window (opened foldable/tablet) uses the existing two-pane editor.
 * This policy does not claim hinge/posture detection. System-bar policy remains separate.
 */
object EditorLayoutPolicy {
    fun usesTwoPanes(widthDp:Int,heightDp:Int,multiWindow:Boolean=false):Boolean = widthDp>=600 || (widthDp>heightDp && !multiWindow)
}
