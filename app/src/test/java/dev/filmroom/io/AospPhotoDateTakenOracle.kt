package dev.filmroom.io

import androidx.exifinterface.media.ExifInterface

/** Host oracle for AOSP ModernMediaScanner.parseOptionalDateTaken, NOT an OEM emulator.
 * Scanner resets DATE_TAKEN before this, so an insert-only value cannot survive a null result.
 * https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/master/src/com/android/providers/media/scan/ModernMediaScanner.java
 */
internal object AospPhotoDateTakenOracle {
    fun scan(exif:ExifInterface,lastModifiedTime:Long):Long? {
        val original=exif.dateTimeOriginal ?: return null
        if(exif.hasAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))return original
        val unit=15*60*1000L
        fun inferred(reference:Long?):Long? {
            if(reference==null || reference<=0)return null
            val delta=reference-original
            return if(kotlin.math.abs(delta)<24*60*60*1000L)original+Math.round(delta.toFloat()/unit)*unit else null
        }
        return inferred(exif.gpsDateTime) ?: inferred(lastModifiedTime)
    }
}
