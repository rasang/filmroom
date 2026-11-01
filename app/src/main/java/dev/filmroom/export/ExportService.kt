package dev.filmroom.export

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.filmroom.ExportQueueActivity
import kotlinx.coroutines.*

/** 用户点导出/继续才启动；不使用 START_STICKY，也不注册开机或定时重启。 */
class ExportService:Service() {
    private var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var worker:Job?=null
    private var stopping=false
    private var session=0L
    private var latestStartId=0
    private var wakeLock:android.os.PowerManager.WakeLock?=null
    private var wakeRenewal:Job?=null
    private lateinit var queue:ExportQueue
    internal var queueFactory:()->ExportQueue={ExportQueue.get(this)}
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_89e2a57fd12b),NotificationManager.IMPORTANCE_LOW).apply{description=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_13a6efbdb2f3)})
        // 必须先展示通知，再做磁盘恢复/初始化。
        startForeground(NOTIFICATION,notification(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_22db9e335155)),foregroundType(Build.VERSION.SDK_INT))
        wakeLock=(getSystemService(POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"filmroom:export").apply{setReferenceCounted(false)}
        renewWakeLock()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_PAUSE){pauseAndStop();return START_NOT_STICKY}
        if(stopping || !scope.isActive) {
            stopping=false;session++
            scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            startForeground(NOTIFICATION,notification(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_22db9e335155)),foregroundType(Build.VERSION.SDK_INT))
            renewWakeLock()
            worker=null
        }
        latestStartId=startId
        if(worker?.isActive!=true)startWorker()
        return START_NOT_STICKY
    }
    private fun renewWakeLock() {
        wakeRenewal?.cancel()
        wakeRenewal=scope.launch(Dispatchers.Main.immediate) {while(isActive){wakeLock?.acquire(10*60*1000L);delay(5*60*1000L)}}
    }
    private fun startWorker() {
        val ownSession=session
        val servicedStartId=latestStartId
        worker=scope.launch {
            var drained=false
            var blocked=false
            try {
                queue=queueFactory()
                when(queue.runPending {job->getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(job.progress.compactText,job.id))}) {
                    ExportRunOutcome.DRAINED->drained=true
                    ExportRunOutcome.BLOCKED_STARTUP_RECOVERY->blocked=true
                }
            }
            catch(c:CancellationException){throw c}
            catch(_:Exception){/* runOne 的 finally 会留下可手动恢复的状态。 */}
            finally {withContext(NonCancellable+Dispatchers.Main){
                if(ownSession!=session)return@withContext
                val newerReadyRequest=latestStartId!=servicedStartId && ::queue.isInitialized && queue.startupRecoveryReady
                if(!stopping && ((!blocked && (latestStartId!=servicedStartId || drained && queue.hasPendingWorkOrIntakes)) || blocked && newerReadyRequest))startWorker()
                else {stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(if(blocked)latestStartId else servicedStartId)}
            }}
        }
    }
    private fun pauseAndStop() {
        stopping=true;session++
        val pause=if(::queue.isInitialized)queue.requestPause()else {ExportQueue.announcePause();null}
        // 限时回调先立即停止前台状态，不在主线程等待原子日志或 native 退出。
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        scope.cancel()
        if(::queue.isInitialized)scope.launch(NonCancellable+Dispatchers.IO){
            if(pause!=null)runCatching{queue.applyPause(pause)}
        }
    }
    override fun onTimeout(startId:Int,fgsType:Int) {pauseAndStop()}
    override fun onDestroy(){
        session++;stopping=true
        scope.cancel();wakeRenewal?.cancel()
        wakeLock?.let{if(it.isHeld)it.release()};wakeLock=null
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    private fun notification(text:String,jobId:String?=null):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,ExportQueueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("export_job_id",jobId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,1,Intent(this,ExportService::class.java).setAction(ACTION_PAUSE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7faeabb91a25)).setContentText(text.replace('\n',' '))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8d12fc0d4eb2),pause).build()).build()
    }
    companion object {
        const val CHANNEL="photo-export"
        const val NOTIFICATION=4101
        const val ACTION_PAUSE="dev.filmroom.export.PAUSE"
        /** Metadata-only refresh; never starts/stops the service or touches queue jobs. */
        fun refreshExistingChannel(context:android.content.Context){
            val manager=context.getSystemService(NotificationManager::class.java)
            val channel=manager.getNotificationChannel(CHANNEL) ?: return
            channel.name=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_89e2a57fd12b)
            channel.description=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_13a6efbdb2f3)
            manager.createNotificationChannel(channel)
        }
        fun foregroundType(api:Int)=if(api>=35)ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
