package dev.filmroom.engine

import android.content.*
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/** One bounded decode-overlap opportunity, never a timer/polling loop. A short
 * import gets no speculative work. This does not schedule QUICK or REFINED. */
internal class IdlePreviewPrewarmer(
    context:Context,
    private val scope:CoroutineScope,
    private val prepare:suspend (EditorState,()->Boolean)->String,
    private val cancelNative:()->Unit,
    private val interactiveIdle:()->Boolean,
) : AutoCloseable {
    private val app=context.applicationContext
    private val power=runCatching{app.getSystemService(Context.POWER_SERVICE) as? PowerManager}.getOrNull()
    private var job:Job?=null
    private val epoch=AtomicLong()
    @Volatile private var foreground=false
    @Volatile private var closed=false
    private var selected:EditorState?=null
    private var listening=false
    private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){stop()}}
    private val memory=object:ComponentCallbacks2 {
        override fun onConfigurationChanged(configuration:Configuration)=Unit
        override fun onLowMemory(){stop()}
        override fun onTrimMemory(level:Int){if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)stop()}
    }
    private val thermal=if(Build.VERSION.SDK_INT>=29)PowerManager.OnThermalStatusChangedListener { status -> if(status!=PowerManager.THERMAL_STATUS_NONE)stop() }else null
    fun setForeground(value:Boolean){foreground=value;if(!value)stop()else if(selected!=null)schedule()}
    fun begin(parameters:EditorState){end();if(closed)return;selected=parameters;if(listen())schedule()else {selected=null;IdlePrewarmMetrics.skipped.incrementAndGet()}}
    fun end(){selected=null;stop();unlisten()}
    private fun allowed()=runCatching {
        val manager=power ?: return@runCatching false
        IdlePrewarmPolicy.eligible(foreground,manager.isPowerSaveMode,manager.isInteractive,
            if(Build.VERSION.SDK_INT>=29)manager.currentThermalStatus else 0,
            ExportWorkAdmissions.prewarmBlocked,!interactiveIdle(),closed)
    }.getOrDefault(false)
    private fun schedule(){
        stop()
        val parameters=selected ?: return
        val token=epoch.get()
        job=scope.launch {
            // Event-relative quiet gap: only a decode still in flight after two
            // normal 64 ms input deadlines is eligible, not a fixed idle timer.
            delay(DECODE_QUIET_MS)
            if(token!=epoch.get() || !allowed()){IdlePrewarmMetrics.skipped.incrementAndGet();return@launch}
            IdlePrewarmMetrics.attempts.incrementAndGet()
            try {
                val outcome=prepare(parameters){token==epoch.get() && allowed()}
                if(outcome!="published" && outcome!="already_warm")IdlePrewarmMetrics.skipped.incrementAndGet()
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){IdlePrewarmMetrics.skipped.incrementAndGet()}
            catch(_:LinkageError){IdlePrewarmMetrics.skipped.incrementAndGet()}
            catch(_:OutOfMemoryError){IdlePrewarmMetrics.skipped.incrementAndGet()}
        }
    }
    fun stop(){epoch.incrementAndGet();runCatching{cancelNative()};job?.cancel();job=null}
    private fun listen():Boolean {
        if(listening)return true
        listening=true
        return try {
        app.registerComponentCallbacks(memory)
        val filter=IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED).apply{addAction(Intent.ACTION_SCREEN_OFF)}
        if(Build.VERSION.SDK_INT>=33)app.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)else app.registerReceiver(receiver,filter)
        if(Build.VERSION.SDK_INT>=29 && thermal!=null)power?.addThermalStatusListener(thermal)
        true
        } catch(_:Exception){unlisten();false}
    }
    private fun unlisten(){
        if(!listening)return
        listening=false
        runCatching{app.unregisterComponentCallbacks(memory)}
        runCatching{app.unregisterReceiver(receiver)}
        val listener=thermal
        if(Build.VERSION.SDK_INT>=29 && listener!=null)runCatching{power?.removeThermalStatusListener(listener)}
    }
    override fun close(){closed=true;end()}
    companion object {const val DECODE_QUIET_MS=128L}
}

/** Process-local counts, no user/source/parameter identifiers. Native metrics
 * separately count fronts published, subsequently consumed and retired unused. */
internal object IdlePrewarmMetrics {
    val attempts=AtomicLong()
    val skipped=AtomicLong()
}

internal object IdlePrewarmPolicy {
    fun eligible(foreground:Boolean,powerSave:Boolean,interactive:Boolean,thermal:Int,exportActive:Boolean,interactiveBusy:Boolean,closed:Boolean)=
        foreground && !powerSave && interactive && thermal==0 && !exportActive && !interactiveBusy && !closed
}
