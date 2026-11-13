package com.vandam.ink

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import com.atlas.audio.Calls
import com.atlas.audio.Engine
import com.atlas.audio.SpeakerService
import com.atlas.engine.EmojiPages
import com.atlas.engine.EnglishSpelling
import com.atlas.engine.SwipeTyping
import com.atlas.engine.TypingSettings
import com.atlas.keyboard.AppShortcuts
import com.atlas.keyboard.AtlasIme
import com.atlas.keyboard.BackService
import com.atlas.keyboard.HapticPreset
import com.atlas.keyboard.Haptics
import com.atlas.keyboard.KeyCapture
import com.atlas.keyboard.Trace
import com.atlas.keyboard.TouchpadPreview
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal fun createAppNativeAdapters(activity: MainActivity): Map<String, NativeAdapter> =
    mapOf("atlas" to AtlasAdapter(activity))

private class AtlasAdapter(private val activity: MainActivity) : NativeAdapter, Application.ActivityLifecycleCallbacks {
    private val typing by lazy { SwipeTyping.get(activity) }
    private var stopWatching: (() -> Unit)? = null
    private val previewController = lazy { TouchpadPreview(activity) }
    private var watchingRequested = false
    private var previewRequested = false
    // Adapters can be created before the live activity's window gains focus.
    private var visible = true
    private var transferring = false
    private val keyCapture = lazy { KeyCapture(activity, ::keyPressed) }
    private var keyCaptureRequested = false
    private val keyboardSettings = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = publishState()
    }

    private fun publishState() = activity.emit(JSONObject().put("type", "atlas-state").toString())

    init {
        activity.application.registerActivityLifecycleCallbacks(this)
        // Enabling and choosing the keyboard happen outside the app; the screens follow as they change.
        for (setting in listOf(Settings.Secure.ENABLED_INPUT_METHODS, Settings.Secure.DEFAULT_INPUT_METHOD,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
            activity.contentResolver.registerContentObserver(Settings.Secure.getUriFor(setting), false, keyboardSettings)
        // Settings load without waiting for the swipe engine; tell them when it finishes loading.
        typing.whenReady { _, _, _ -> publishState() }
        Engine.init(activity)
        // An update stops the speaker service; opening the app brings back whatever was on.
        SpeakerService.sync(activity)
    }

    private fun syncPreview() {
        if (visible && watchingRequested) {
            if (stopWatching == null) stopWatching = Trace.listen { activity.emit(it.put("type", "atlas-trace").toString()) }
        } else {
            stopWatching?.invoke()
            stopWatching = null
        }
        if (visible && previewRequested) previewController.value.start()
        else if (previewController.isInitialized()) previewController.value.stop()
        if (visible && keyCaptureRequested) keyCapture.value.start()
        else if (keyCapture.isInitialized()) keyCapture.value.stop()
    }

    override fun onActivityResumed(target: Activity) {
        if (target !== activity) return
        visible = true
        syncPreview()
        publishState()
    }

    override fun onActivityPaused(target: Activity) {
        if (target !== activity) return
        visible = false
        syncPreview()
    }

    override fun onActivityStopped(target: Activity) = onActivityPaused(target)

    override fun onActivityDestroyed(target: Activity) {
        if (target !== activity) return
        visible = false
        syncPreview()
        watchingRequested = false
        previewRequested = false
        keyCaptureRequested = false
        activity.contentResolver.unregisterContentObserver(keyboardSettings)
        activity.application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(target: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(target: Activity) = Unit
    override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit

    override fun execute(requestId: Long, operation: String, payload: String, complete: NativeResultHandler) {
        try {
            val data = JSONObject(payload)
            // Asynchronous engine operations complete through their own outcome callback.
            val result: String? = when (operation) {
                "state" -> state().toString()
                "set" -> {
                    set(data.getString("key"), data.get("value"))
                    "null"
                }
                "addWord" -> {
                    typing.addWord(data.getString("word")) { error -> operationResult(error, complete) }
                    null
                }
                "removeWord" -> {
                    typing.removeWord(data.getString("word")) { error -> operationResult(error, complete) }
                    null
                }
                "blockWord", "unblockWord" -> {
                    typing.setWordBlocked(data.getString("word"), operation == "blockWord") { error -> operationResult(error, complete) }
                    null
                }
                "clearWords" -> {
                    typing.clearDictionary { error -> operationResult(error, complete) }
                    null
                }
                "transferLearning" -> {
                    check(!transferring) { "A learning transfer is already open." }
                    val mode = data.getString("mode")
                    require(mode == "import" || mode == "export")
                    transferring = true
                    try {
                        LearningTransferActivity.start(activity, mode == "export") { transferred, error ->
                            transferring = false
                            if (error == null) complete(NativeResult.Success(transferred.toString()))
                            else operationResult(error, complete)
                        }
                    } catch (error: Exception) {
                        transferring = false
                        throw error
                    }
                    null
                }
                "copy" -> {
                    val text = data.getString("text")
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text, text))
                    "null"
                }
                "notesSeen" -> {
                    appPreferences().edit().putString(SEEN_NOTES, installedVersion()).apply()
                    "null"
                }
                "enable" -> {
                    activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    "null"
                }
                "choose" -> {
                    activity.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    "null"
                }
                "overlay" -> {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")))
                    "null"
                }
                "scrollAssistant" -> {
                    activity.startActivity(Intent().setClassName("com.agui.settings",
                        "com.agui.settings.touchpad.ScrollAssistantActivity"))
                    "null"
                }
                "backService" -> {
                    // The page for a single service is closed to apps, so the list opens with Atlas highlighted.
                    val service = ComponentName(activity, BackService::class.java).flattenToString()
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .putExtra(":settings:fragment_args_key", service)
                        .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", service) }))
                    "null"
                }
                "watch" -> {
                    watchingRequested = true
                    syncPreview()
                    "null"
                }
                "unwatch" -> {
                    watchingRequested = false
                    syncPreview()
                    "null"
                }
                "preview" -> {
                    previewRequested = true
                    syncPreview()
                    "null"
                }
                "unpreview" -> {
                    previewRequested = false
                    if (previewController.isInitialized()) previewController.value.stop()
                    "null"
                }
                "captureKey" -> {
                    if (keyCapture.isInitialized()) keyCapture.value.stop()
                    keyCaptureRequested = true
                    syncPreview()
                    "null"
                }
                "releaseKey" -> {
                    keyCaptureRequested = false
                    syncPreview()
                    "null"
                }
                "shortcut" -> {
                    val letter = data.getString("letter").single()
                    AppShortcuts.app(activity, letter, if (data.isNull("app")) null else data.getString("app"))
                    // Fn is only taken from other apps while a letter has an app.
                    BackService.filterKeys()
                    "null"
                }
                "apps" -> {
                    // Drawing the icons takes a moment the first time, so it stays off the main thread.
                    Thread {
                        try {
                            val apps = JSONArray()
                            for (app in AppShortcuts.apps(activity))
                                apps.put(JSONObject().put("app", app.packageName).put("name", app.name).put("icon", icon(app) ?: JSONObject.NULL))
                            complete(NativeResult.Success(apps.toString()))
                        } catch (error: Exception) {
                            operationResult(error.message ?: "Could not list the apps", complete)
                        }
                    }.start()
                    null
                }
                else -> throw IllegalArgumentException("Unknown Atlas operation: $operation")
            }
            if (result != null) complete(NativeResult.Success(result))
        } catch (error: Exception) {
            complete(NativeResult.Failure(NativeErrorKind.UNEXPECTED, error.message ?: "Atlas request failed", false))
        }
    }

    override fun cancel(requestId: Long) = Unit

    private fun keyPressed(code: Int) {
        val message = JSONObject().put("type", "atlas-key")
        message.put("key", when (code) {
            KeyEvent.KEYCODE_SPACE -> " "
            KeyEvent.KEYCODE_DEL -> "Backspace"
            else -> ('A' + (code - KeyEvent.KEYCODE_A)).toString()
        })
        activity.emit(message.toString())
    }

    private fun operationResult(error: String?, complete: NativeResultHandler) {
        complete(if (error == null) NativeResult.Success("null")
            else NativeResult.Failure(NativeErrorKind.UNEXPECTED, error, true))
    }

    private fun state(): JSONObject {
        val keyboard = ComponentName(activity, AtlasIme::class.java)
        val enabled = activity.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.component == keyboard }
        val selected = Settings.Secure.getString(activity.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString) == keyboard
        val shortcuts = JSONArray()
        for ((letter, app) in AppShortcuts.shortcuts(activity))
            shortcuts.put(JSONObject().put("letter", letter.toString()).put("app", app).put("name", AppShortcuts.name(activity, app)))
        val learned = typing.learnedWords()
        val words = JSONArray()
        for ((index, word) in learned.words.withIndex())
            words.put(JSONObject().put("word", word).put("description", learned.swipeStatuses[index]))
        return JSONObject()
            .put("version", installedVersion())
            .put("whatsNew", whatsNew())
            .put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("enabled", enabled)
            .put("selected", selected)
            .put("overlay", Settings.canDrawOverlays(activity))
            .put("scrollAssistant", scrollAssistant())
            .put("backService", BackService.enabled(activity))
            .put("learning", typing.learningEnabled())
            .put("tapKeys", TypingSettings.tapKeys(activity))
            .put("autoCapitalise", TypingSettings.autoCapitalise(activity))
            .put("autoPunctuation", TypingSettings.autoPunctuation(activity))
            .put("suggestionBar", TypingSettings.suggestionBar(activity))
            .put("cursorControl", TypingSettings.cursorControl(activity))
            .put("touchLight", TypingSettings.touchLight(activity))
            .put("swipeTyping", TypingSettings.swipeTyping(activity))
            .put("liftOff", TypingSettings.liftOffGrace(activity))
            .put("liftOffMillis", TypingSettings.liftOffDurationMillis(activity))
            .put("haptics", Haptics.enabled(activity))
            .put("hapticPreset", Haptics.preset(activity).id)
            .put("spelling", if (TypingSettings.spelling(activity) == EnglishSpelling.Variant.AMERICAN) "american" else "british")
            .put("skinTone", TypingSettings.skinTone(activity).id)
            .put("words", words)
            .put("blockedWords", JSONArray(typing.blockedWords().toList()))
            .put("openApps", AppShortcuts.enabled(activity))
            .put("shortcutAnimation", AppShortcuts.animation(activity))
            .put("actions", TypingSettings.actions(activity))
            .put("shortcuts", shortcuts)
            .put("music", Engine.music(activity))
            .put("calls", Engine.calls(activity))
            .put("callsCommand", if (Calls.allowed(activity)) JSONObject.NULL else Calls.command(activity))
    }

    /** The Titan's Scroll assistant switch powers the keyboard touchpad, and it ships off. Phones without one pass. */
    private fun scrollAssistant(): Boolean {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        if (get.invoke(null, "ro.agui.touchpad_function", "") != "yes") return true
        return get.invoke(null, "persist.sys.scroll_assistant", "0") == "1"
    }

    private val managedFiles by lazy { InkManagedFiles(activity) }

    /** The app's icon as the home screen shows it, saved as a picture the settings can show. Null when it cannot be drawn. */
    private fun icon(app: AppShortcuts.App): String? = try {
        val id = "app-icon-" + MessageDigest.getInstance("SHA-1").digest(app.activity.name.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        val directory = File(activity.filesDir, "ink-files/app-icons").apply { mkdirs() }
        val file = File(directory, "$id.png")
        val updated = activity.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        if (!file.isFile || file.lastModified() < updated) {
            val drawable = app.activity.loadIcon(activity.packageManager)
            val bitmap = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, ICON_PIXELS, ICON_PIXELS)
            drawable.draw(Canvas(bitmap))
            val partial = File(directory, "$id.png.partial")
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(partial.renameTo(file)) { "Icon could not be saved" }
        }
        if (managedFiles.open(id) == null) managedFiles.adopt(file, "image/png", id = id, width = ICON_PIXELS, height = ICON_PIXELS)
        "ink-file://$id"
    } catch (error: Exception) {
        null
    }

    private fun installedVersion(): String = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""

    /** Release notes show once after each update. A fresh install has nothing new, so it starts as seen. */
    private fun whatsNew(): Boolean {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val preferences = appPreferences()
        val seen = preferences.getString(SEEN_NOTES, null) ?: if (info.firstInstallTime == info.lastUpdateTime) {
            preferences.edit().putString(SEEN_NOTES, info.versionName).apply()
            info.versionName
        } else null
        return seen != info.versionName
    }

    private fun appPreferences() = activity.getSharedPreferences("app", Context.MODE_PRIVATE)

    private fun set(key: String, value: Any) {
        when (key) {
            "learning" -> typing.learningEnabled(value as Boolean)
            "tapKeys" -> TypingSettings.tapKeys(activity, value as String)
            "autoCapitalise" -> TypingSettings.autoCapitalise(activity, value as Boolean)
            "autoPunctuation" -> TypingSettings.autoPunctuation(activity, value as Boolean)
            "suggestionBar" -> TypingSettings.suggestionBar(activity, value as Boolean)
            "cursorControl" -> TypingSettings.cursorControl(activity, value as Boolean)
            "touchLight" -> TypingSettings.touchLight(activity, value as Boolean)
            "swipeTyping" -> TypingSettings.swipeTyping(activity, value as Boolean)
            "liftOff" -> TypingSettings.liftOffGrace(activity, value as Boolean)
            "liftOffMillis" -> TypingSettings.liftOffDurationMillis(activity, (value as Number).toInt())
            "haptics" -> Haptics.select(activity, Haptics.preset(activity), value as Boolean)
            "hapticPreset" -> Haptics.select(activity, HapticPreset.of(value as String), Haptics.enabled(activity))
            "openApps" -> {
                AppShortcuts.enabled(activity, value as Boolean)
                BackService.filterKeys()
            }
            "shortcutAnimation" -> AppShortcuts.animation(activity, value as Boolean)
            "actions" -> TypingSettings.actions(activity, value as Boolean)
            "spelling" -> TypingSettings.spelling(activity,
                if (value == "american") EnglishSpelling.Variant.AMERICAN else EnglishSpelling.Variant.BRITISH)
            "skinTone" -> TypingSettings.skinTone(activity, EmojiPages.Tone.of(value as String))
            "music" -> {
                Engine.setMusic(value as Boolean)
                SpeakerService.sync(activity)
            }
            "calls" -> {
                Engine.setCalls(value as Boolean)
                SpeakerService.sync(activity)
            }
            else -> throw IllegalArgumentException("Unknown setting: $key")
        }
    }
}

private const val SEEN_NOTES = "seen_release_notes"
/** Icons show at 50 points; this keeps them sharp at the Titan's density. */
private const val ICON_PIXELS = 144
