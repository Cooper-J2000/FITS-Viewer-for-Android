package com.fitsviewer.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * 轻量图表: 折线 / 散点 / 柱状。自动坐标范围与刻度。
 */
class ChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Type { LINE, SCATTER, BAR }

    private var xs = DoubleArray(0)
    private var ys = DoubleArray(0)
    private var type = Type.LINE
    private var xLabel = ""
    private var yLabel = ""

    private val axisPaint = Paint().apply {
        color = Color.rgb(144, 164, 174); strokeWidth = 2f; isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(40, 255, 255, 255); strokeWidth = 1f
    }
    private val tickPaint = Paint().apply {
        color = Color.rgb(176, 190, 197); textSize = 24f; isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.rgb(79, 195, 247); textSize = 28f; isAntiAlias = true
    }
    private val seriesPaint = Paint().apply {
        color = Color.rgb(79, 195, 247); strokeWidth = 3f; isAntiAlias = true
    }
    private val barPaint = Paint().apply {
        color = Color.argb(200, 79, 195, 247); style = Paint.Style.FILL
    }

    fun setData(x: DoubleArray, y: DoubleArray, t: Type, xl: String, yl: String) {
        // 过滤成对 NaN
        val n = minOf(x.size, y.size)
        val fx = ArrayList<Double>(n)
        val fy = ArrayList<Double>(n)
        for (i in 0 until n) {
            if (!x[i].isNaN() && !y[i].isNaN() && !x[i].isInfinite() && !y[i].isInfinite()) {
                fx.add(x[i]); fy.add(y[i])
            }
        }
        xs = fx.toDoubleArray(); ys = fy.toDoubleArray()
        type = t; xLabel = xl; yLabel = yl
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (xs.isEmpty()) {
            canvas.drawText("无有效数据", width / 2f - 60, height / 2f, labelPaint)
            return
        }
        val padL = 110f; val padR = 30f; val padT = 30f; val padB = 90f
        val plotW = width - padL - padR
        val plotH = height - padT - padB
        if (plotW <= 0 || plotH <= 0) return

        var xmin = xs.min(); var xmax = xs.max()
        var ymin = ys.min(); var ymax = ys.max()
        if (type == Type.BAR) { ymin = minOf(ymin, 0.0); ymax = maxOf(ymax, 0.0) }
        if (xmax == xmin) { xmax = xmin + 1 }
        if (ymax == ymin) { ymax = ymin + 1 }
        // 留 5% 边距
        val xm = (xmax - xmin) * 0.05; val ym = (ymax - ymin) * 0.05
        xmin -= xm; xmax += xm; ymin -= ym; ymax += ym

        fun px(v: Double) = padL + ((v - xmin) / (xmax - xmin) * plotW).toFloat()
        fun py(v: Double) = padT + plotH - ((v - ymin) / (ymax - ymin) * plotH).toFloat()

        // 网格 + 刻度
        for (t in niceTicks(xmin, xmax)) {
            val sx = px(t)
            canvas.drawLine(sx, padT, sx, padT + plotH, gridPaint)
            val s = fmtTick(t)
            canvas.drawText(s, sx - tickPaint.measureText(s) / 2, padT + plotH + 34f, tickPaint)
        }
        for (t in niceTicks(ymin, ymax)) {
            val sy = py(t)
            canvas.drawLine(padL, sy, padL + plotW, sy, gridPaint)
            val s = fmtTick(t)
            canvas.drawText(s, padL - tickPaint.measureText(s) - 8f, sy + 8f, tickPaint)
        }
        // 轴
        canvas.drawLine(padL, padT, padL, padT + plotH, axisPaint)
        canvas.drawLine(padL, padT + plotH, padL + plotW, padT + plotH, axisPaint)
        // 轴标签
        canvas.drawText(xLabel, padL + plotW / 2 - labelPaint.measureText(xLabel) / 2,
            height - 20f, labelPaint)
        canvas.save()
        canvas.rotate(-90f, 30f, padT + plotH / 2)
        canvas.drawText(yLabel, 30f - labelPaint.measureText(yLabel) / 2, padT + plotH / 2 + 10f, labelPaint)
        canvas.restore()

        // 数据系列
        when (type) {
            Type.LINE -> {
                var lastX = 0f; var lastY = 0f; var first = true
                for (i in xs.indices) {
                    val sx = px(xs[i]); val sy = py(ys[i])
                    if (!first) canvas.drawLine(lastX, lastY, sx, sy, seriesPaint)
                    lastX = sx; lastY = sy; first = false
                }
            }
            Type.SCATTER -> {
                val r = if (xs.size > 2000) 2.5f else 5f
                for (i in xs.indices) canvas.drawCircle(px(xs[i]), py(ys[i]), r, seriesPaint)
            }
            Type.BAR -> {
                val bw = max(1f, plotW / xs.size * 0.8f)
                val y0 = py(0.0.coerceIn(ymin, ymax))
                for (i in xs.indices) {
                    val sx = px(xs[i])
                    canvas.drawRect(sx - bw / 2, minOf(py(ys[i]), y0), sx + bw / 2,
                        maxOf(py(ys[i]), y0), barPaint)
                }
            }
        }
    }

    /** 生成 4-7 个"好看"的刻度 */
    private fun niceTicks(lo: Double, hi: Double): List<Double> {
        val span = hi - lo
        if (span <= 0 || span.isNaN()) return emptyList()
        val rawStep = span / 5.0
        val mag = 10.0.pow(floor(log10(rawStep)))
        val norm = rawStep / mag
        val step = when {
            norm < 1.5 -> mag
            norm < 3.5 -> 2 * mag
            norm < 7.5 -> 5 * mag
            else -> 10 * mag
        }
        val ticks = ArrayList<Double>()
        var t = ceil(lo / step) * step
        while (t <= hi + step * 1e-9) { ticks.add(t); t += step }
        return ticks
    }

    private fun fmtTick(v: Double): String {
        val av = abs(v)
        return when {
            av < 1e-12 -> "0"
            av >= 1e5 || av < 1e-3 -> "%.1e".format(v)
            v == floor(v) -> v.toLong().toString()
            else -> "%.3g".format(v)
        }
    }
}
