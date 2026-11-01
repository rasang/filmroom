package dev.filmroom.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/** Tap-only film browser. A card must never be wider than the available editor pane. */
class FilmStockScrollView(context:Context,private val preferredCardWidth:Int):HorizontalScrollView(context) {
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val available=if(View.MeasureSpec.getMode(widthMeasureSpec)==View.MeasureSpec.UNSPECIFIED)preferredCardWidth
            else (View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
        val width=minOf(preferredCardWidth,available)
        (getChildAt(0) as? ViewGroup)?.let{cards->
            for(index in 0 until cards.childCount){
                val card=cards.getChildAt(index)
                if(card.layoutParams.width!=width)card.layoutParams=card.layoutParams.apply{this.width=width}
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
