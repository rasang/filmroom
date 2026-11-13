package dev.filmroom.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
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

class LocationPermissionHost:ComponentActivity() {
    var fileLaunches=0
    internal val location=PhotoLocationPermission(this){fileLaunches++}
    override fun onCreate(state:Bundle?) { super.onCreate(state);location.restore(state) }
    override fun onSaveInstanceState(outState:Bundle) { location.save(outState);super.onSaveInstanceState(outState) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PhotoLocationPermissionTest {
    private fun reset() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("photo-location-permission",0).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
    private fun answer(a:LocationPermissionHost,request:Int,granted:Boolean) {
        a.activityResultRegistry.dispatchResult(request,Activity.RESULT_OK,Intent()
            .putExtra("androidx.activity.result.contract.extra.PERMISSIONS",arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
            .putExtra("androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",intArrayOf(if(granted)PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun deniedStillOpensFilesAndDoesNotNagAgain() {
        reset()
        Robolectric.buildActivity(LocationPermissionHost::class.java).setup().use { host ->
            val a=host.get();a.location.pickFiles();val request=shadowOf(a).nextStartedActivityForResult
            assertArrayEquals(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION),shadowOf(a).lastRequestedPermission.requestedPermissions)
            a.location.pickFiles();assertEquals(0,a.fileLaunches)
            answer(a,request.requestCode,false);assertEquals(1,a.fileLaunches)
            a.location.pickFiles();assertEquals(2,a.fileLaunches)
            assertNull(shadowOf(a).nextStartedActivityForResult)
        }
    }
    @Test fun grantedSkipsRequest() {
        reset();shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        Robolectric.buildActivity(LocationPermissionHost::class.java).setup().use { host ->
            host.get().location.pickFiles();assertEquals(1,host.get().fileLaunches)
            assertNull(shadowOf(host.get()).lastRequestedPermission)
        }
    }
    @Test fun pendingPermissionSurvivesRecreationAndResumesOnce() {
        reset();val first=Robolectric.buildActivity(LocationPermissionHost::class.java).setup()
        first.get().location.pickFiles();val request=shadowOf(first.get()).nextStartedActivityForResult
        val saved=Bundle();first.pause().saveInstanceState(saved).stop().destroy()
        Robolectric.buildActivity(LocationPermissionHost::class.java).create(saved).start().resume().visible().use { host ->
            assertEquals(0,host.get().fileLaunches)
            answer(host.get(),request.requestCode,false);assertEquals(1,host.get().fileLaunches)
        }
    }
}
