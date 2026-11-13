package dev.filmroom.effects

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/** One small pulse per enabled-state change, never an idle animation. */
internal object HomeLiveIconPulse {
    fun reset(view:View) {
        view.animate().cancel()
        view.scaleX=1f;view.scaleY=1f;view.alpha=1f
    }
    fun play(view:View,animationsEnabled:Boolean=ValueAnimator.areAnimatorsEnabled()) {
        reset(view)
        if(!animationsEnabled || !view.isAttachedToWindow)return
        view.animate().scaleX(1.10f).scaleY(1.10f).alpha(.72f)
            .setDuration(240L).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(360L).setInterpolator(DecelerateInterpolator()).withEndAction(null).start()
            }.start()
    }
}
