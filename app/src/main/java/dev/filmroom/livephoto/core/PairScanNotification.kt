package dev.filmroom.livephoto.core

/** Scanner acknowledgement is not vendor Gallery pairing confirmation. */
enum class MediaScanStatus { SCANNED, DIFFERENT_URI, PATH_UNAVAILABLE, REQUEST_FAILED, TIMED_OUT }
data class MediaScanResult(val mediaId: String, val status: MediaScanStatus, val detail: String? = null)
fun interface PublishedMediaScanner { fun scan(mediaId: String, mimeType: String): MediaScanResult }

/** Invoke only after PublicationCoordinator returns COMMITTED. Never writes or rolls back media rows. */
object PairScanNotification {
    fun notify(asset: PackagedAsset, published: PublishedAsset, scanner: PublishedMediaScanner): List<MediaScanResult> {
        if (asset.profile != LivePhotoProfile.VIVO_PAIR_2014) return emptyList()
        if (published.assetId != asset.id || published.ids.size != asset.parts.size || published.ids.size != 2) {
            return listOf(MediaScanResult("", MediaScanStatus.REQUEST_FAILED, "Invalid committed pair"))
        }
        // The adapter bounds each callback wait (not platform query/call duration). A missing callback cannot suppress the second file.
        return published.ids.zip(asset.parts).map { (id, part) ->
            try { scanner.scan(id, part.mimeType) }
            catch (e: Exception) { MediaScanResult(id, MediaScanStatus.REQUEST_FAILED, e.javaClass.simpleName) }
            catch (e: LinkageError) { MediaScanResult(id, MediaScanStatus.REQUEST_FAILED, e.javaClass.simpleName) }
        }
    }
}
