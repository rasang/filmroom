package dev.filmroom.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class PreviewNativeContractHostTest {
    @Test fun capabilitiesMustExplicitlyAdvertiseNewBitmapAbi() {
        assertTrue(PreviewNativeContract.supportsBitmap(JSONObject().put("nativeAbiRevision",2).put("bitmapPreview",true)))
        for(c in listOf(JSONObject(),JSONObject().put("nativeAbiRevision",1).put("bitmapPreview",true),JSONObject().put("nativeAbiRevision",2).put("bitmapPreview","true")))assertFalse(PreviewNativeContract.supportsBitmap(c))
    }
    @Test fun refinedPreviewRequiresExactSeparateCapability() {
        fun caps(abi:Any=1,edge:Any=1536)=JSONObject().put("nativeAbiRevision",2).put("bitmapPreview",true).put("refinedPreviewAbi",abi).put("refinedPreviewMaxEdge",edge)
        assertTrue(PreviewNativeContract.supportsRefinedBitmap(caps()))
        for(c in listOf(JSONObject(),caps(0),caps(2),caps("1"),caps(1,768),caps(1,"1536"),caps().put("bitmapPreview",false)))assertFalse(PreviewNativeContract.supportsRefinedBitmap(c))
    }
    @Test fun zoomRequiresExplicitCapabilityAndExactRequestedTier() {
        val c=JSONObject().put("nativeAbiRevision",2).put("bitmapPreview",true)
            .put("refinedPreviewAbi",1).put("refinedPreviewMaxEdge",1536)
        assertFalse(PreviewNativeContract.supportsZoomRefinedBitmap(c,2048))
        c.put("zoomRefinedPreviewAbi",1).put("zoomRefinedPreviewMaxEdge",3072)
        assertTrue(PreviewNativeContract.supportsZoomRefinedBitmap(c,2048))
        assertTrue(PreviewNativeContract.supportsZoomRefinedBitmap(c,3072))
        assertFalse(PreviewNativeContract.supportsZoomRefinedBitmap(c,4096))
        assertFalse(PreviewNativeContract.supportsZoomRefinedBitmap(JSONObject(c.toString()).put("zoomRefinedPreviewAbi","1"),2048))
        assertFalse(PreviewNativeContract.supportsZoomRefinedBitmap(JSONObject(c.toString()).put("refinedPreviewMaxEdge",3072),2048))
    }
    @Test fun impossibleZoomFramesAreRejectedBeforeDecodeWithoutRaisingBudget() {
        assertTrue(NativeMemoryPolicy.zoomFrameFloorFits(2048,1365,512))
        assertFalse(NativeMemoryPolicy.zoomFrameFloorFits(3072,2048,512))
        assertFalse(NativeMemoryPolicy.zoomFrameFloorFits(3072,3072,512))
        assertFalse(NativeMemoryPolicy.zoomFrameFloorFits(2048,1365,128))
        assertTrue(NativeMemoryPolicy.zoomFrameFloorFits(3072,1024,512))
        assertEquals(1536,NativeMemoryPolicy.REFINED_PREVIEW_EDGE)
    }
    @Test fun stageCacheRequiresMatchingReservationContract() {
        fun caps(abi:Any,bytes:Any)=JSONObject().put("previewStageCacheAbi",abi).put("previewStageCandidateBytesPerPixel",bytes)
        assertTrue(PreviewNativeContract.supportsStageCache(caps(3,48)))
        assertTrue(PreviewNativeContract.supportsStageCache(caps(3,48L)))
        for(c in listOf(JSONObject(),caps(2,36),caps(2,48),caps(3,36),caps(4,48),caps("3",48),caps(3,"48"),caps(3,48.0)))
            assertFalse(PreviewNativeContract.supportsStageCache(c))
    }
    @Test fun refinedOutputRequiresAccountedPlanAndConfirmedGpuWork() {
        val width=512;val height=512;val external=width.toLong()*height*16
        val full=width.toLong()*height*60
        val peak=96L*NativeMemoryPolicy.MIB+external+full+128L+192L*192*272
        val plan=JSONObject().put("abi",1).put("estimatedPeakBytes",peak).put("externalBytes",external)
            .put("fullFrameReserveBytes",full).put("globalFieldReserveBytes",128).put("coreWidth",128).put("coreHeight",128)
            .put("halo",32).put("expandedWidth",192).put("expandedHeight",192)
        val gpu=JSONObject().put("backend","wgpu").put("requested",true).put("active",true).put("numericGuardPassed",true).put("cpuFallbacks",0).put("gpuBatches",2)
        val valid=JSONObject().put("ok",true).put("outputValid",true).put("width",width).put("height",height).put("outputEncoding","srgb-rgba8")
            .put("refinedPlan",plan).put("gpu",gpu).put("estimatedPeakNativeBytes",peak).put("memoryBudgetMiB",256).put("maxPixels",1536*1536)
        assertTrue(PreviewNativeContract.refinedOutputMatches(valid,width,height,256,external))
        assertFalse(PreviewNativeContract.refinedOutputMatches(valid,width,height,256,external,2048))
        assertTrue(PreviewNativeContract.refinedOutputMatches(JSONObject(valid.toString()).put("maxPixels",2048*2048),width,height,256,external,2048))
        for((key,value) in listOf("abi" to 2,"estimatedPeakBytes" to peak-1,"externalBytes" to external-1,"coreWidth" to 0,"expandedWidth" to 193,"fullFrameReserveBytes" to full-1,"globalFieldReserveBytes" to Long.MAX_VALUE,"halo" to "32")) {
            val bad=JSONObject(valid.toString());bad.getJSONObject("refinedPlan").put(key,value)
            assertFalse("plan:$key",PreviewNativeContract.refinedOutputMatches(bad,width,height,256,external))
        }
        for((key,value) in listOf("backend" to "cpu","active" to false,"requested" to false,"numericGuardPassed" to false,"cpuFallbacks" to 1,"gpuBatches" to 0)) {
            val bad=JSONObject(valid.toString());bad.getJSONObject("gpu").put(key,value)
            assertFalse("gpu:$key",PreviewNativeContract.refinedOutputMatches(bad,width,height,256,external))
        }
        assertFalse(PreviewNativeContract.refinedOutputMatches(valid,width,height,128,external))
        assertFalse(PreviewNativeContract.refinedOutputMatches(valid,width,height,256,external+4))
        assertFalse(PreviewNativeContract.refinedOutputMatches(JSONObject(valid.toString()).put("maxPixels",1280*1280),width,height,256,external))
    }

    @Test fun beta7NativePlannerIsAcceptedAtOrdinaryAndZoomTiers() {
        for(edge in listOf(1536,2048,3072)) {
            val width=edge;val height=edge/2;val external=width.toLong()*height*16
            val full=width.toLong()*height*60
            val peak=96L*NativeMemoryPolicy.MIB+external+full+128L+192L*192*272
            val plan=JSONObject().put("abi",1).put("estimatedPeakBytes",peak).put("externalBytes",external)
                .put("fullFrameReserveBytes",full).put("globalFieldReserveBytes",128).put("coreWidth",128).put("coreHeight",128)
                .put("halo",32).put("expandedWidth",192).put("expandedHeight",192)
            val gpu=JSONObject().put("backend","wgpu").put("requested",true).put("active",true).put("numericGuardPassed",true).put("cpuFallbacks",0).put("gpuBatches",2)
            val result=JSONObject().put("ok",true).put("outputValid",true).put("width",width).put("height",height).put("outputEncoding","srgb-rgba8")
                .put("refinedPlan",plan).put("gpu",gpu).put("estimatedPeakNativeBytes",peak).put("memoryBudgetMiB",512).put("maxPixels",edge*edge)
            assertTrue("Native 272 B/pixel at $edge",PreviewNativeContract.refinedOutputMatches(result,width,height,512,external,edge))
            val stalePeak=peak-192L*192*16
            result.put("estimatedPeakNativeBytes",stalePeak);plan.put("estimatedPeakBytes",stalePeak)
            assertFalse("Reject stale 256 B/pixel at $edge",PreviewNativeContract.refinedOutputMatches(result,width,height,512,external,edge))
        }
    }

    @Test fun incompleteOrWrongShapeOutputIsNeverPublished() {
        val valid=JSONObject().put("ok",true).put("outputValid",true).put("width",512).put("height",384).put("outputEncoding","srgb-rgba8")
        assertTrue(PreviewNativeContract.outputMatches(valid,512,384))
        for((key,value) in listOf("ok" to false,"outputValid" to false,"width" to 511,"height" to "384","outputEncoding" to "linear-rgb-f32")) {
            assertFalse(PreviewNativeContract.outputMatches(JSONObject(valid.toString()).put(key,value),512,384))
        }
    }
}
