package dev.filmroom.effects

import android.animation.ValueAnimator
import android.view.View
import android.widget.TextView

/** One inner-to-outer ripple per enabled-state change; no idle animation or button movement. */
internal object HomeLiveIconPulse {
    fun install(view:TextView,color:Int) {
        val old=view.compoundDrawables[0]
        val icon=old as? HomeLiveIconDrawable ?: HomeLiveIconDrawable().also {
            if(old!=null)it.bounds=old.bounds
            else {val size=(24*view.resources.displayMetrics.density).toInt();it.setBounds(0,0,size,size)}
            view.setCompoundDrawables(it,null,null,null)
        }
        icon.setTint(color)
    }
    private fun icon(view:View)=(view as? TextView)?.compoundDrawables?.firstOrNull() as? HomeLiveIconDrawable
    fun reset(view:View) {
        view.animate().cancel()
        view.scaleX=1f;view.scaleY=1f;view.alpha=1f
        icon(view)?.reset()
    }
    fun play(view:View,animationsEnabled:Boolean=ValueAnimator.areAnimatorsEnabled()) {
        reset(view)
        if(!animationsEnabled || !view.isAttachedToWindow)return
        icon(view)?.play()
    }
}
