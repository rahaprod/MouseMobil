package com.mousemobile.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * absolute = false: тачпад (относительное движение курсора).
 * absolute = true: экран ПК (касание = позиция курсора на удалённом экране).
 */
class TouchView(context: Context, private val absolute: Boolean) : View(context) {

    private companion object {
        const val SENS = 1.8f
        const val LONG_MS = 550L
        const val TAP_MS = 300L
        const val SCROLL_STEP = 24f
    }

    @Volatile private var frame: Bitmap? = null
    private val dst = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint().apply {
        color = Color.parseColor("#3A0000")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A2A2A")
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    }
    private val lines: Array<String> = if (absolute) {
        arrayOf("Ожидание экрана ПК…", "тап — клик, удержание — ПКМ", "2 пальца — прокрутка")
    } else {
        arrayOf("1 палец — курсор", "тап — клик", "2 пальца — прокрутка", "удержание или тап 2 пальцами — ПКМ")
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastAvgY = 0f
    private var downTime = 0L
    private var moved = false
    private var multi = false
    private var longDone = false
    private var accX = 0f
    private var accY = 0f
    private var accS = 0f

    private val longPress = Runnable {
        if (!moved && !multi) {
            longDone = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Net.send("CLICK right")
        }
    }

    fun showFrame(b: Bitmap) {
        frame = b
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val f = frame
        if (absolute && f != null) {
            val s = minOf(width.toFloat() / f.width, height.toFloat() / f.height)
            val w = f.width * s
            val h = f.height * s
            val l = (width - w) / 2f
            val t = (height - h) / 2f
            dst.set(l, t, l + w, t + h)
            c.drawBitmap(f, null, dst, bmpPaint)
        } else {
            val step = textPaint.textSize * 1.6f
            val y0 = height / 2f - step * (lines.size - 1) / 2f
            for ((i, s) in lines.withIndex()) {
                c.drawText(s, width / 2f, y0 + i * step, textPaint)
            }
        }
        c.drawRect(2f, 2f, width - 2f, height - 2f, framePaint)
    }

    private fun avgY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }

    private fun sendAbs(x: Float, y: Float) {
        val w = dst.width()
        val h = dst.height()
        if (w <= 0f || h <= 0f) return
        val nx = ((x - dst.left) / w).coerceIn(0f, 1f)
        val ny = ((y - dst.top) / h).coerceIn(0f, 1f)
        Net.send("ABS ${(nx * 10000).toInt()} ${(ny * 10000).toInt()}")
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                downTime = e.eventTime
                moved = false
                multi = false
                longDone = false
                accX = 0f
                accY = 0f
                accS = 0f
                if (absolute) sendAbs(e.x, e.y)
                postDelayed(longPress, LONG_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                removeCallbacks(longPress)
                lastAvgY = avgY(e)
                accS = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val y = avgY(e)
                    accS += y - lastAvgY
                    lastAvgY = y
                    val steps = (accS / SCROLL_STEP).toInt()
                    if (steps != 0) {
                        accS -= steps * SCROLL_STEP
                        moved = true
                        Net.send("SCROLL $steps")
                    }
                } else if (!multi) {
                    if (!moved && hypot(e.x - downX, e.y - downY) < slop) return true
                    moved = true
                    removeCallbacks(longPress)
                    if (absolute) {
                        sendAbs(e.x, e.y)
                    } else {
                        accX += (e.x - lastX) * SENS
                        accY += (e.y - lastY) * SENS
                        val ix = accX.toInt()
                        val iy = accY.toInt()
                        if (ix != 0 || iy != 0) {
                            accX -= ix
                            accY -= iy
                            Net.send("MOVE $ix $iy")
                        }
                    }
                    lastX = e.x
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!moved && !longDone && e.eventTime - downTime < TAP_MS) {
                    Net.send(if (multi) "CLICK right" else "CLICK left")
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
            }
        }
        return true
    }
}
