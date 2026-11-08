package dev.filmroom.update

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.*

/** Elapsed time survives process restart; boot marker prevents interpreting it across reboots. */
class UpdateCooldown(private val prefs:android.content.SharedPreferences) {
    @Synchronized fun claim(wall:Long,elapsed:Long,boot:Int):Boolean {
        if(prefs.contains("wall")) {
            val oldWall=prefs.getLong("wall",0);val oldElapsed=prefs.getLong("elapsed",0)
            val sameBoot=boot>=0&&prefs.getInt("boot",-1)==boot&&elapsed>=oldElapsed
            if(sameBoot) {if(elapsed-oldElapsed<INTERVAL)return false}
            else {
                if(wall<oldWall){
                    // Clock moved backwards across boot: conservatively start a fresh cooldown.
                    prefs.edit().putLong("wall",wall).putLong("elapsed",elapsed).putInt("boot",boot).commit()
                    return false
                }
                if(wall-oldWall<INTERVAL)return false
            }
        }
        return prefs.edit().putLong("wall",wall).putLong("elapsed",elapsed).putInt("boot",boot).commit()
    }
    companion object {const val INTERVAL=12*60*60*1000L}
}

/** Only validated successful responses are cached; age starts at the network request. */
class ManualUpdateCache(private val prefs:android.content.SharedPreferences) {
    fun read(wall:Long,elapsed:Long,boot:Int):UpdateManifest? {
        val body=prefs.getString("body",null) ?: return null
        val result=runCatching{UpdateManifest.parse(body)}.getOrNull() ?: return null
        val oldWall=prefs.getLong("wall",0);val oldElapsed=prefs.getLong("elapsed",0)
        val sameBoot=boot>=0&&prefs.getInt("boot",-1)==boot&&elapsed>=oldElapsed
        if(sameBoot){if(elapsed-oldElapsed>=INTERVAL)return null}
        else {
            if(wall<oldWall){
                // A reboot plus backwards wall clock cannot bypass the window or pin it forever.
                prefs.edit().putLong("wall",wall).putLong("elapsed",elapsed).putInt("boot",boot).commit()
            } else if(wall-oldWall>=INTERVAL)return null
        }
        return result
    }
    fun write(value:UpdateManifest,wall:Long,elapsed:Long,boot:Int){
        val links=org.json.JSONArray()
        value.downloads.forEach{links.put(org.json.JSONObject().put("name",it.name).put("url",it.url))}
        val body=org.json.JSONObject().put("versionCode",value.versionCode).put("versionName",value.versionName)
            .put("releaseNotes",value.releaseNotes).put("downloads",links).toString()
        prefs.edit().putString("body",body).putLong("wall",wall).putLong("elapsed",elapsed).putInt("boot",boot).commit()
    }
    companion object {const val INTERVAL=10*60*1000L}
}

/** Application-only scope: joining from another screen never retains or cancels an Activity. */
object UpdateChecks {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var delivered:java.lang.ref.WeakReference<UpdateManifest>?=null
    @Synchronized fun claimResult(result:UpdateManifest):Boolean {
        if(delivered?.get()===result)return false
        delivered=java.lang.ref.WeakReference(result);return true
    }
    private var startupConsidered=false
    private var startupTask:Deferred<UpdateManifest>?=null
    private var running:Deferred<UpdateManifest>?=null
    @Volatile private var manualParticipant=false
    @Synchronized fun request(context:Context,automatic:Boolean,fetch:()->UpdateManifest):Deferred<UpdateManifest>? {
        if(automatic){
            // A recreated owner joins this startup's same task, including a completed pending result.
            if(startupConsidered)return startupTask
            startupConsidered=true
        }
        running?.takeIf{it.isActive}?.let{
            if(automatic)startupTask=it else manualParticipant=true
            return it
        }
        val app=context.applicationContext
        manualParticipant=!automatic
        val deferred=scope.async(start=CoroutineStart.LAZY){
            val boot=runCatching{Settings.Global.getInt(app.contentResolver,Settings.Global.BOOT_COUNT)}.getOrDefault(-1)
            val wall=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
            val manualCache=ManualUpdateCache(app.getSharedPreferences("update_manual_success",0))
            if(!automatic)runCatching{manualCache.read(wall,elapsed,boot)}.getOrNull()?.let{return@async it}
            if(automatic&&!UpdateCooldown(app.getSharedPreferences("update_auto_cooldown",0)).claim(wall,elapsed,boot))throw CancellationException("Update cooldown")
            // Failure never creates a manual cache entry; automatic failures still consume 12 hours.
            val result=withTimeout(22000){runInterruptible{fetch()}}
            if(manualParticipant)runCatching{manualCache.write(result,wall,elapsed,boot)}
            result
        }
        running=deferred;if(automatic)startupTask=deferred
        deferred.start();return deferred
    }
}
