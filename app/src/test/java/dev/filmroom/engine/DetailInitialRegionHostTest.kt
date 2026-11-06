package dev.filmroom.engine

import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class DetailInitialRegionHostTest {
    private class PlanOnly:DetailNativeApi {
        val requests=mutableListOf<JSONObject>()
        override fun nativeCapabilities()=error("unused")
        override fun nativePlan(requestJson:String):String {requests+=JSONObject(requestJson);throw UnsatisfiedLinkError("stop after request")}
        override fun nativeCreate(canonicalPrivateAppRoot:String)=error("unused")
        override fun nativeBeginOperation(sessionId:Long)=error("unused")
        override fun nativeBuild(sessionId:Long,operationId:Long,sourceRelative:String,requestJson:String)=error("unused")
        override fun nativeTile(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer)=error("unused")
        override fun nativeCancel(sessionId:Long,operationId:Long)=error("unused")
        override fun nativeReleaseOperation(sessionId:Long,operationId:Long)=error("unused")
        override fun nativeDestroy(sessionId:Long)=error("unused")
    }
    private suspend fun fixture(test:suspend (OwnedDetailProvider,PhotoSource,PlanOnly)->Unit) {
        val root=Files.createTempDirectory("detail-initial-region").toFile().canonicalFile
        File(root,"files/engine-data").mkdirs()
        val raw=File(root,"original").apply{writeText("owned")};val linear=File(root,"linear").apply{writeBytes(ByteArray(8*4*12))}
        val photo=PhotoSource(raw,"private source","image/heif",8,4,raw.length(),linearFile=linear)
        val api=PlanOnly();val provider=OwnedDetailProvider(root,api,{NativeMemorySnapshot(8L*1024*1024*1024,0,false)})
        try {test(provider,photo,api)} finally {photo.clearLinearPreview();photo.detailGridCache.awaitRetirements();root.deleteRecursively()}
    }
    private suspend fun stopAtPlan(provider:OwnedDetailProvider,photo:PhotoSource,hint:DetailRect?) {
        try {provider.open(photo,EditorState(),initialRegion=hint);fail()}catch(e:DetailUnavailable){assertEquals("UNAVAILABLE",e.code)}
    }
    @Test fun hintIsTopLevelOnlyAndDoesNotChangeScienceOrSourceIdentity()=runBlocking {
        fixture { provider,photo,api ->
            stopAtPlan(provider,photo,null);stopAtPlan(provider,photo,DetailRect(4,1,3,2))
            val baseline=api.requests[0];val hinted=api.requests[1]
            assertFalse(baseline.has("initialRegion"));assertFalse(hinted.getJSONObject("parameters").has("initialRegion"))
            assertEquals("{\"x\":4,\"y\":1,\"w\":3,\"h\":2}",hinted.getJSONObject("initialRegion").toString())
            for(key in listOf("sourceRevision","mappingRevision","dataRevision","width","height","parameters"))assertEquals(key,baseline.get(key).toString(),hinted.get(key).toString())
        }
    }
    @Test fun invalidHintsAreRejectedBeforeNativePlanning()=runBlocking {
        fixture { provider,photo,api ->
            for(hint in listOf(DetailRect(-1,0,1,1),DetailRect(0,0,0,1),DetailRect(7,0,2,1),DetailRect(Int.MAX_VALUE,0,Int.MAX_VALUE,1))) {
                try {provider.open(photo,EditorState(),initialRegion=hint);fail()}catch(_:IllegalArgumentException){}
            }
            assertTrue(api.requests.isEmpty())
        }
    }
}
