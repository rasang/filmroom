package dev.filmroom.border

/** Immutable, reference-informed display layouts. Pixel coordinates describe the approved
 * original artwork, NOT camera gate specifications or measured manufacturing tolerances. */
internal data class BwFrameGeometry(val width:Double,val height:Double,val left:Double,val top:Double,val right:Double,val bottom:Double,val pitch:Double=0.0) {
    val apertureWidth get()=right-left
    val apertureHeight get()=bottom-top
    val aspect get()=apertureWidth/apertureHeight
    fun imageWindow(x:Double,y:Double,super8Fringe:Boolean=false):Boolean {
        val windowLeft = if (pitch > 0.0 && !super8Fringe) left else left - 3.0
        if (x < windowLeft || x > right + 3.0) return false
        val inWindow=(y>=top-3 && y<=bottom+3) || (pitch>0 && ((y>=top-pitch-3 && y<=bottom-pitch+3) || (y>=top+pitch-3 && y<=bottom+pitch+3)))
        if(!inWindow)return false
        if(super8Fringe && pitch>0 && x<left){
            // The authored Gaussian feather extends three texels left of the image window.
            // It is NOT a white perforation. At the touching rounded hole, assign only the
            // nearest-hole fringe to the matte, retaining the original white-hole silhouette.
            // Hole: [82,354,250,562], radius24; repeated at the same796px film pitch.
            val localY=y-kotlin.math.round((y-458.0)/pitch)*pitch
            val dx=maxOf(106.0-x,0.0,x-226.0)
            val dy=maxOf(378.0-localY,0.0,localY-538.0)
            val holeDistance=kotlin.math.sqrt(dx*dx+dy*dy)-24.0
            if(holeDistance<left-x)return false
        }
        return true
    }
    companion object {
        private val doublex=BwFrameGeometry(1520.0,1400.0,40.0,220.0,1480.0,1180.0)
        private val trix=BwFrameGeometry(1404.0,926.0,250.0,80.0,1296.0,846.0,796.0)
        private val print=BwFrameGeometry(1400.0,760.0,202.0,20.0,1198.0,740.0)
        fun forStock(stock:String):BwFrameGeometry?=when(stock){
            "kodak_doublex"->doublex
            "kodak_trix"->trix
            "kodak_2302"->print
            else->null
        }
    }
}
