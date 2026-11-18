package com.atlas.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * The bars that show Atlas listening: each bar is how loud one moment was, newest on the right. While Atlas
 * writes, the bars settle into a slow wave.
 */
class Waveform(context: Context) : View(context) {
    private val levels = FloatArray(BARS)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var writingSince = 0L

    var writing = false
        set(value) {
            if (value && !field) writingSince = SystemClock.uptimeMillis()
            field = value
            invalidate()
        }

    fun colour(ink: Int) {
        bar.color = ink
        invalidate()
    }

    /** Adds the loudness of the newest moment, from 0 to 1. */
    fun add(level: Float) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        // Loudness is heard on a log scale: -50 dB is silence and -10 dB is a raised voice.
        levels[BARS - 1] = ((20 * log10(level.coerceAtLeast(1e-6f)) + 50) / 40).coerceIn(0f, 1f)
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        writing = false
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val width = BAR_DP * density
        val gap = GAP_DP * density
        val total = BARS * width + (BARS - 1) * gap
        val tallest = height * 0.6f
        var left = (this.width - total) / 2
        val middle = height / 2f
        val phase = (SystemClock.uptimeMillis() - writingSince) / 1000.0 * 2 * PI
        for (index in 0 until BARS) {
            val level = if (writing) (0.15 + 0.1 * sin(phase - index * 0.4)).toFloat()
                else levels[index]
            val half = (width + level * (tallest - width)) / 2
            rect.set(left, middle - half, left + width, middle + half)
            canvas.drawRoundRect(rect, width / 2, width / 2, bar)
            left += width + gap
        }
        if (writing) postInvalidateOnAnimation()
    }

    companion object {
        private const val BARS = 24
        private const val BAR_DP = 3f
        private const val GAP_DP = 3f
    }
}
