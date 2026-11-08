package dev.filmroom.effects

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises AndroidX's actual persisted request-code/key routing, not a fake URI dispatcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE, application=android.app.Application::class)
class EffectPickerRestoreHostTest {
    private class Owner : LifecycleOwner {
        private val state = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = state
        fun move(to: Lifecycle.State) { state.currentState = to }
    }
    private class Registry : ActivityResultRegistry() {
        var launchedCode = -1
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launchedCode = requestCode
        }
    }
    private fun roundTrip(sourceTarget: Boolean, registerBeforeRestore: Boolean) {
        val old = Registry()
        val owner = Owner().apply { move(Lifecycle.State.CREATED) }
        val sourceLauncher = old.register(PhotoEffectActivity.SOURCE_PICKER_KEY, owner, ActivityResultContracts.OpenDocument()) { }
        val resultLauncher = old.register(PhotoEffectActivity.RESULT_PICKER_KEY, owner, ActivityResultContracts.OpenDocument()) { }
        owner.move(Lifecycle.State.STARTED)
        (if(sourceTarget) sourceLauncher else resultLauncher).launch(arrayOf("image/*"))
        assertTrue(old.launchedCode >= 0)
        val saved = Bundle()
        old.onSaveInstanceState(saved)
        owner.move(Lifecycle.State.DESTROYED)
        val restored = Registry()
        val newOwner = Owner().apply { move(Lifecycle.State.CREATED) }
        var source = "content://photos/original"
        var result = "content://photos/exported"
        var sourceCalls = 0
        var resultCalls = 0
        fun register() {
            // Reversed construction order must not alter stable-key routing.
            restored.register(PhotoEffectActivity.RESULT_PICKER_KEY, newOwner, ActivityResultContracts.OpenDocument()) { uri ->
                if(uri != null) { result = uri.toString(); resultCalls++ }
            }
            restored.register(PhotoEffectActivity.SOURCE_PICKER_KEY, newOwner, ActivityResultContracts.OpenDocument()) { uri ->
                if(uri != null) { source = uri.toString(); sourceCalls++ }
            }
        }
        if(registerBeforeRestore) register()
        restored.onRestoreInstanceState(saved)
        if(!registerBeforeRestore) register()
        val replacement = Uri.parse("content://photos/reselected")
        // Result arriving while stopped must wait for the new lifecycle's STARTED state.
        assertTrue(restored.dispatchResult(old.launchedCode, Activity.RESULT_OK, Intent().setData(replacement)))
        assertEquals(0, sourceCalls + resultCalls)
        newOwner.move(Lifecycle.State.STARTED)
        assertEquals(if(sourceTarget) replacement.toString() else "content://photos/original", source)
        assertEquals(if(sourceTarget) "content://photos/exported" else replacement.toString(), result)
        assertEquals(if(sourceTarget) 1 else 0, sourceCalls)
        assertEquals(if(sourceTarget) 0 else 1, resultCalls)
        newOwner.move(Lifecycle.State.CREATED)
        newOwner.move(Lifecycle.State.STARTED)
        assertEquals(1, sourceCalls + resultCalls)
        newOwner.move(Lifecycle.State.DESTROYED)
    }
    @Test fun originalPickerRestoresOnlyOriginal() = roundTrip(true, false)
    @Test fun resultPickerRestoresOnlyResult() = roundTrip(false, false)
    @Test fun originalPickerRegisteredBeforeRestoreStillRoutesOriginal() = roundTrip(true, true)
    @Test fun resultPickerRegisteredBeforeRestoreStillRoutesResult() = roundTrip(false, true)
}
