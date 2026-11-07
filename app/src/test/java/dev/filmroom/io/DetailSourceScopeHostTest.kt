package dev.filmroom.io

import dev.filmroom.engine.NativeMemorySnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Real filesystem aliases/links; no Android device or platform-path assumption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class DetailSourceScopeHostTest {
    private val memory={NativeMemorySnapshot(8L*1024*1024*1024,0,false)}
    private fun expectRejected(block:()->Unit) {
        try { block();fail("Untrusted path must be rejected") } catch(_:IllegalArgumentException) {}
    }
    @Test fun trustedAndroidStyleRootAliasIsAcceptedAndCanonicalSourceAlsoWorks()=runBlocking {
        val outer=Files.createTempDirectory("detail-root-alias").toFile().canonicalFile
        try {
            val real=File(outer,"data/dev.filmroom").apply{mkdirs()}
            val aliasParent=File(outer,"user/0");aliasParent.parentFile!!.mkdirs()
            Files.createSymbolicLink(aliasParent.toPath(),File(outer,"data").toPath())
            val alias=File(aliasParent,"dev.filmroom")
            val files=File(alias,"cache/photo-sources").apply{mkdirs()}
            val original=File(files,"source.heic").apply{writeText("original bytes remain")}
            val linear=File(files,"linear.rgb")
            val samples=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(-.5f).putFloat(2f).putFloat(.25f).putFloat(.5f).putFloat(1f).putFloat(.75f).array()
            linear.writeBytes(samples)
            // This is the shipped guard's deterministic failure, despite app ownership.
            assertNotEquals(original.absoluteFile,original.canonicalFile)
            assertFalse(original.toPath().startsWith(real.toPath()))
            val factory=DetailSourceFactory(alias,memory)
            assertEquals(original.canonicalFile,factory.checkedSourceFile(original))
            assertEquals(original.canonicalFile,factory.checkedSourceFile(original.canonicalFile))
            val source=PhotoSource(original,"source","image/heif",2,1,original.length(),linearFile=linear)
            val detail=factory.prepare(source)
            val grid=File(real,"cache/detail-preview/${detail.relativePath}")
            assertArrayEquals(samples,grid.readBytes())
            assertEquals(2,detail.width);assertEquals(1,detail.height)
            detail.close();assertTrue(grid.exists());source.clearLinearPreview();source.detailGridCache.awaitRetirements();assertFalse(grid.exists());assertTrue(original.exists());assertTrue(linear.exists())
        }finally{outer.deleteRecursively()}
    }
    @Test fun rejectsLexicalTraversalOutsideRootAndUntrustedEquivalentAlias() {
        val outer=Files.createTempDirectory("detail-scope").toFile().canonicalFile
        try {
            val root=File(outer,"root").apply{mkdir()};val source=File(root,"source").apply{writeText("safe")}
            val factory=DetailSourceFactory(root,memory)
            expectRejected{factory.checkedSourceFile(File(root,"./source"))}
            val child=File(root,"child").apply{mkdir()}
            expectRejected{factory.checkedSourceFile(File(child,"../source"))}
            val outside=File(outer,"outside").apply{writeText("outside")}
            expectRejected{factory.checkedSourceFile(outside)}
            val otherAlias=File(outer,"untrusted-alias")
            Files.createSymbolicLink(otherAlias.toPath(),root.toPath())
            expectRejected{factory.checkedSourceFile(File(otherAlias,"source"))}
            assertEquals("safe",source.readText());assertEquals("outside",outside.readText())
        }finally{outer.deleteRecursively()}
    }
    @Test fun rejectsNestedDirectoryAndFileSymlinksEvenWhenTheirTargetsStayInsideRoot() {
        val root=Files.createTempDirectory("detail-nested-links").toFile().canonicalFile
        try {
            val directory=File(root,"real").apply{mkdir()};val source=File(directory,"source").apply{writeText("safe")}
            val linkedDirectory=File(root,"linked");Files.createSymbolicLink(linkedDirectory.toPath(),directory.toPath())
            val linkedFile=File(root,"link-file");Files.createSymbolicLink(linkedFile.toPath(),source.toPath())
            val factory=DetailSourceFactory(root,memory)
            expectRejected{factory.checkedSourceFile(File(linkedDirectory,"source"))}
            expectRejected{factory.checkedSourceFile(linkedFile)}
            assertEquals("safe",source.readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun rejectsHardlinksToInsideAndOutsideFiles() {
        val outer=Files.createTempDirectory("detail-hardlinks").toFile().canonicalFile
        try {
            val root=File(outer,"root").apply{mkdir()};val factory=DetailSourceFactory(root,memory)
            val outside=File(outer,"outside").apply{writeText("outside")}
            val inside=File(root,"inside").apply{writeText("inside")}
            val outsideLink=File(root,"outside-link");Files.createLink(outsideLink.toPath(),outside.toPath())
            val insideLink=File(root,"inside-link");Files.createLink(insideLink.toPath(),inside.toPath())
            expectRejected{factory.checkedSourceFile(outsideLink)}
            expectRejected{factory.checkedSourceFile(insideLink)}
            expectRejected{factory.checkedSourceFile(inside)}
            assertEquals("outside",outside.readText());assertEquals("inside",inside.readText())
        }finally{outer.deleteRecursively()}
    }
    @Test fun cacheDescendantSymlinkCannotRedirectPreparationOrCleanup()=runBlocking {
        val outer=Files.createTempDirectory("detail-cache-link").toFile().canonicalFile
        try {
            val root=File(outer,"root").apply{mkdir()};val outside=File(outer,"outside").apply{mkdir()}
            val marker=File(outside,"keep").apply{writeText("keep")}
            val raw=File(root,"source").apply{writeText("raw")};val linear=File(root,"linear").apply{writeBytes(ByteArray(12))}
            Files.createSymbolicLink(File(root,"cache").toPath(),outside.toPath())
            val factory=DetailSourceFactory(root,memory)
            expectRejected{factory.cleanupOrphans()}
            try { factory.prepare(PhotoSource(raw,"source","image/heif",1,1,3,linearFile=linear));fail() } catch(_:IllegalArgumentException) {}
            assertEquals(listOf("keep"),outside.list()!!.toList());assertEquals("keep",marker.readText())
        }finally{outer.deleteRecursively()}
    }
}
