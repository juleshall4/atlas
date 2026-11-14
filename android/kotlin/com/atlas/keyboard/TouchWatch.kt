package com.atlas.keyboard

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Reports each touch that starts outside this invisible window, which is every touch. Outside a text field the
 * firmware sends the touchpad to the app as screen touches, so this is how the keyboard learns of them. Android
 * only tells this window that a touch started; the touch itself stays with the app.
 */
class TouchWatch(private val context: Context, private val touched: (MotionEvent) -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val watcher = object : View(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) touched(event)
                return false
            }
        }
        val params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            // Below the obscuring limit, so touches on apps under this pixel are never blocked.
            alpha = .5f
            // Without "InputMethod" in the title, so the firmware never sends the touchpad here.
            title = "AtlasTouchWatch"
        }
        try {
            windows.addView(watcher, params)
            view = watcher
        } catch (error: RuntimeException) {
            Log.w("Atlas", "Could not add the touch watch", error)
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        try {
            windows.removeViewImmediate(current)
        } catch (error: RuntimeException) {
            Log.w("Atlas", "Could not remove the touch watch", error)
        }
    }
}
