package com.atlas.keyboard

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.KeyEvent

/** The app each letter opens with Fn, and how it opens. */
object AppShortcuts {
    /** No key opens a shortcut. */
    const val NONE = KeyEvent.KEYCODE_UNKNOWN
    /** The Titan 2 Elite's Fn key. The firmware sends it as 403, a code that Android has no name for. */
    const val FN = 403

    class App(val packageName: String, val name: String, val activity: ActivityInfo)

    private fun preferences(context: Context) = context.getSharedPreferences("app_shortcuts", Context.MODE_PRIVATE)

    fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)

    fun enabled(context: Context, on: Boolean) {
        preferences(context).edit().putBoolean("enabled", on).apply()
    }

    /** The app opens with Android's usual animation. Off, it shows at once. */
    fun animation(context: Context) = preferences(context).getBoolean("animation", true)

    fun animation(context: Context, on: Boolean) {
        preferences(context).edit().putBoolean("animation", on).apply()
    }

    /** Fn while Quick open is on and a letter has an app, so Fn keeps its usual job until then. */
    fun activeKey(context: Context) = if (!enabled(context) || shortcuts(context).isEmpty()) NONE else FN

    fun app(context: Context, letter: Char): String? = preferences(context).getString("letter_$letter", null)

    /** Gives the letter to the app, which loses any letter it had. Null takes the letter's app away. */
    fun app(context: Context, letter: Char, packageName: String?) {
        require(letter in 'A'..'Z')
        val earlier = shortcuts(context).filter { it.second == packageName }.map { it.first }
        preferences(context).edit().apply {
            for (old in earlier) remove("letter_$old")
            if (packageName == null) remove("letter_$letter") else putString("letter_$letter", packageName)
        }.apply()
    }

    /** Each letter with an app, in order. */
    fun shortcuts(context: Context): List<Pair<Char, String>> =
        ('A'..'Z').mapNotNull { letter -> app(context, letter)?.let { letter to it } }

    /** Apps with a launcher icon, by name. */
    fun apps(context: Context): List<App> {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { App(it.activityInfo.packageName, it.loadLabel(manager).toString(), it.activityInfo) }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }

    fun name(context: Context, packageName: String): String = try {
        val manager = context.packageManager
        manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(manager).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** Opens the letter's app. Returns false when the letter has no app or the app is gone. */
    fun open(context: Context, letter: Char): Boolean {
        val packageName = app(context, letter) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // A custom animation of none is ignored for a start from a service; only this flag skips it.
        if (!animation(context)) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        context.startActivity(intent)
        return true
    }
}
