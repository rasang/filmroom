package dev.filmroom.model

import android.content.Context
import dev.filmroom.R

/** Stable wire IDs, independent of translated labels and enum order. */
enum class RawBatchMode(val id:String,val titleRes:Int) {
    AUTO("auto",R.string.raw_batch_auto),
    SMALL("small",R.string.raw_batch_small),
    STANDARD("standard",R.string.raw_batch_standard),
    LARGE("large",R.string.raw_batch_large);

    companion object {
        fun fromId(id:String?):RawBatchMode=entries.firstOrNull{it.id==id} ?: AUTO
    }
}

/** App-level import preference: never part of photo parameters, presets or export snapshots. */
class RawBatchPreferences(context:Context) {
    private val values=context.applicationContext.getSharedPreferences("raw-processing-preferences",Context.MODE_PRIVATE)
    var mode:RawBatchMode
        // Old/corrupt/unknown settings safely return AUTO, including wrong stored value types.
        get()=RawBatchMode.fromId(try{values.getString("raw-batch-mode",null)}catch(_:ClassCastException){null})
        // A tiny confirmed setting is persisted before the chooser closes, including cold restart.
        set(value){values.edit().putString("raw-batch-mode",value.id).commit()}
}
