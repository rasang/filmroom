package dev.filmroom.engine

import dev.filmroom.diagnostics.PreviewFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

class PreviewAssetIdentityHostTest {
    private fun expectReason(reason:PreviewFailureReason,block:()->Unit) {
        try {block();fail("Expected a typed asset identity failure")}
        catch(error:PreviewAssetIdentityException) {assertEquals(reason,error.safeReason)}
    }

    @Test fun canonicalManifestProducesKnownSha256() {
        val root=Files.createTempDirectory("preview-identity").toFile().canonicalFile
        try {
            File(root,".prepared-v2").writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PreviewAssetIdentity.revision(root,EmptyCoroutineContext))
        } finally {root.deleteRecursively()}
    }

    @Test fun missingManifestHasTypedSafeReason() {
        val root=Files.createTempDirectory("preview-identity-missing").toFile().canonicalFile
        try {
            expectReason(PreviewFailureReason.ASSET_MANIFEST_MISSING) {
                PreviewAssetIdentity.revision(root,EmptyCoroutineContext)
            }
            File(root,".prepared-v2").mkdir()
            expectReason(PreviewFailureReason.ASSET_MANIFEST_MISSING) {
                PreviewAssetIdentity.revision(root,EmptyCoroutineContext)
            }
        } finally {root.deleteRecursively()}
    }

    @Test fun markerSymlinkIsRejectedEvenWhenTargetIsInsidePreparedRoot() {
        val root=Files.createTempDirectory("preview-identity-marker-link").toFile().canonicalFile
        val marker=File(root,".prepared-v2")
        try {
            val target=File(root,"real-manifest").apply{writeText("abc")}
            Files.createSymbolicLink(marker.toPath(),target.toPath())
            expectReason(PreviewFailureReason.ASSET_PATH_REJECTED) {
                PreviewAssetIdentity.revision(root,EmptyCoroutineContext)
            }
            assertEquals("abc",target.readText())
        } finally {marker.delete();root.deleteRecursively()}
    }

    @Test fun engineDataDescendantSymlinkIsRejected() {
        val root=Files.createTempDirectory("preview-identity-dir-link").toFile().canonicalFile
        val link=File(root,"files/engine-data")
        try {
            val target=File(root,"other-engine-data").apply{mkdirs()}
            val marker=File(target,".prepared-v2").apply{writeText("abc")}
            link.parentFile!!.mkdirs()
            Files.createSymbolicLink(link.toPath(),target.toPath())
            expectReason(PreviewFailureReason.ASSET_PATH_REJECTED) {
                PreviewAssetIdentity.revision(link,EmptyCoroutineContext)
            }
            assertEquals("abc",marker.readText())
        } finally {link.delete();root.deleteRecursively()}
    }

    @Test fun cancellationPropagatesWithoutBecomingAssetFailure() {
        val root=Files.createTempDirectory("preview-identity-cancel").toFile().canonicalFile
        try {
            val marker=File(root,".prepared-v2").apply{writeText("abc")}
            val job=Job().apply{cancel()}
            try {
                PreviewAssetIdentity.revision(root,job)
                fail("Cancelled jobs must not produce a revision")
            } catch(_:CancellationException) {}
            assertEquals("abc",marker.readText())
        } finally {root.deleteRecursively()}
    }
}
