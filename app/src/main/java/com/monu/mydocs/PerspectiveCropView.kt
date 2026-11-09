package com.monu.mydocs

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class PerspectiveCropView(context: Context, private val bitmap: Bitmap) : View(context) {
    private val corners = arrayOf(PointF(), PointF(), PointF(), PointF())
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = -1

    init { fullImage() }

    fun fullImage() {
        corners[0] = PointF(0f, 0f)
        corners[1] = PointF(bitmap.width.toFloat(), 0f)
        corners[2] = PointF(bitmap.width.toFloat(), bitmap.height.toFloat())
        corners[3] = PointF(0f, bitmap.height.toFloat())
        invalidate()
    }

    fun autoCrop() {
        // Work on a small preview bitmap so edge analysis stays fast and memory-light.
        val sw = min(420, bitmap.width).coerceAtLeast(3)
        val sh = (bitmap.height.toFloat() * sw / bitmap.width).roundToInt().coerceIn(3, 700)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        try {
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val gray = IntArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                gray[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            }

            val rows = FloatArray(sh)
            val cols = FloatArray(sw)
            val edgeValues = FloatArray((sw - 2) * (sh - 2))
            var edgeCount = 0
            var edgeSum = 0f

            // Sobel gradients are less sensitive to small shadows/noise than the old
            // four-neighbour difference. Count strong edges by row and column.
            for (y in 1 until sh - 1) {
                for (x in 1 until sw - 1) {
                    val i = y * sw + x
                    val gx = -gray[i - sw - 1] + gray[i - sw + 1] -
                        2 * gray[i - 1] + 2 * gray[i + 1] -
                        gray[i + sw - 1] + gray[i + sw + 1]
                    val gy = -gray[i - sw - 1] - 2 * gray[i - sw] - gray[i - sw + 1] +
                        gray[i + sw - 1] + 2 * gray[i + sw] + gray[i + sw + 1]
                    val magnitude = (abs(gx) + abs(gy)) / 4f
                    edgeValues[edgeCount++] = magnitude
                    edgeSum += magnitude
                }
            }

            val mean = if (edgeCount > 0) edgeSum / edgeCount else 0f
            val threshold = max(22f, mean * 1.45f)
            for (y in 1 until sh - 1) {
                for (x in 1 until sw - 1) {
                    val i = y * sw + x
                    val gx = -gray[i - sw - 1] + gray[i - sw + 1] -
                        2 * gray[i - 1] + 2 * gray[i + 1] -
                        gray[i + sw - 1] + gray[i + sw + 1]
                    val gy = -gray[i - sw - 1] - 2 * gray[i - sw] - gray[i - sw + 1] +
                        gray[i + sw - 1] + 2 * gray[i + sw] + gray[i + sw + 1]
                    if ((abs(gx) + abs(gy)) / 4f >= threshold) {
                        rows[y]++
                        cols[x]++
                    }
                }
            }

            fun smooth(values: FloatArray, index: Int): Float {
                var total = 0f
                var count = 0
                for (offset in -3..3) {
                    val at = index + offset
                    if (at in values.indices) { total += values[at]; count++ }
                }
                return if (count == 0) 0f else total / count
            }

            fun strongest(values: FloatArray, start: Int, end: Int): Int {
                var best = start
                var score = -1f
                for (i in start until end) {
                    val candidate = smooth(values, i)
                    if (candidate > score) { score = candidate; best = i }
                }
                return best
            }

            val top = strongest(rows, 1, (sh * 0.55f).roundToInt().coerceAtLeast(2))
            val bottom = strongest(rows, (sh * 0.45f).roundToInt().coerceAtMost(sh - 2), sh - 1)
            val left = strongest(cols, 1, (sw * 0.55f).roundToInt().coerceAtLeast(2))
            val right = strongest(cols, (sw * 0.45f).roundToInt().coerceAtMost(sw - 2), sw - 1)

            // Reject implausibly small detections and retain a safe inset fallback.
            val valid = right - left > sw * 0.35f && bottom - top > sh * 0.35f
            val l = if (valid) left else (sw * 0.035f).roundToInt()
            val r = if (valid) right else (sw * 0.965f).roundToInt()
            val t = if (valid) top else (sh * 0.035f).roundToInt()
            val b = if (valid) bottom else (sh * 0.965f).roundToInt()

            val sx = bitmap.width.toFloat() / sw
            val sy = bitmap.height.toFloat() / sh
            val padX = max(1, (sw * 0.008f).roundToInt())
            val padY = max(1, (sh * 0.008f).roundToInt())
            corners[0] = PointF(max(0, l - padX) * sx, max(0, t - padY) * sy)
            corners[1] = PointF(min(sw - 1, r + padX) * sx, max(0, t - padY) * sy)
            corners[2] = PointF(min(sw - 1, r + padX) * sx, min(sh - 1, b + padY) * sy)
            corners[3] = PointF(max(0, l - padX) * sx, min(sh - 1, b + padY) * sy)
            invalidate()
        } finally {
            if (small !== bitmap && !small.isRecycled) small.recycle()
        }
    }

    fun sourcePoints() = floatArrayOf(
        corners[0].x, corners[0].y, corners[1].x, corners[1].y,
        corners[2].x, corners[2].y, corners[3].x, corners[3].y
    )

    private fun fit() {
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        rect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        fit()
        canvas.drawColor(Color.rgb(18, 22, 24))
        canvas.drawBitmap(bitmap, null, rect, paint)
        val p = FloatArray(8)
        for (i in 0..3) {
            p[i * 2] = rect.left + corners[i].x / bitmap.width * rect.width()
            p[i * 2 + 1] = rect.top + corners[i].y / bitmap.height * rect.height()
        }
        val path = Path().apply {
            moveTo(p[0], p[1])
            for (i in 1..3) lineTo(p[i * 2], p[i * 2 + 1])
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = 0x5538DCC6
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        for (i in 0..3) {
            paint.color = Color.WHITE
            canvas.drawCircle(p[i * 2], p[i * 2 + 1], 22f, paint)
            paint.color = 0xFF087F78.toInt()
            canvas.drawCircle(p[i * 2], p[i * 2 + 1], 16f, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        fit()
        fun mapX(x: Float) = (x - rect.left) / rect.width() * bitmap.width
        fun mapY(y: Float) = (y - rect.top) / rect.height() * bitmap.height
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = mapX(e.x)
                val y = mapY(e.y)
                active = (0..3).minByOrNull {
                    hypot((corners[it].x - x).toDouble(), (corners[it].y - y).toDouble())
                } ?: -1
                if (active >= 0 && hypot(
                        (corners[active].x - x).toDouble(),
                        (corners[active].y - y).toDouble()
                    ) > bitmap.width * 0.15f
                ) active = -1
                return active >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0) {
                    corners[active] = PointF(
                        mapX(e.x).coerceIn(0f, bitmap.width.toFloat()),
                        mapY(e.y).coerceIn(0f, bitmap.height.toFloat())
                    )
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (active >= 0) { active = -1; performClick(); return true }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}