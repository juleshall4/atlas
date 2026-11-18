package com.atlas.keyboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Sends back the way the system does. Android shows the keyboard with its own back handler in front of
 * the app's, so a back swipe reaches Atlas instead of the app. A back key from a keyboard skips the
 * stage where apps on Android's newer back handling listen for it, so only a system back reaches them.
 *
 * With app shortcuts on and a hyper key set, it also sees keys before apps do, so Hyper and a letter open an app anywhere.
 * It asks for keys only then, because each key waits for Atlas while it does. It cannot read the screen.
 *
 * In a text field it also keeps the touchpad with the keyboard. A notification banner sits above the
 * keyboard's own overlay and takes swipes that start under it, but not those under this service's overlay.
 */
class BackService : AccessibilityService() {
    private val hyper = HyperKey()
    private val dictationKeys = HashSet<Int>()
    private val overlay by lazy {
        TouchpadOverlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) { touchpad?.invoke(it) == true }
    }

    override fun onServiceConnected() {
        current = this
        filterKeys()
        showTouchpad()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (current === this) current = null
        overlay.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (current === this) current = null
        overlay.hide()
        super.onDestroy()
    }

    private fun showTouchpad() {
        if (touchpad != null) overlay.show() else overlay.hide()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (KeyCapture.waiting?.invoke(event) == true) return true
        val code = AppShortcuts.activeKey(this)
        if (code == AppShortcuts.NONE || event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (event.action == KeyEvent.ACTION_UP && dictationKeys.remove(event.keyCode)) return false
        if (event.action == KeyEvent.ACTION_DOWN && (AtlasIme.listening || event.keyCode in dictationKeys)) {
            // The keyboard stops listening on key down and needs the matching release too.
            dictationKeys += event.keyCode
            return false
        }
        val action = hyper.key(code, event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, event.eventTime)
        if (action == HyperKey.Action.Pass) return false
        // The keyboard still learns of the press, so the finger on the touchpad does not type.
        AtlasIme.keyTaken(event)
        if (action == HyperKey.Action.Twice) AtlasIme.dictate()
        if (action is HyperKey.Action.Open) try {
            AppShortcuts.open(this, action.letter)
        } catch (error: RuntimeException) {
            Log.w("Atlas", "Could not open the app for ${action.letter}", error)
        }
        return true
    }

    private fun filterKeys() {
        val info = serviceInfo ?: return
        val wanted = KeyCapture.waiting != null || AppShortcuts.activeKey(this) != AppShortcuts.NONE
        val flags = if (wanted) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        if (flags == info.flags) return
        hyper.reset()
        dictationKeys.clear()
        info.flags = flags
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var current: BackService? = null
        private var touchpad: ((MotionEvent) -> Boolean)? = null

        /** Sends a system back, or returns false when the service is off. */
        fun back(): Boolean = current?.performGlobalAction(GLOBAL_ACTION_BACK) == true

        /** Sends touchpad events to [handler] while it is set, or stops when it is null. Call on the main thread. */
        fun catchTouchpad(handler: ((MotionEvent) -> Boolean)?) {
            touchpad = handler
            current?.showTouchpad()
        }

        /** Asks for keys only while shortcuts are on with a hyper key, or a screen waits for a key. */
        fun filterKeys() {
            current?.filterKeys()
        }

        fun enabled(context: Context): Boolean {
            val component = ComponentName(context, BackService::class.java)
            val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return services.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
