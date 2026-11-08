package dev.filmroom.update

import org.json.JSONObject
import java.net.URI

/** versionCode, not the display name, is the monotonically increasing release identity. */
data class UpdateManifest(val versionCode:Long,val versionName:String,val releaseNotes:String,val downloads:List<Download>) {
    data class Download(val name:String,val url:String)
    fun isNewerThan(current:Long)=versionCode>current
    companion object {
        const val MAX_BYTES=65536
        fun parse(body:String):UpdateManifest {
            require(body.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
            val json=JSONObject(body)
            val code=json.get("versionCode")
            require(code is Int || code is Long) { "versionCode must be an integer" }
            val number=(code as Number).toLong();require(number in 1..Int.MAX_VALUE.toLong())
            fun field(key:String,max:Int,optional:Boolean=false):String {
                if(optional&&!json.has(key))return ""
                val value=json.get(key);require(value is String)
                return cleanText(value,max)
            }
            val name=field("versionName",80);require(name.isNotBlank())
            val notes=field("releaseNotes",4000,true)
            val rows=json.getJSONArray("downloads");require(rows.length() in 1..8)
            val links=(0 until rows.length()).map { i->
                val row=rows.getJSONObject(i)
                val label=row.get("name");val url=row.get("url")
                require(label is String && url is String)
                Download(cleanText(label,80).also{require(it.isNotBlank())},SafeUpdateUrl.requireHttps(url))
            }
            require(links.map{it.url}.distinct().size==links.size)
            return UpdateManifest(number,name,notes,links)
        }
        private fun cleanText(value:String,max:Int):String {
            require(value.length<=max)
            // Render only plain text, reject control/bidi overrides that could disguise a link label.
            require(value.none{(it<' '&&it!='\n'&&it!='\t') || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'})
            return value.trim()
        }
    }
}

object SafeUpdateUrl {
    fun requireHttps(value:String):String {
        require(value.length in 1..4096 && value==value.trim() && value.none{it<=' '||it=='\\'})
        val uri=URI(value)
        require(uri.scheme.equals("https",true)&&uri.rawUserInfo==null&&uri.fragment==null)
        require(uri.port==-1||uri.port==443)
        val host=uri.host?.lowercase() ?: error("Missing host")
        // Public DNS names only. No localhost, IP literals, Android intents or credentials.
        require(host.length<=253&&host.contains('.')&&!host.endsWith('.')&&!host.endsWith(".localhost")&&!host.endsWith(".local")&&!host.endsWith(".internal"))
        require(!host.all{it.isDigit()||it=='.'} && ':' !in host)
        require(host.split('.').all{it.isNotEmpty()&&it.length<=63&&it.first()!='-'&&it.last()!='-'&&it.all{c->c in 'a'..'z'||c in '0'..'9'||c=='-'}})
        return value
    }
}

object UpdateConfig {
    // Set to the public COS JSON HTTPS URL before distributing an update-enabled build.
    // Empty is intentionally unconfigured: no guessed endpoint, account ID or credential.
    const val MANIFEST_URL="https://filmroom-1305412660.cos.ap-guangzhou.myqcloud.com/filmroom-update.json"
}
