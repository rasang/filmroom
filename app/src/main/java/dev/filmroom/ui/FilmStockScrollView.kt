package dev.filmroom.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Tap-only browser: two whole cards and half the next card hint that more can be browsed. */
class FilmStockScrollView @JvmOverloads constructor(context:Context,private val preferredCardWidth:Int,private val artworkEdgeDp:Int=72):HorizontalScrollView(context) {
    /** Full, unfiltered catalog. Its tallest wrapped label determines every filter height. */
    var heightReferenceCards:List<View> = emptyList()

    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val available=if(View.MeasureSpec.getMode(widthMeasureSpec)==View.MeasureSpec.UNSPECIFIED)preferredCardWidth
            else (View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
        (getChildAt(0) as? ViewGroup)?.let{cards->
            val first=heightReferenceCards.firstOrNull() ?: cards.getChildAt(0)
            val margins=first?.layoutParams as? ViewGroup.MarginLayoutParams
            val gap=(margins?.marginStart ?: 0)+(margins?.marginEnd ?: 0)
            val viewport=(available-cards.paddingLeft-cards.paddingRight).coerceAtLeast(1)
            val density=resources.displayMetrics.density
            val scale=resources.configuration.fontScale
            // Large accessibility text may show fewer items rather than clipped or tiny labels.
            val readable=if(scale>1.35f)(112*density*scale).roundToInt() else (48*density).roundToInt()
            val ideal=((viewport-2*gap)/2.5f).coerceAtLeast(readable.toFloat()).coerceAtMost(viewport.toFloat())
            fun sizeArtwork(card:View,width:Int){
                (card as? TextView)?.let { label ->
                    label.compoundDrawables[1]?.let { art ->
                        val edge=minOf((artworkEdgeDp*density).roundToInt(),(width-label.paddingLeft-label.paddingRight).coerceAtLeast(1))
                        if(art.bounds.width()!=edge){art.setBounds(0,0,edge,edge);label.setCompoundDrawables(null,art,null,null)}
                    }
                }
            }
            // Measure the whole catalog at the narrowest rounded slot. Filtering changes
            // neither the height nor the preview area; orientation/font changes remeasure it.
            val stableHeight=heightReferenceCards.maxOfOrNull { card ->
                val width=ideal.toInt().coerceAtLeast(1)
                sizeArtwork(card,width)
                card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                card.measuredHeight
            }
            for(index in 0 until cards.childCount){
                val width=if(cards.getChildAt(index).tag=="film-filter-empty")viewport else ((index+1)*ideal).roundToInt()-(index*ideal).roundToInt()
                val card=cards.getChildAt(index)
                val height=stableHeight ?: card.layoutParams.height
                if(card.layoutParams.width!=width || card.layoutParams.height!=height)
                    card.layoutParams=card.layoutParams.apply{this.width=width;this.height=height}
                sizeArtwork(card,width)
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
