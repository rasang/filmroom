package dev.filmroom

import android.os.Looper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.lifecycle.PrivateWorkRecovery
import dev.filmroom.lifecycle.StartupRecoveryGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=FilmroomApplication::class)
class StartupRecoveryHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    @Test fun startupRemainsResponsiveBlocksImportAndShowsRetryableFailure()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<FilmroomApplication>()
        // Settle the real empty test-directory startup before injecting a controlled gate.
        withTimeout(3000){app.recoveryGate.awaitAttempt()}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val first=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>();val attempts=AtomicInteger()
        val original=app.recoveryGate
        val gate=StartupRecoveryGate(scope){if(attempts.incrementAndGet()==1){first.await();PrivateWorkRecovery.Report(0,2)}else{second.await();PrivateWorkRecovery.Report(2,0)}}
        val field=FilmroomApplication::class.java.getDeclaredField("recoveryGate").apply{isAccessible=true};field.set(app,gate)
        // AndroidX retains its first Application in a static factory. Robolectric creates
        // multiple Applications in one JVM: isolate that singleton, not production gating.
        val factoryField=androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply{isAccessible=true}
        val previousFactory=factoryField.get(null);factoryField.set(null,null)
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(c.get()) as EditorViewModel
                assertSame(app,c.get().application)
                assertSame(app,vm.getApplication<android.app.Application>())
                assertSame(gate,EditorViewModel::class.java.getDeclaredField("recoveryGate").apply{isAccessible=true}.get(vm))
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(vm.state.value.startupReady);assertNotNull(vm.state.value.busy)
                vm.importPhoto(Uri.parse("content://must-not-open/untrusted"));vm.retry()
                assertNull(vm.state.value.photo);assertFalse(vm.state.value.updating)
                first.complete(Unit);withTimeout(2000){gate.awaitAttempt()};shadowOf(Looper.getMainLooper()).idle()
                assertTrue(vm.state.value.recoveryFailed);assertTrue(vm.state.value.message!!.contains("2 项"))
                vm.clearMessage();assertNotNull(vm.state.value.message)
                vm.retryRecovery();vm.retryRecovery();assertTrue(gate.state.value is StartupRecoveryGate.State.Running)
                shadowOf(Looper.getMainLooper()).idle();assertFalse(vm.state.value.startupReady)
                second.complete(Unit);withTimeout(2000){gate.awaitAttempt()}
                withTimeout(3000){while(!vm.state.value.startupReady){shadowOf(Looper.getMainLooper()).idle();delay(10)}}
                assertTrue(vm.state.value.startupReady);assertFalse(vm.state.value.recoveryFailed)
                assertEquals(2,attempts.get());assertFalse(gate.retry())
            }
        }finally{factoryField.set(null,previousFactory);field.set(app,original);scope.cancel()}
    }
}
