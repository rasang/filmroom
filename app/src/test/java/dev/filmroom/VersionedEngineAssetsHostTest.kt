package dev.filmroom

import android.app.Application
import dev.filmroom.engine.VersionedEngineAssets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class VersionedEngineAssetsHostTest {
    @Test fun realBundledAssetsStayAtNativeTrustRootAndWarmPreparationDoesNotRewrite()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        VersionedEngineAssets.clearMemoryForTest()
        val root=VersionedEngineAssets.prepare(app)
        assertEquals(File(app.filesDir,"engine-data"),root)
        val file=File(root,"profile_catalog.json");val original=file.readBytes();val stamp=file.lastModified()
        repeat(20){assertEquals(root,VersionedEngineAssets.prepare(app));assertEquals(stamp,file.lastModified())}
        file.writeText("corrupt")
        assertEquals(root,VersionedEngineAssets.prepare(app))
        assertArrayEquals(original,file.readBytes())
        val modified=file.lastModified()
        file.writeBytes(original.clone().apply{this[0]=(this[0].toInt() xor 1).toByte()});file.setLastModified(modified)
        // Even same-size/same-mtime corruption is revalidated at the next process preparation.
        VersionedEngineAssets.clearMemoryForTest();VersionedEngineAssets.prepare(app)
        assertArrayEquals(original,file.readBytes())
        assertTrue(File(root,".prepared-v2").isFile)
        assertFalse(File(app.filesDir,"prepared-engine-data").exists())
        assertTrue(root.walkTopDown().none{it.name.startsWith("prepare-")})
    }
}
