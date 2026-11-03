package dev.filmroom

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import dev.filmroom.engine.*
import dev.filmroom.model.EditorState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class CombinedGpuSettingsHostTest {
    private val app:android.app.Application get()=RuntimeEnvironment.getApplication()
    private val store get()=app.getSharedPreferences("exact-optimization-test",0)
    private val preferences get()=OptimizationPreferences(app)
    private val gpuKeys=listOf("gpuDiffusionExperimental","gpuScannerExperimental","gpuOpticalExperimental","gpuExposureExperimental","gpuDirExperimental","gpuScienceExperimental")
    private val photo=EditorState(brightness=1.25f,grain=.6f,useGpu=true).json()
    @Before fun prepare(){store.edit().clear().commit();app.getSharedPreferences("local-session",0).edit().clear().putString("source","photo").putString("parameters",photo).commit()}
    @After fun clear(){store.edit().clear().commit();app.getSharedPreferences("local-session",0).edit().clear().commit()}
    private fun find(view:View,tag:String):View?{if(view.tag==tag)return view;if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i),tag)?.let{return it};return null}
    private fun withSettings(work:(ViewGroup)->Unit){val controller=Robolectric.buildActivity(SettingsActivity::class.java).setup();try{work(controller.get().findViewById(android.R.id.content))}finally{controller.pause().stop().destroy()}}
    private fun assertChoice(enabled:Boolean){val value=preferences.snapshot();assertEquals(enabled,value.gpuRenderingEnabled);assertEquals(enabled,value.allScientificStagesEnabled());gpuKeys.forEach{assertEquals(enabled,store.all[it])}}
    private fun assertPhoto(){val saved=app.getSharedPreferences("local-session",0);assertEquals("photo",saved.getString("source",null));assertEquals(photo,saved.getString("parameters",null));assertEquals(42,JSONObject(photo).getInt("seed"))}
    @Test fun singleToggleControlsWholePipelineAndPreservesInternalExactOptions(){
        preferences.save(OptimizationSnapshot(exportMask=85,cpuComparison=true,previewBrightnessReuse=true,gpuResourceReuse=true))
        withSettings{root->
            val toggle=find(root,"gpu-rendering-toggle")
            if(!BuildConfig.PERF_BUILD){assertNull(toggle);return@withSettings}
            toggle as CheckBox;assertFalse(toggle.isChecked)
            toggle.performClick();assertTrue(toggle.isChecked);assertChoice(true);assertFalse(preferences.snapshot().cpuComparison)
            assertEquals(85,preferences.snapshot().exportMask);assertTrue(preferences.snapshot().previewBrightnessReuse)
            toggle.performClick();assertFalse(toggle.isChecked);assertChoice(false);assertEquals(85,preferences.snapshot().exportMask)
            for(tag in listOf("optimization-gpu-all","optimization-cpu","optimization-gpu-diffusion","optimization-gpu-science","gpu-probe-arm"))assertNull(find(root,tag))
            assertPhoto()
        }
    }
    @Test fun allLegacyCombinationsMigrateConservativelyAndOnlyOnce(){
        if(!BuildConfig.PERF_BUILD)return
        for(bits in 0..63){
            store.edit().clear().putInt("exportMask",85).apply{gpuKeys.forEachIndexed{i,key->putBoolean(key,bits and (1 shl i)!=0)}}.commit()
            assertChoice(bits==63);assertEquals(85,preferences.snapshot().exportMask)
            val normalized=store.all
            repeat(3){assertChoice(bits==63);assertEquals(normalized,store.all)}
        }
        store.edit().clear().putBoolean("cpuComparison",true).apply{gpuKeys.forEach{putBoolean(it,true)}}.commit();assertChoice(false)
        store.edit().clear().putString("gpuRenderingEnabled","true").apply{gpuKeys.forEach{putBoolean(it,true)}}.commit();assertChoice(false)
        assertPhoto()
    }
    @Test fun togglePublishesOnlyCoherentSnapshotsAndSurvivesRecreation(){
        if(!BuildConfig.PERF_BUILD)return
        preferences.snapshot();val observed=mutableListOf<OptimizationSnapshot>()
        withSettings{root->preferences.observeChoices{observed.add(preferences.snapshot())}.use{(find(root,"gpu-rendering-toggle") as CheckBox).performClick()}}
        assertTrue(observed.isNotEmpty());for(value in observed){assertTrue(value.gpuRenderingEnabled && value.allScientificStagesEnabled());assertFalse(value.cpuComparison)}
        withSettings{root->assertTrue((find(root,"gpu-rendering-toggle") as CheckBox).isChecked)}
        assertChoice(true);assertPhoto()
    }
    @Test fun restoredOldDensityPreferenceCannotBypassMasterInPreviewDetailOrExport(){
        if(!BuildConfig.PERF_BUILD)return
        for(enabled in listOf(false,true)){
            preferences.setGpuRendering(enabled);val value=preferences.snapshot()
            for(mode in listOf("preview","export")){
                val restored=JSONObject(photo).put("mode",mode).put("gpuResourceReuse",true)
                value.applyTo(restored);assertFalse(restored.getBoolean("useGpu"));assertFalse(restored.getBoolean("gpuResourceReuse"))
                val choice=ScientificGpuSelection.from(value,true,mode);assertEquals(enabled,choice.enabled)
                val detail=choice.applyTo(JSONObject(photo));assertFalse(detail.getBoolean("useGpu"));assertFalse(detail.getBoolean("gpuResourceReuse"))
                for(key in gpuKeys)assertEquals(enabled,detail.optBoolean(key,false))
                assertEquals(42,detail.getInt("seed"));assertEquals(JSONObject(photo).getDouble("grainAmount"),detail.getDouble("grainAmount"),0.0)
            }
        }
        assertPhoto()
    }
}
