package com.soloheisbeer.microwaffle400.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.soloheisbeer.microwaffle400.MainActivity
import com.soloheisbeer.microwaffle400.R
import com.soloheisbeer.microwaffle400.network.NetworkManager
import com.soloheisbeer.microwaffle400.network.StatusUpdateInterface
import com.soloheisbeer.microwaffle400.timer.MicroTimer
import com.soloheisbeer.microwaffle400.timer.MicroTimerState
import com.soloheisbeer.microwaffle400.timer.TimerStatusInterface
import com.soloheisbeer.microwaffle400.utils.MicroUtils
import org.json.JSONObject

class MicroService : Service(),
    StatusUpdateInterface,
    TimerStatusInterface {

    companion object {

        const val ACTION_TIMER_TICK = "ACTION_TIMER_TICK"
        const val ACTION_TIMER_FINISH = "ACTION_TIMER_FINISH"
        const val DATA_TIMER_TIME_LEFT = "DATA_TIMER_TIME_LEFT"

        private const val ACTION_SERVICE_INIT = "ACTION_SERVICE_INIT"
        private const val ACTION_SERVICE_STOP = "ACTION_SERVICE_STOP"
        private const val ACTION_SERVICE_START_TIMER = "ACTION_SERVICE_START_TIMER"
        private const val ACTION_SERVICE_PAUSE_TIMER = "ACTION_SERVICE_PAUSE_TIMER"
        private const val DATA_SERVICE_TIME = "DATA_TIME"

        fun startTimer(context: Context, timeInSeconds: Int) {
            val startIntent = Intent(context, MicroService::class.java)
            startIntent.putExtra(DATA_SERVICE_TIME, timeInSeconds)
            startIntent.action = ACTION_SERVICE_START_TIMER
            ContextCompat.startForegroundService(context, startIntent)
        }

        fun pauseTimer(context: Context, timeInSeconds: Int) {
            val startIntent = Intent(context, MicroService::class.java)
            startIntent.putExtra(DATA_SERVICE_TIME, timeInSeconds)
            startIntent.action = ACTION_SERVICE_PAUSE_TIMER
            ContextCompat.startForegroundService(context, startIntent)
        }

        fun stopTimerAndService(context: Context) {
            val stopIntent = Intent(context, MicroService::class.java)
            stopIntent.action = ACTION_SERVICE_STOP
            ContextCompat.startForegroundService(context, stopIntent)
        }
    }

    private val notificationID = 1001
    private val channelID = "MicroService"
    private val channelName = "$channelID channel"

    private val networkManager = NetworkManager
    private val microTimer = MicroTimer(this)

    private lateinit var notificationManager: NotificationManager

    private var state = MicroState.IDLE

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)!!
        networkManager.addStatusUpdateCallback(this)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        val tis = intent.getIntExtra(DATA_SERVICE_TIME, 0)
        startForeground(notificationID, createNotification(tis))

        when(intent.action){
            ACTION_SERVICE_START_TIMER -> start(tis)
            ACTION_SERVICE_PAUSE_TIMER -> pause()
            ACTION_SERVICE_STOP -> stop()
            else -> stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun start(tis: Int){
        //microTimer.reset()
        microTimer.set(tis)
        microTimer.start()
    }

    private fun pause(){
        microTimer.pause()
    }

    private fun stop(){
        microTimer.reset()
        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onTimerTick(timeLeftInSeconds: Int){
        notificationManager.notify(notificationID, createNotification(timeLeftInSeconds))
        Intent().also { intent ->
            intent.action = ACTION_TIMER_TICK
            intent.putExtra(DATA_TIMER_TIME_LEFT, timeLeftInSeconds)
            intent.setPackage(this.packageName)
            sendBroadcast(intent)
        }
    }

    override fun onTimerFinish(){
        stopForeground(true)
        Intent().also { intent ->
            intent.action = ACTION_TIMER_FINISH
            intent.setPackage(this.packageName)
            sendBroadcast(intent)
        }
        stopSelf()
    }

    override fun onStatusUpdate(status: JSONObject){
        val tempState = MicroUtils.intToState(status["state"] as Int, MicroState.IDLE)
        val tis = status["timeInSeconds"] as Int

        if(tempState != state) {
            state = tempState

            if (state == MicroState.RUNNING && microTimer.state != MicroTimerState.RUNNING) {
                start(tis)
            } else if (state == MicroState.PAUSE && microTimer.state != MicroTimerState.PAUSED) {
                pause()
            } else if (state == MicroState.IDLE && microTimer.state != MicroTimerState.NOT_SET) {
                stop()
            }
        }

        //Sync if too much out of sync
        if (state == MicroState.RUNNING && microTimer.state == MicroTimerState.RUNNING) {
            if (microTimer.timeInSeconds - tis > 10) {
                val diff = tis - microTimer.timeInSeconds
                microTimer.add(diff)
            }
        }
    }

    private fun createNotification(timeInSeconds: Int): Notification? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(channelID, channelName,
                NotificationManager.IMPORTANCE_LOW)

            notificationManager.createNotificationChannel(serviceChannel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, 0
        )

        return NotificationCompat.Builder(this, channelID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(MicroUtils.secondsToTimeString(this, timeInSeconds))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(getColor(R.color.colorAccent))
            .setColorized(true)
            .setSound(null)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if(microTimer.state == MicroTimerState.RUNNING) {
            microTimer.reset()
        }
        networkManager.removeStatusUpdateCallback(this)
    }
}
