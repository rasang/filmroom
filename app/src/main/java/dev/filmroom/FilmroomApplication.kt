package dev.filmroom

import android.app.Application
import dev.filmroom.lifecycle.PrivateWorkRecovery
import dev.filmroom.lifecycle.StartupRecoveryGate
import dev.filmroom.diagnostics.*
import kotlinx.coroutines.*

open class FilmroomApplication:Application() {
    private val processScope by lazy { CoroutineScope(SupervisorJob()+Dispatchers.IO) }
    lateinit var recoveryGate:StartupRecoveryGate; private set
    val recoveryReport:PrivateWorkRecovery.Report? get()=when(val s=recoveryGate.state.value){
        is StartupRecoveryGate.State.Ready->s.report
        is StartupRecoveryGate.State.Failed->s.report
        StartupRecoveryGate.State.Running->null
    }
    override fun onCreate(){
        super.onCreate()
        // The diagnostic helper shares app-private storage, not main-process lifecycle.
        // This MUST precede recovery, Diagnostics, locale/preferences, and queue startup.
        if(dev.filmroom.engine.GpuProbeProcess.isWorker(packageName, applicationProcessName()))return
        // Keep the Application base context system-owned; only UI/scoped lookup contexts override locale.
        dev.filmroom.i18n.L10n.initialize(this)
        // Never wait for filesystem work on the main thread. The gate is process-owned,
        // not Activity-owned, and stays closed on failure. No live worker can race it.
        recoveryGate=StartupRecoveryGate(processScope){
            val gpuIdle = if(BuildConfig.PERF_BUILD) dev.filmroom.engine.GpuHelperProcessLock.awaitStartup(this@FilmroomApplication) else null
            if(BuildConfig.PERF_BUILD && gpuIdle==null)return@StartupRecoveryGate PrivateWorkRecovery.Report(0,1)
            try {
            Diagnostics.initialize(this@FilmroomApplication)
            val cleanup=Diagnostics.begin(Operation.CLEANUP)
            val report=runCatching{
                val gpu=if(BuildConfig.PERF_BUILD)dev.filmroom.engine.GpuRenderRecovery.recover(cacheDir)else PrivateWorkRecovery.Report(0,0)
                val normal=PrivateWorkRecovery.recover(cacheDir,filesDir,getSharedPreferences("local-session",0).getString("source",null),getSharedPreferences("local-session",0).getString("rawSource",null))
                PrivateWorkRecovery.Report(gpu.removed+normal.removed,gpu.failed+normal.failed)
            }
                .getOrElse{PrivateWorkRecovery.Report(0,1)}
            Diagnostics.event(cleanup,Stage.CLEANUP,SafeInfo(cleanupRemoved=report.removed.toLong(),cleanupUnresolved=report.failed.toLong()),if(report.failed>0)ErrorCode.CLEANUP_UNCERTAIN else null)
            Diagnostics.end(cleanup,if(report.failed==0)Outcome.SUCCESS else Outcome.FAILED)
            report
            } finally { gpuIdle?.close() }
        }
    }
    protected open fun applicationProcessName():String? = Application.getProcessName()
}
