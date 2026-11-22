package com.atlas.debug

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Atlas Debug only. tools/debug.sh copies this file in and adds it to the manifest, and the release
 * checks fail if it is ever in a release build. Android creates a provider when the app process starts,
 * so it logs without any call from Atlas code.
 *
 * Writes a line each minute, and when the battery or the screen changes, to
 * /sdcard/Android/data/com.atlas.debug/files/battery.csv. Get it with `bash tools/battery-log.sh`.
 */
class BatteryLog : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var file: File
    private lateinit var battery: BatteryManager
    private lateinit var power: PowerManager
    private var last: Intent? = null
    private var lastKey = ""

    private val tick = object : Runnable {
        override fun run() {
            write("tick")
            main.postDelayed(this, 60_000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    last = intent
                    // Voltage changes every few seconds, so only log a new level, status or plug.
                    val key = "${intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}," +
                        "${intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)}," +
                        "${intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)}"
                    if (key != lastKey) {
                        lastKey = key
                        write("battery")
                    }
                }
                Intent.ACTION_SCREEN_ON -> write("screen_on")
                Intent.ACTION_SCREEN_OFF -> write("screen_off")
            }
        }
    }

    override fun onCreate(): Boolean {
        val context = context ?: return false
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, "battery.csv")
        battery = context.getSystemService(BatteryManager::class.java)
        power = context.getSystemService(PowerManager::class.java)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        last = context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        write("start")
        main.postDelayed(tick, 60_000)
        return true
    }

    private fun write(event: String) {
        try {
            val intent = last
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            }
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
            val charge = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) / 1000
            val cpu = Process.getElapsedCpuTime() / 1000.0
            val uptime = SystemClock.elapsedRealtime() / 1000
            val time = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substringBefore('.')

            if (file.length() > 5_000_000) file.renameTo(File(file.parentFile, "battery.old.csv"))
            if (!file.exists()) {
                file.writeText("time,uptime_s,event,level_pct,status,plugged,current_ma,charge_mah,voltage_mv,temp_c,screen,atlas_cpu_s\n")
            }
            val pct = if (level >= 0) level * 100 / scale else -1
            val screen = if (power.isInteractive) "on" else "off"
            file.appendText("$time,$uptime,$event,$pct,$status,$plugged,$current,$charge,$voltage,$temp,$screen,$cpu\n")
        } catch (_: Exception) {
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?) = null
    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
