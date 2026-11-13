package com.atlas.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the process, and so the effects, alive while music or call tuning is on. */
class SpeakerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Speaker tuning", NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Speaker tuning is on")
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Engine.init(this)
        Engine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        Engine.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tuning"

        /** Starts or stops the service to match what's turned on. */
        fun sync(context: Context) {
            val intent = Intent(context, SpeakerService::class.java)
            if (Engine.wanted(context)) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
