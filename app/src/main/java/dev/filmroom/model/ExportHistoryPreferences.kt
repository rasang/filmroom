package dev.filmroom.model

import android.content.Context

enum class ExportHistoryAction(val key:String) { SINGLE("single-delete-mode"),CLEAR("clear-history-mode") }
enum class ExportHistoryMode(val id:String) { ASK("ask"),RECORDS_ONLY("records"),RECORDS_AND_PHOTOS("records-and-photos");companion object{fun fromId(id:String?)=entries.firstOrNull{it.id==id} ?: ASK} }
/** Independent app-level preferences; absent/corrupt values never opt into deleting photos. */
class ExportHistoryPreferences(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("export-history-preferences",Context.MODE_PRIVATE)
    fun get(action:ExportHistoryAction)=ExportHistoryMode.fromId(try{prefs.getString(action.key,null)}catch(_:ClassCastException){null})
    fun set(action:ExportHistoryAction,mode:ExportHistoryMode){check(prefs.edit().putString(action.key,mode.id).commit()){"Could not save export history preference"}}
}
