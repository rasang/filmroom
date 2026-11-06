package dev.filmroom

import android.app.Application
import dev.filmroom.lifecycle.PrivateWorkRecovery
import dev.filmroom.lifecycle.StartupRecoveryGate
import dev.filmroom.diagnostics.*
import kotlinx.coroutines.*

open class FilmroomApplication:Application() {
    private val processScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var recoveryGate:StartupRecoveryGate; private set
    val recoveryReport:PrivateWorkRecovery.Report? get()=when(val s=recoveryGate.state.value){
        is StartupRecoveryGate.State.Ready->s.report
        is StartupRecoveryGate.State.Failed->s.report
        StartupRecoveryGate.State.Running->null
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN and memory pressure are best-effort boundaries, not shutdown
        // guarantees. The bounded timer also covers idle foreground diagnostics.
        processScope.launch { Diagnostics.flush() }
    }

    override fun onCreate(){
        super.onCreate()
        // Keep the Application base context system-owned; only UI/scoped lookup contexts override locale.
        dev.filmroom.i18n.L10n.initialize(this)
        // Never wait for filesystem work on the main thread. The gate is process-owned,
        // not Activity-owned, and stays closed on failure. No live worker can race it.
        recoveryGate=StartupRecoveryGate(processScope){
            Diagnostics.initialize(this@FilmroomApplication)
            val cleanup=Diagnostics.begin(Operation.CLEANUP)
            val report=runCatching{PrivateWorkRecovery.recover(cacheDir,filesDir,getSharedPreferences("local-session",0).getString("source",null),getSharedPreferences("local-session",0).getString("rawSource",null))}
                .getOrElse{PrivateWorkRecovery.Report(0,1)}
            Diagnostics.event(cleanup,Stage.CLEANUP,SafeInfo(cleanupRemoved=report.removed.toLong(),cleanupUnresolved=report.failed.toLong()),if(report.failed>0)ErrorCode.CLEANUP_UNCERTAIN else null)
            Diagnostics.end(cleanup,if(report.failed==0)Outcome.SUCCESS else Outcome.FAILED)
            report
        }
    }
}
