package com.focuslens.focuslens.focus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Smart crop of the error the AI points at. A box is [ymin, xmin, ymax, xmax], integers 0..1000 relative to the photo. */
object Crop {
    const val MAX_W = 560
    const val MAX_H = 240
    private const val PAD = 0.12f
    private const val MIN_AREA = 5_000L // 0.5 % of 1000×1000
    private const val YELLOW = 0xFFFFD400.toInt()

    fun valid(b: IntArray?): Boolean =
        b != null && b.size == 4 && b[0] >= 0 && b[0] < b[2] && b[2] <= 1000 && b[1] >= 0 && b[1] < b[3] && b[3] <= 1000 &&
            (b[2] - b[0]).toLong() * (b[3] - b[1]) >= MIN_AREA

    /** The box itself in pixels, or null when the box is invalid or empty at this size. */
    fun box(b: IntArray?, w: Int, h: Int): PixelRect? {
        if (b == null || !valid(b) || w <= 0 || h <= 0) return null
        val r = PixelRect(b[1] * w / 1000, b[0] * h / 1000, b[3] * w / 1000, b[2] * h / 1000)
        return r.takeIf { it.width > 0 && it.height > 0 }
    }

    /** The box grown by [pad] of its size on every side, clamped to the image. */
    fun region(b: IntArray?, w: Int, h: Int, pad: Float = PAD): PixelRect? {
        val r = box(b, w, h) ?: return null
        val px = (r.width * pad).roundToInt()
        val py = (r.height * pad).roundToInt()
        return PixelRect(max(0, r.left - px), max(0, r.top - py), min(w, r.right + px), min(h, r.bottom + py))
    }

    /** Crops [box] out of [jpeg], scales it to fit [MAX_W]×[MAX_H] and draws a yellow frame around the box. Null if unusable. */
    fun render(jpeg: ByteArray, box: IntArray?): Bitmap? {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        try {
            val inner = box(box, src.width, src.height) ?: return null
            val r = region(box, src.width, src.height) ?: return null
            val s = min(MAX_W.toFloat() / r.width, MAX_H.toFloat() / r.height)
            val tw = max(1, (r.width * s).roundToInt())
            val th = max(1, (r.height * s).roundToInt())
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(src, Rect(r.left, r.top, r.right, r.bottom), Rect(0, 0, tw, th), Paint(Paint.FILTER_BITMAP_FLAG))
            val stroke = 5f
            val p = Paint().apply { style = Paint.Style.STROKE; color = YELLOW; strokeWidth = stroke }
            val half = stroke / 2
            c.drawRect(RectF(
                ((inner.left - r.left) * s).coerceAtLeast(half), ((inner.top - r.top) * s).coerceAtLeast(half),
                ((inner.right - r.left) * s).coerceAtMost(tw - half), ((inner.bottom - r.top) * s).coerceAtMost(th - half),
            ), p)
            return out
        } finally {
            src.recycle()
        }
    }

    /** HUD copy: black strips to a fixed width, so "fill the width" never blows up a narrow crop. */
    fun forHud(b: Bitmap): Bitmap {
        if (b.width >= MAX_W) return b
        val out = Bitmap.createBitmap(MAX_W, b.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.BLACK)
        Canvas(out).drawBitmap(b, (MAX_W - b.width) / 2f, 0f, null)
        return out
    }
}
