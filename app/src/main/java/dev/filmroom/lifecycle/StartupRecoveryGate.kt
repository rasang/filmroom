package dev.filmroom.lifecycle

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Process-owned gate: the janitor can run only before any editor worker is admitted. */
class StartupRecoveryGate(private val scope:CoroutineScope,private val recover:suspend ()->PrivateWorkRecovery.Report) {
    sealed interface State {
        data object Running:State
        data class Failed(val report:PrivateWorkRecovery.Report):State
        data class Ready(val report:PrivateWorkRecovery.Report):State
    }
    private val mutable=MutableStateFlow<State>(State.Running)
    val state=mutable.asStateFlow()
    init { launchRecovery() }
    private fun launchRecovery(){
        scope.launch {
            val report=try{recover()}catch(_:Throwable){PrivateWorkRecovery.Report(0,1)}
            mutable.value=if(report.failed==0)State.Ready(report)else State.Failed(report)
        }
    }
    /** A failed attempt terminates explicitly. It never releases workers or spins retrying. */
    suspend fun awaitAttempt():State=state.first{it !is State.Running}
    /** Only the user can retry a failed pre-startup attempt; Ready is irreversible. */
    @Synchronized fun retry():Boolean {
        if(mutable.value !is State.Failed)return false
        mutable.value=State.Running
        launchRecovery()
        return true
    }
}
