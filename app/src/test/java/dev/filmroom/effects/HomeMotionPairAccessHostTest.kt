package dev.filmroom.effects

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class HomePairTestActivity:ComponentActivity() {
    var image:String?="content://media/external/images/media/1"
    var enabled=true
    var refreshes=0
    internal val access=HomeMotionPairAccess(this,{image},{enabled}){refreshes++}
    override fun onCreate(state:Bundle?) { super.onCreate(state);access.restore(state) }
    override fun onSaveInstanceState(out:Bundle) {access.save(out);super.onSaveInstanceState(out)}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class HomeMotionPairAccessHostTest {
    private fun reset(){shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(*EffectMotionPairAccess.requiredPermissions())}
    private fun answer(a:HomePairTestActivity,code:Int,granted:Boolean=false){
        val permissions=EffectMotionPairAccess.requiredPermissions()
        assertTrue(a.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent()
            .putExtra("androidx.activity.result.contract.extra.PERMISSIONS",permissions)
            .putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",IntArray(permissions.size){if(granted)PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED})))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun deniedRequestRefreshesOnceAndDoesNotNag(){
        reset();Robolectric.buildActivity(HomePairTestActivity::class.java).setup().use{host->
            val a=host.get();a.access.requestIfNeeded(true);val launch=shadowOf(a).nextStartedActivityForResult
            assertNotNull(launch);assertTrue(a.access.busy)
            a.access.requestIfNeeded(true);assertNull(shadowOf(a).nextStartedActivityForResult)
            answer(a,launch.requestCode);assertFalse(a.access.busy);assertEquals(1,a.refreshes)
            a.access.requestIfNeeded(true);assertNull(shadowOf(a).nextStartedActivityForResult)
        }
    }
    @Test fun stalePermissionOrDisabledEffectDoesNotRefresh(){
        for(disable in listOf(false,true)){
            reset();Robolectric.buildActivity(HomePairTestActivity::class.java).setup().use{host->
                val a=host.get();a.access.requestIfNeeded(true);val launch=shadowOf(a).nextStartedActivityForResult
                if(disable)a.enabled=false else a.image="content://media/external/images/media/2"
                answer(a,launch.requestCode,true);assertFalse(a.access.busy);assertEquals(0,a.refreshes)
            }
        }
    }
    @Test fun pendingPermissionRestoresAcrossActivityRecreation(){
        reset();val first=Robolectric.buildActivity(HomePairTestActivity::class.java).setup()
        first.get().access.requestIfNeeded(true);val launch=shadowOf(first.get()).nextStartedActivityForResult
        val saved=Bundle();first.pause().saveInstanceState(saved).stop().destroy()
        Robolectric.buildActivity(HomePairTestActivity::class.java).create(saved).start().resume().visible().use{host->
            assertTrue(host.get().access.busy);answer(host.get(),launch.requestCode)
            assertFalse(host.get().access.busy);assertEquals(1,host.get().refreshes)
        }
    }
    @Test fun noMissingPairAndDisabledEffectDoNotRequestPermission(){
        reset();Robolectric.buildActivity(HomePairTestActivity::class.java).setup().use{host->
            val a=host.get();a.access.requestIfNeeded(false);a.enabled=false;a.access.requestIfNeeded(true)
            assertNull(shadowOf(a).nextStartedActivityForResult);assertFalse(a.access.requireCompanion(false))
        }
    }
    @Test fun restoredPickerResultForOldPhotoIsIgnored(){
        reset();val first=Robolectric.buildActivity(HomePairTestActivity::class.java).setup()
        assertTrue(first.get().access.requireCompanion(true))
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle() // Dialog button dispatches its listener through Handler.
        val launch=shadowOf(first.get()).nextStartedActivityForResult
        assertNotNull(launch);assertTrue(first.get().access.busy)
        val saved=Bundle();first.pause().saveInstanceState(saved).stop().destroy()
        Robolectric.buildActivity(HomePairTestActivity::class.java).create(saved).start().resume().visible().use{host->
            val a=host.get();a.image="content://media/external/images/media/new"
            assertTrue(a.access.busy)
            assertTrue(a.activityResultRegistry.dispatchResult(launch.requestCode,Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://not-read/video"))))
            shadowOf(Looper.getMainLooper()).idle();assertFalse(a.access.busy);assertEquals(0,a.refreshes)
        }
    }
}
