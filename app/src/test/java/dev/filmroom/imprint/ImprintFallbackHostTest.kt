package dev.filmroom.imprint

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.io.SourcePhotoTime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ImprintFallbackHostTest {
    private val settings=ImprintSettings(dateStyle="canon_luna")
    @Test fun captureWinsAndFallbackSurvivesQueueJsonWithoutBecomingCaptureExif() {
        val source=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2020:02:29 13:14:15","DateTime" to "2025:01:02 03:04:05","DateTimeDigitized" to "2021:01:02 03:04:05"))
        assertEquals("20年2月29日",ImprintContent.from(source,settings).date)
        val fallback=PhotoMetadata.fromAttributes(mapOf("DateTime" to "2025:01:02 03:04:05","DateTimeDigitized" to "2021:01:02 03:04:05"))
        for(m in listOf(fallback,PhotoMetadata.fromJson(fallback.toJson()))) {
            assertEquals("25年1月2日",ImprintContent.from(m,settings).date)
            assertEquals("modified",m.imprintFallbackKind)
            assertNull(ImprintContent.originalCaptureDateTime(m));assertNull(m.captureTimeMillis())
            assertFalse(m.imprintAttributes().containsKey("DateTimeOriginal"))
        }
        assertNull(ImprintContent.from(PhotoMetadata.unavailable().withImprintFallback("2025:02:30 00:00:00","modified"),settings).date)
    }
    @Test fun providerModificationUsesDocumentMillisOrMediaSecondsNeverDateAdded() {
        class Provider(val columns:Map<String,Long>):ContentProvider() {
            override fun onCreate()=true
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
                val column=projection!!.single()
                require(columns.containsKey(column))
                return MatrixCursor(arrayOf(column)).apply{addRow(arrayOf(columns.getValue(column)))}
            }
            override fun getType(uri:Uri)="image/jpeg"
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
            override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        }
        val resolver=RuntimeEnvironment.getApplication().contentResolver
        val uri=Uri.parse("content://imprint-date-fallback/photo")
        val epoch=java.time.Instant.parse("2021-12-31T23:00:00Z").toEpochMilli()
        for(columns in listOf(mapOf("last_modified" to epoch),mapOf("date_modified" to epoch/1000))) {
            ShadowContentResolver.registerProviderInternal("imprint-date-fallback",Provider(columns))
            assertEquals("2022:01:01 08:00:00",SourcePhotoTime.modified(resolver,uri,ZoneId.of("Asia/Tokyo")))
        }
        for(columns in listOf(mapOf("date_added" to epoch/1000),mapOf("last_modified" to 0L),emptyMap())) {
            ShadowContentResolver.registerProviderInternal("imprint-date-fallback",Provider(columns))
            assertNull(SourcePhotoTime.modified(resolver,uri,ZoneId.of("UTC")))
        }
    }
}
