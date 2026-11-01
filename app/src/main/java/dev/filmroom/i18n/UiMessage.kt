package dev.filmroom.i18n

import org.json.JSONArray
import org.json.JSONObject

/** Stable resource names and string arguments survive APK resource-ID changes and locale changes. */
data class UiMessage(val key:String, val args:List<String> = emptyList()) {
    fun render():String?=if(ResourceKeys.arities[key]!=args.size)null else ResourceKeys.ids[key]?.let{runCatching{L10n.text(it,*args.toTypedArray())}.getOrNull()}
    fun toJson():JSONObject=JSONObject().put("key",key).put("args",JSONArray(args))
    companion object {
        fun of(resource:Int,vararg args:Any?):UiMessage=UiMessage(requireNotNull(ResourceKeys.names[resource]),args.map{it.toString()})
        /** Optional field: old queues and future unknown keys remain readable using their raw fallback. */
        fun fromJson(value:JSONObject?):UiMessage? {
            if(value==null)return null
            val key=value.optString("key")
            if(key !in ResourceKeys.ids)return null
            val args=value.optJSONArray("args") ?: JSONArray()
            if(args.length()>32)return null
            return UiMessage(key,(0 until args.length()).map{args.optString(it)})
        }
    }
}
