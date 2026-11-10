package com.davenn.carbuddy

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.Window
import java.util.Calendar
import java.util.Locale

/**
 * carBuddy Clock: the one carBuddy screen meant to stay up while driving.
 *
 * It is declared distraction-optimised in the manifest, which is the Android
 * flag for screens that meet in-car safety guidelines. Everything here is chosen
 * to keep that true:
 *  - glanceable: one large time, the date, nothing else to read;
 *  - still: it changes once a minute; no seconds, animation or video;
 *  - nothing to operate: no buttons, no text entry, no lists;
 *  - a dead end: it cannot open the mirror or the streaming browser, which
 *    stay parked-only in [MainActivity].
 *
 * Styled after davenn.com: its near-black, DM Mono, and the davenn/dotcom mark.
 */
class ClockActivity : Activity() {

    private lateinit var clock: ClockView

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = clock.update()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        clock = ClockView(this)
        setContentView(clock)
    }

    override fun onStart() {
        super.onStart()
        // TIME_TICK arrives once a minute, on the minute, only while registered.
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        clock.update()
    }

    override fun onStop() {
        unregisterReceiver(tick)
        super.onStop()
    }
}

/**
 * Draws the clock, scaled to the view's height so it suits both a 1920×720 car
 * screen and a phone. Left-aligned, leaving the ultrawide screen's right side
 * empty and calm.
 */
private class ClockView(context: Context) : View(context) {

    private val medium: Typeface = context.resources.getFont(R.font.dm_mono_medium)
    private val regular: Typeface = context.resources.getFont(R.font.dm_mono_regular)

    private val timePaint = text(medium, WHITE)
    private val suffixPaint = text(medium, DIM)
    private val datePaint = text(regular, MUTED)
    private val brandPaint = text(medium, WHITE)
    private val brandDimPaint = text(medium, DIM)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK; strokeCap = Paint.Cap.ROUND }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; strokeCap = Paint.Cap.ROUND }

    private var time = ""
    private var suffix = ""
    private var date = ""
    private var hourFraction = 0f

    init {
        setBackgroundColor(PAGE)
    }

    fun update() {
        val now = Calendar.getInstance()
        val locale = Locale.getDefault()
        val is24 = DateFormat.is24HourFormat(context)
        time = DateFormat.format(DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm"), now)
            .toString()
            // "9:41 AM" in some locales: keep the number here, the marker below.
            .replace(Regex("\\s*[^0-9:.\\s]+\\.?\\s*"), "")
        suffix = if (is24) "" else DateFormat.format("a", now).toString().lowercase(locale)
        date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now).toString()
        hourFraction = now.get(Calendar.MINUTE) / 60f
        contentDescription = "$time $suffix, $date"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return

        // Height drives everything; on a tall phone screen, width limits it.
        val unit = minOf(h, w * 0.45f)
        val left = maxOf(w * 0.07f, unit * 0.12f)

        timePaint.textSize = unit * 0.44f
        suffixPaint.textSize = unit * 0.09f
        datePaint.textSize = unit * 0.075f
        brandPaint.textSize = unit * 0.05f
        brandDimPaint.textSize = brandPaint.textSize

        val timeBaseline = h * 0.56f
        canvas.drawText(time, left, timeBaseline, timePaint)
        val timeWidth = timePaint.measureText(time)
        if (suffix.isNotEmpty()) {
            canvas.drawText(suffix, left + timeWidth + unit * 0.04f, timeBaseline, suffixPaint)
        }

        // A hairline under the time that fills as the hour passes.
        val lineY = timeBaseline + unit * 0.08f
        val stroke = maxOf(2f, unit * 0.008f)
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        canvas.drawLine(left, lineY, left + timeWidth, lineY, trackPaint)
        if (hourFraction > 0f) {
            canvas.drawLine(left, lineY, left + timeWidth * hourFraction, lineY, progressPaint)
        }

        canvas.drawText(date, left, lineY + unit * 0.14f, datePaint)

        // davenn.com's mark: "davenn" bright, "dotcom" dimmed, as in its nav bar.
        val brandY = h - unit * 0.09f
        canvas.drawText("davenn", left, brandY, brandPaint)
        canvas.drawText("dotcom", left + brandPaint.measureText("davenn"), brandY, brandDimPaint)
    }

    private fun text(face: Typeface, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        this.color = color
        // DM Mono reads best slightly tightened, as davenn.com sets it.
        letterSpacing = -0.02f
    }

    private companion object {
        // davenn.com's nav colours: near-black page, white, and white at 35%.
        val PAGE = Color.rgb(0x11, 0x11, 0x10)
        val WHITE = Color.argb(235, 255, 255, 255)
        val DIM = Color.argb(89, 255, 255, 255)
        val MUTED = Color.argb(140, 255, 255, 255)
        val TRACK = Color.argb(28, 255, 255, 255)
    }
}
