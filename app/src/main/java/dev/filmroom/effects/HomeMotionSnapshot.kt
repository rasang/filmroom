package dev.filmroom.effects

import android.graphics.Bitmap
import dev.filmroom.EditorUiState

/** Reconstructed from the exp6 APK. Owns copies, never the editor's published bitmaps. */
internal class HomeMotionSnapshot(
    source: Bitmap,
    val result: Bitmap,
    val readout: EffectCameraReadout,
    private var composition: EffectSourceComposition? = null,
) : AutoCloseable {
    val motionComposition = composition
    var motionInput: HomeMotionInputOwner.Lease? = null
    var source: Bitmap = source
        private set

    /** Run off Main after capture, retaining the full original for video composition. */
    fun prepare(checkActive: () -> Unit = {}) {
        checkActive()
        val prepared = composition?.apply(source, checkActive) ?: source
        if (prepared !== source) { source.recycle(); source = prepared }
        composition = null
        checkActive()
    }

    override fun close() {
        motionInput?.close(); motionInput = null
        if (!source.isRecycled) source.recycle()
        if (result !== source && !result.isRecycled) result.recycle()
    }

    companion object {
        const val MAX_EDGE = 1600
        fun canRetain(frozen: EditorUiState, current: EditorUiState) =
            frozen.photo === current.photo && !current.compositionEditing && !current.exporting
        fun matches(frozen: EditorUiState?, current: EditorUiState) =
            frozen != null && ready(current) && frozen.photo === current.photo &&
                frozen.rendered === current.rendered && frozen.parameters == current.parameters
        fun ready(state: EditorUiState) = state.canExport && !state.previewRefining &&
            !state.compare && !state.exporting && state.original != null
        private fun copyBounded(bitmap: Bitmap, maxEdge:Int=MAX_EDGE): Bitmap {
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(bitmap.width, bitmap.height))
            val copy = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            return if (copy === bitmap) checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else copy
        }
        fun capture(state: EditorUiState, kind: EffectKind, prepare: Boolean = true, detailResult:Bitmap?=null): HomeMotionSnapshot {
            check(ready(state)) { "Preview not settled" }
            val result = if(detailResult!=null)copyBounded(detailResult,dev.filmroom.roi.ZoomFramePolicy.MAX_EDGE) else copyBounded(checkNotNull(state.rendered))
            var source: Bitmap? = null
            var owned: HomeMotionSnapshot? = null
            try {
                if (kind == EffectKind.LIGHT_TABLE) return HomeMotionSnapshot(result, result, EffectCameraReadout())
                source = copyBounded(checkNotNull(state.original))
                val composition = state.presentation?.let(EffectSourceComposition::fromBorder)
                    ?: state.cropPresentation?.let { EffectSourceComposition(it.canonicalWidth, it.canonicalHeight, it.settings) }
                owned = HomeMotionSnapshot(source, result, EffectCameraReadout.fromSnapshot(state.photo?.photoMetadata?.toJson()), composition)
                if (prepare) owned.prepare()
                return owned
            } catch (failure: Throwable) {
                if (owned != null) owned.close() else {
                    source?.takeUnless { it.isRecycled }?.recycle()
                    if (!result.isRecycled) result.recycle()
                }
                throw failure
            }
        }
    }
}
