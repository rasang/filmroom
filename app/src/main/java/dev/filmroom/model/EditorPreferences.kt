package dev.filmroom.model

import android.content.Context

/** App-level choices are not image parameters and never enter queued exports or undo history. */
class EditorPreferences(context:Context) {
    private val values=context.applicationContext.getSharedPreferences("editor-preferences",Context.MODE_PRIVATE)
    var resetOnImport:Boolean
        get()=values.getBoolean("reset-on-import",false)
        set(value){values.edit().putBoolean("reset-on-import",value).apply()}
    var restoreLastPhoto:Boolean
        get()=values.getBoolean("restore-last-photo",false)
        set(value){values.edit().putBoolean("restore-last-photo",value).apply()}
}
