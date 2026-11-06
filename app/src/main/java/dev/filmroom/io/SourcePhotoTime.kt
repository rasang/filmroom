package dev.filmroom.io

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Query the original provider, never the newly created private copy's filesystem clock. */
internal object SourcePhotoTime {
    fun modified(resolver:ContentResolver,uri:Uri,zone:ZoneId=ZoneId.systemDefault()):String? {
        // Providers differ in supported columns; an unsupported projection must not hide another one.
        for((column,multiplier) in listOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED to 1L,
            MediaStore.MediaColumns.DATE_MODIFIED to 1000L)) {
            val millis=runCatching {
                resolver.query(uri,arrayOf(column),null,null,null)?.use { cursor ->
                    val index=cursor.getColumnIndex(column)
                    if(index<0 || !cursor.moveToFirst() || cursor.isNull(index))null
                    else Math.multiplyExact(cursor.getLong(index),multiplier).takeIf{it>0}
                }
            }.getOrNull() ?: continue
            val date=runCatching { Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().also{require(it.year in 1..9999)} }.getOrNull() ?: continue
            return date.format(DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss",Locale.ROOT))
        }
        // DATE_ADDED is the gallery insertion time, not the photo's creation time.
        return null
    }
}
