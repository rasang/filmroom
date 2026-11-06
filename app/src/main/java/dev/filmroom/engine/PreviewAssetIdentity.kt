package dev.filmroom.engine

import dev.filmroom.diagnostics.PreviewFailureReason
import dev.filmroom.io.DetailSourceFactory
import java.io.File
import kotlin.coroutines.CoroutineContext

internal class PreviewAssetIdentityException(val safeReason:PreviewFailureReason):java.io.IOException("Preview asset identity unavailable")

/** The installer has resolved only the trusted Context root. Keep descendants
 * strict; accepting their canonical target would silently follow a symlink. */
internal object PreviewAssetIdentity {
    fun revision(preparedRoot:File,job:CoroutineContext):String {
        val marker=File(preparedRoot,".prepared-v2")
        if(marker.canonicalFile!=marker.absoluteFile)throw PreviewAssetIdentityException(PreviewFailureReason.ASSET_PATH_REJECTED)
        if(!marker.isFile)throw PreviewAssetIdentityException(PreviewFailureReason.ASSET_MANIFEST_MISSING)
        return DetailSourceFactory.sha256(marker,job)
    }
}
