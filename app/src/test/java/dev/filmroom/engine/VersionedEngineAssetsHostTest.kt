package dev.filmroom.engine

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.DetailSourceFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class VersionedEngineAssetsHostTest {
    private fun contextWithFiles(files:File):Context {
        val base=ApplicationProvider.getApplicationContext<Context>()
        return object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=files
        }
    }

    @Test fun canonicalRootPreparesPackedLutAndReusesVerifiedSnapshot()=runBlocking {
        val outer=Files.createTempDirectory("assets-canonical").toFile().canonicalFile
        VersionedEngineAssets.clearMemoryForTest()
        try {
            val files=File(outer,"files").apply{mkdirs()}
            val context=contextWithFiles(files)
            val root=VersionedEngineAssets.prepare(context)
            assertEquals(File(files,"engine-data"),root)
            assertEquals(root.absoluteFile,root.canonicalFile)
            assertPreparedLut(root)
            val revision=PreviewAssetIdentity.revision(root,coroutineContext)
            assertEquals(64,revision.length)
            val again=VersionedEngineAssets.prepare(context)
            assertSame("Warm preparation returns the verified cached root",root,again)
            assertSame(root,VersionedEngineAssets.preparedForPrewarm())
            assertEquals(revision,PreviewAssetIdentity.revision(again,coroutineContext))
        } finally {
            VersionedEngineAssets.clearMemoryForTest()
            outer.deleteRecursively()
        }
    }

    @Test fun androidParentAliasReproducesOldHashFailureButNewPreparedRootWorks()=runBlocking {
        val outer=Files.createTempDirectory("assets-android-alias").toFile().canonicalFile
        val aliasParent=File(outer,"user/0")
        VersionedEngineAssets.clearMemoryForTest()
        try {
            val realData=File(outer,"data").apply{mkdirs()}
            val realFiles=File(realData,"dev.filmroom/files").apply{mkdirs()}
            aliasParent.parentFile!!.mkdirs()
            Files.createSymbolicLink(aliasParent.toPath(),realData.toPath())
            val aliasFiles=File(aliasParent,"dev.filmroom/files")
            assertNotEquals(aliasFiles.absoluteFile,aliasFiles.canonicalFile)
            val context=contextWithFiles(aliasFiles)
            val root=VersionedEngineAssets.prepare(context)
            assertEquals(File(realFiles,"engine-data"),root)
            assertEquals(root.absoluteFile,root.canonicalFile)
            assertPreparedLut(root)
            val oldMarker=File(aliasFiles,"engine-data/.prepared-v2")
            assertTrue(oldMarker.isFile)
            try {
                DetailSourceFactory.sha256(oldMarker,coroutineContext)
                fail("The old direct hash must reproduce the alias rejection")
            } catch(error:IllegalArgumentException) {
                assertTrue(error.stackTrace.any { it.className.startsWith("dev.filmroom.io.DetailSourceFactory") && it.methodName.startsWith("sha256") })
            }
            val revision=PreviewAssetIdentity.revision(root,coroutineContext)
            assertEquals(DetailSourceFactory.sha256(File(root,".prepared-v2"),coroutineContext),revision)
            assertSame(root,VersionedEngineAssets.prepare(context))
            assertSame(root,VersionedEngineAssets.preparedForPrewarm())
            assertEquals(revision,PreviewAssetIdentity.revision(root,coroutineContext))
        } finally {
            VersionedEngineAssets.clearMemoryForTest()
            aliasParent.delete()
            outer.deleteRecursively()
        }
    }

    @Test fun engineDataSymlinkIsRejectedBeforeInstallation()=runBlocking {
        val outer=Files.createTempDirectory("assets-descendant-link").toFile().canonicalFile
        val files=File(outer,"files").apply{mkdirs()}
        val outside=File(outer,"outside").apply{mkdirs()}
        val link=File(files,"engine-data")
        VersionedEngineAssets.clearMemoryForTest()
        try {
            File(outside,"keep").writeText("unchanged")
            Files.createSymbolicLink(link.toPath(),outside.toPath())
            try {
                VersionedEngineAssets.prepare(contextWithFiles(files))
                fail("An engine-data descendant alias is not a trusted Context alias")
            } catch(_:IllegalArgumentException) {}
            assertEquals(listOf("keep"),outside.list()!!.toList())
            assertEquals("unchanged",File(outside,"keep").readText())
            assertNull(VersionedEngineAssets.preparedForPrewarm())
        } finally {
            VersionedEngineAssets.clearMemoryForTest()
            link.delete()
            outer.deleteRecursively()
        }
    }

    private fun assertPreparedLut(root:File) {
        val lut=File(root,"luts/spectral_upsampling/${PackedSpectralLut.ORIGINAL_NAME}")
        assertArrayEquals(File(System.getProperty("filmroom.lut.original")!!).readBytes(),lut.readBytes())
        assertFalse(File(lut.parentFile,PackedSpectralLut.PACKED_NAME).exists())
        assertTrue(File(root,".prepared-v2").isFile)
        assertFalse(root.walkTopDown().any{it.name.endsWith(".tmp")})
    }
}
