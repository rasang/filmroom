package dev.filmroom.engine

/** Distinguishes passive ownership / admission waits from actual JNI borrowing. */
internal class PreviewCacheLease(private val release:()->Unit) {
    private var borrowed=false
    private var retired=false
    private var released=false
    @Synchronized fun borrow():Boolean {
        if(retired || released)return false
        check(!borrowed);borrowed=true;return true
    }
    @Synchronized fun retire(){retired=true;releaseIfIdle()}
    @Synchronized fun finish(success:Boolean){borrowed=false;if(!success)retired=true;releaseIfIdle()}
    @Synchronized fun isRetired()=retired
    @Synchronized fun isReleased()=released
    private fun releaseIfIdle(){if(retired && !borrowed && !released){released=true;release()}}
}
