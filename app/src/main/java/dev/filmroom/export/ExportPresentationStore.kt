package dev.filmroom.export

import android.content.Context

/** Recovery hints only. Never changes export state or completion notification/read semantics. */
internal object ExportPresentationStore {
    private fun prefs(context:Context)=context.getSharedPreferences("export-preview-presentations",Context.MODE_PRIVATE)
    fun pending(context:Context,id:String,attempt:String)=prefs(context).getString(id,null)?.substringBefore('|')==attempt
    fun clear(context:Context,id:String){val p=prefs(context);if(p.contains(id))p.edit().remove(id).apply()}
    fun remember(context:Context,id:String,attempt:String,active:Boolean) {
        if(!active){clear(context,id);return}
        val p=prefs(context)
        if(p.getString(id,null)?.substringBefore('|')==attempt)return
        val editor=p.edit().putString(id,"$attempt|${System.currentTimeMillis()}")
        val entries=p.all
        entries.entries.filter{it.key!=id}.sortedBy{(it.value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L}
            .take((entries.size-255).coerceAtLeast(0)).forEach{editor.remove(it.key)}
        editor.apply()
    }
}
