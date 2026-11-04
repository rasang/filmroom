package dev.filmroom.border

/** Nominal 120 windows, not a claim about any particular camera's measured gate. */
enum class MediumFormat(val label:String,val longitudinalMm:Double) {
    MF645("645",41.5), MF66("6×6",56.0), MF67("6×7",70.0), MF69("6×9",84.0), MF617("6×17",168.0);
    val landscapeAspect get()=if(this==MF645)56.0/longitudinalMm else longitudinalMm/56.0
    fun geometry(portrait:Boolean)=MediumFrameGeometry(this,portrait)
}

/** 61mm film, 56mm cross-film window, 3mm designed end allowance on each end.
 * 645 landscape turns the continuous strip clockwise; other landscape formats do not. */
data class MediumFrameGeometry(val format:MediumFormat,val portrait:Boolean) {
    val rotated get()=(format==MediumFormat.MF645) xor portrait
    val apertureWidth get()=if(rotated)56.0 else format.longitudinalMm
    val apertureHeight get()=if(rotated)format.longitudinalMm else 56.0
    val width get()=if(rotated)61.0 else format.longitudinalMm+6.0
    val height get()=if(rotated)format.longitudinalMm+6.0 else 61.0
    val left get()=if(rotated)2.5 else 3.0
    val top get()=if(rotated)3.0 else 2.5
    val aspect get()=apertureWidth/apertureHeight
    /** Frame coordinates to unrotated physical strip coordinates, in millimetres. */
    fun stripPoint(x:Double,y:Double,centerMm:Double):Pair<Double,Double> =
        if(rotated)(centerMm-(format.longitudinalMm+6.0)/2+y) to (61.0-x)
        else (centerMm-(format.longitudinalMm+6.0)/2+x) to y
}

/** Profile aliases share exactly one physical strip. Unsupported 135-only stocks fail closed. */
object MediumFormatStocks {
    val profiles:Map<String,String> = listOf("kodak_portra_160","kodak_portra_400","kodak_portra_800","kodak_gold_200","kodak_ektar_100","kodak_ektachrome_100","fujifilm_provia_100f","fujifilm_velvia_100","fujifilm_pro_400h").associateWith{it} + mapOf(
        "kodak_portra_800_push1" to "kodak_portra_800", "kodak_portra_800_push2" to "kodak_portra_800")
    fun available(stock:String)=profiles.containsKey(stock)
    fun physicalStock(stock:String)=requireNotNull(profiles[stock]){"No reviewed 120 strip for $stock"}
}
