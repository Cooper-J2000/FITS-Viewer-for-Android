package com.fitsviewer.app.render

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/**
 * FITS 图像渲染：数值 → Bitmap。
 * 流程: 归一化(vmin..vmax) → 拉伸函数 → 亮度/对比度 → 色表 LUT。
 * Bitmap 行序做 y 翻转，使 FITS 第 1 行显示在底部（天文惯例）。
 */
object ImageRenderer {

    val SCALE_MODES = arrayOf("Linear", "Log", "Sqrt", "Asinh")
    val COLORMAPS = arrayOf("Gray", "Gray-Inv", "Heat", "Viridis")
    val SMOOTH_MODES = arrayOf("平滑:关", "高斯 σ=1", "高斯 σ=2")

    /** 采样估计百分位数（用于自动限幅，类似 ds9 zscale 的效果） */
    fun percentileLimits(data: FloatArray, lo: Double = 0.25, hi: Double = 99.75): Pair<Float, Float> {
        val step = max(1, data.size / 120000)
        val sample = ArrayList<Float>(data.size / step + 1)
        var i = 0
        while (i < data.size) {
            val v = data[i]
            if (!v.isNaN() && !v.isInfinite()) sample.add(v)
            i += step
        }
        if (sample.isEmpty()) return Pair(0f, 1f)
        sample.sort()
        val n = sample.size
        val a = sample[((lo / 100.0) * (n - 1)).roundToInt().coerceIn(0, n - 1)]
        val b = sample[((hi / 100.0) * (n - 1)).roundToInt().coerceIn(0, n - 1)]
        return if (a >= b) Pair(sample.first(), max(sample.last(), sample.first() + 1e-6f))
        else Pair(a, b)
    }

    /** 可分离高斯平滑，NaN 安全 */
    fun gaussianSmooth(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        if (sigma <= 0) return src
        val radius = max(1, ceil(3 * sigma).toInt())
        val kernel = DoubleArray(2 * radius + 1)
        var sum = 0.0
        for (k in -radius..radius) {
            kernel[k + radius] = exp(-0.5 * (k * k) / (sigma * sigma))
            sum += kernel[k + radius]
        }
        for (k in kernel.indices) kernel[k] /= sum

        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        // 水平
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0.0; var wsum = 0.0
                for (k in -radius..radius) {
                    val xx = x + k
                    if (xx in 0 until w) {
                        val v = src[row + xx]
                        if (!v.isNaN()) { acc += v * kernel[k + radius]; wsum += kernel[k + radius] }
                    }
                }
                tmp[row + x] = if (wsum > 0) (acc / wsum).toFloat() else Float.NaN
            }
        }
        // 垂直
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0.0; var wsum = 0.0
                for (k in -radius..radius) {
                    val yy = y + k
                    if (yy in 0 until h) {
                        val v = tmp[yy * w + x]
                        if (!v.isNaN()) { acc += v * kernel[k + radius]; wsum += kernel[k + radius] }
                    }
                }
                out[y * w + x] = if (wsum > 0) (acc / wsum).toFloat() else Float.NaN
            }
        }
        return out
    }

    /**
     * 渲染主入口。
     * @param brightness [-1, 1], 0 为中性
     * @param contrast   (0, 4], 1 为中性
     */
    fun render(
        data: FloatArray, w: Int, h: Int,
        vmin: Float, vmax: Float,
        scaleMode: Int, brightness: Float, contrast: Float, cmap: Int
    ): Bitmap {
        val lut = buildLut(cmap)
        val range = max(vmax - vmin, 1e-30f)
        val pixels = IntArray(w * h)
        val nanColor = Color.rgb(20, 20, 30)

        for (y in 0 until h) {
            val srcRow = y * w
            val dstRow = (h - 1 - y) * w      // y 翻转
            for (x in 0 until w) {
                val v = data[srcRow + x]
                if (v.isNaN() || v.isInfinite()) { pixels[dstRow + x] = nanColor; continue }
                var t = ((v - vmin) / range).coerceIn(0f, 1f)
                t = when (scaleMode) {
                    1 -> (ln(1.0 + 1000.0 * t) / ln(1001.0)).toFloat()
                    2 -> sqrt(t)
                    3 -> (asinh(10.0 * t) / asinh(10.0)).toFloat()
                    else -> t
                }
                t = ((t - 0.5f) * contrast + 0.5f + brightness).coerceIn(0f, 1f)
                pixels[dstRow + x] = lut[(t * 255f).toInt()]
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun asinh(x: Double) = ln(x + sqrt(x * x + 1.0))

    private fun buildLut(cmap: Int): IntArray {
        val lut = IntArray(256)
        for (i in 0..255) {
            val t = i / 255.0
            lut[i] = when (cmap) {
                1 -> gray(1.0 - t)
                2 -> heat(t)
                3 -> viridis(t)
                else -> gray(t)
            }
        }
        return lut
    }

    private fun gray(t: Double): Int {
        val g = (t * 255).roundToInt().coerceIn(0, 255)
        return Color.rgb(g, g, g)
    }

    private fun heat(t: Double): Int {
        // 黑→红→黄→白
        val r = (min(1.0, t * 3.0) * 255).roundToInt()
        val g = (min(1.0, max(0.0, t * 3.0 - 1.0)) * 255).roundToInt()
        val b = (min(1.0, max(0.0, t * 3.0 - 2.0)) * 255).roundToInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    // viridis 锚点插值近似
    private val VIRIDIS_ANCHORS = arrayOf(
        intArrayOf(68, 1, 84), intArrayOf(72, 40, 120), intArrayOf(62, 74, 137),
        intArrayOf(49, 104, 142), intArrayOf(38, 130, 142), intArrayOf(31, 158, 137),
        intArrayOf(53, 183, 121), intArrayOf(109, 205, 89), intArrayOf(180, 222, 44),
        intArrayOf(253, 231, 37)
    )

    private fun viridis(t: Double): Int {
        val pos = t * (VIRIDIS_ANCHORS.size - 1)
        val i = pos.toInt().coerceIn(0, VIRIDIS_ANCHORS.size - 2)
        val f = pos - i
        val a = VIRIDIS_ANCHORS[i]; val b = VIRIDIS_ANCHORS[i + 1]
        return Color.rgb(
            (a[0] + f * (b[0] - a[0])).roundToInt(),
            (a[1] + f * (b[1] - a[1])).roundToInt(),
            (a[2] + f * (b[2] - a[2])).roundToInt()
        )
    }
}
