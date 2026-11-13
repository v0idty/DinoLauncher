package com.dino.launcher

import android.graphics.*

object Shapes {
    val names = arrayOf(
        "Скруглённый квадрат", "Круг", "Печенье 9", "Печенье 12",
        "Цветок", "Клевер", "Ромашка", "Разные, как в Pixel"
    )
    // {лепестков, глубина волны}
    private val spec = arrayOf(
        doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(9.0, 0.05),
        doubleArrayOf(12.0, 0.045), doubleArrayOf(8.0, 0.11), doubleArrayOf(4.0, 0.13),
        doubleArrayOf(6.0, 0.12), doubleArrayOf(5.0, 0.07)
    )
    private val mix = intArrayOf(1, 2, 3, 4, 5, 6, 7, 2, 3, 5)

    fun path(kind: Int, s: Float): Path {
        val sp = spec[kind.coerceIn(0, spec.size - 1)]
        val n = sp[0]; val a = sp[1]
        val r0 = s / 2f / (1f + a.toFloat())
        val cx = s / 2f
        val path = Path()
        val steps = 240
        for (i in 0..steps) {
            val t = i * 2.0 * Math.PI / steps
            val rr = r0 * (1.0 + a * Math.cos(n * t))
            val x = (cx + rr * Math.cos(t)).toFloat()
            val y = (cx + rr * Math.sin(t)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    fun shape(src: Bitmap, setting: Int, pkg: String): Bitmap {
        if (setting == 0) return src
        val kind = if (setting >= 7) mix[(pkg.hashCode() and 0x7fffffff) % mix.size] else setting
        val s = src.width
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(out).drawPath(
            path(kind, s.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        )
        src.recycle()
        return out
    }
}
