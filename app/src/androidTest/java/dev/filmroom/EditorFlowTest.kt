package dev.filmroom

import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorFlowTest {
    private fun await(timeout:Long=90000, condition:()->Boolean) {
        val deadline=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<deadline){if(condition())return;Thread.sleep(100)}
        fail("等待本地编辑操作超时")
    }
    @Test fun offlineNativeEditorHistoryComparisonAndRecreation(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS)
        assertFalse(packageInfo.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"暗房测试_${System.nanoTime()}.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.IS_PENDING,1)})!!
        val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        for(y in 0 until 64)for(x in 0 until 96)bitmap.setPixel(x,y,Color.rgb(x*255/95,y*255/63,(x+y)*255/158))
        context.contentResolver.openOutputStream(uri)!!.use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
        context.getSharedPreferences("local-session",0).edit().clear().commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:EditorViewModel
                scenario.onActivity{vm=ViewModelProvider(it)[EditorViewModel::class.java];vm.importPhoto(uri)}
                await { vm.state.value.photo!=null && !vm.state.value.updating && vm.state.value.busy==null }
                val initial=vm.state.value
                assertNull("必须包含真实本地引擎",initial.engineUnavailable)
                assertNotNull("必须真实生成效果，不能返回占位",initial.rendered)
                assertEquals(96,initial.original!!.width);assertEquals(64,initial.original.height)
                val default=initial.parameters
                scenario.onActivity{vm.parameters(default.copy(brightness=0.5f))}
                await{!vm.state.value.updating}
                assertTrue(vm.state.value.canUndo)
                scenario.onActivity{vm.undo()};assertEquals(default,vm.state.value.parameters)
                assertTrue(vm.state.value.canRedo)
                scenario.onActivity{vm.redo()};assertEquals(0.5f,vm.state.value.parameters.brightness)
                await{!vm.state.value.updating}
                scenario.onActivity{vm.compare()};assertTrue(vm.state.value.compare)
                scenario.onActivity{vm.compare()};assertFalse(vm.state.value.compare)
                val before=vm
                scenario.recreate()
                scenario.onActivity{vm=ViewModelProvider(it)[EditorViewModel::class.java]}
                assertSame("旋转保留同一个编辑状态",before,vm)
                assertEquals(0.5f,vm.state.value.parameters.brightness)
                assertNotNull(vm.state.value.rendered)
                val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                context.getExternalFilesDir(null)?.resolve("editor-smoke.png")?.outputStream()?.use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
                screenshot.recycle()
            }
        } finally { context.contentResolver.delete(uri,null,null); context.getSharedPreferences("local-session",0).edit().clear().commit() }
    }
}
