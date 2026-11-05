package dev.filmroom.imprint

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Presentation-only. Optional date override never changes capture metadata or exposure facts. */
data class ImprintSettings(
    val dateStyle:String="off",
    val corner:String="bottom_right",
    val size:Double=1.0,
    val glow:Double=1.0,
    val exposure:Boolean=false,
    val datePlacement:String="photo",
    val layoutVersion:Int=1,
    val dateEnabled:Boolean=dateStyle!="off",
    val manualDateTime:String?=null,
) {
    init {
        require(dateStyle in DATE_STYLES && corner in CORNERS && datePlacement in PLACEMENTS)
        require(size.isFinite() && size in 0.6..1.6 && glow.isFinite() && glow in 0.0..1.0)
        require(layoutVersion==1)
        require(manualDateTime==null || parseDateTime(manualDateTime)!=null)
    }
    val selectedDateStyle get()=dateStyle.takeUnless{it=="off"} ?: "contax_t2"
    val effectiveDateStyle get()=if(dateEnabled)selectedDateStyle else "off"
    val enabled get()=dateEnabled || exposure
    /** Preserve presentation preferences, but never apply one photo's date override to a new import. */
    fun forNewPhoto()=copy(manualDateTime=null)
    fun json()=JSONObject().put("version",3).put("dateStyle",dateStyle).put("corner",corner)
        .put("size",size).put("glow",glow).put("exposure",exposure).put("datePlacement",datePlacement).put("layoutVersion",layoutVersion)
        .put("dateEnabled",dateEnabled).put("manualDateTime",manualDateTime ?: JSONObject.NULL)
    companion object {
        val DATE_STYLES=listOf("off","contax_t2","yashica_t4","canon_data_back","canon_luna")
        val CORNERS=listOf("bottom_right","bottom_left","top_right","top_left")
        val PLACEMENTS=listOf("photo","edge")
        fun parseDate(value:String):LocalDate?=runCatching {
            require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            LocalDate.parse(value).also{require(it.year in 1..9999)}
        }.getOrNull()
        private val DATE_TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
        fun parseDateTime(value:String):LocalDateTime?=runCatching {
            require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")))
            LocalDateTime.parse(value,DATE_TIME).also{require(it.year in 1..9999)}
        }.getOrNull()
        fun formatDateTime(value:LocalDateTime):String=DATE_TIME.format(value)
        fun fromJson(j:JSONObject):ImprintSettings {
            val version=j.getInt("version");require(version in 1..3)
            // v1 date-only overrides were retired in v2. Do not invent their missing time.
            // v3 explicitly persists a per-photo full local date/time override.
            return ImprintSettings(j.getString("dateStyle"),j.getString("corner"),j.getDouble("size"),j.getDouble("glow"),j.getBoolean("exposure"),
                if(version>=2)j.getString("datePlacement")else "photo",if(version>=2)j.getInt("layoutVersion")else 1,
                if(version==3)j.getBoolean("dateEnabled")else j.getString("dateStyle")!="off",
                if(version==3 && !j.isNull("manualDateTime"))j.getString("manualDateTime")else null)
        }
    }
}
