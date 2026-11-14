package com.focuslens.focuslens.focus

import android.graphics.Bitmap
import android.graphics.Canvas
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.ByteArrayOutputStream

/** LaTeX -> white-on-transparent bitmap, 544 px wide (the HUD width; additive display: black/transparent is invisible). jlatexmath-android self-inits via its ContentProvider. */
object Latex {
    private const val W = 544
    private const val PAD = 4

    fun bitmap(tex: String): Bitmap? {
        if (tex.isBlank()) return null
        return try {
            var size = 40f
            while (size >= 18f) {
                val d = JLatexMathDrawable.builder(tex).textSize(size).color(0xFFFFFFFF.toInt())
                    .align(JLatexMathDrawable.ALIGN_LEFT).padding(PAD).build()
                val w = d.intrinsicWidth; val h = d.intrinsicHeight
                if (w in 1..W && h > 0) {
                    val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, w, h)
                    d.draw(Canvas(bmp))
                    return bmp
                }
                size *= 0.9f
            }
            null
        } catch (_: Throwable) { null }
    }

    fun png(tex: String): ByteArray? = try {
        bitmap(tex)?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
    } catch (_: Throwable) { null }
}
