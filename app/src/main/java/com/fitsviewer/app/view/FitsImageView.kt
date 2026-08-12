package com.fitsviewer.app.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.fitsviewer.app.region.Region
import com.fitsviewer.app.wcs.Wcs
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * FITS 图像交互视图。
 * 坐标层次: 数据坐标(0-based, y向上) → 位图坐标(y向下,已在渲染时翻转)
 *          → baseMatrix(适配窗口+北上东左) → userMatrix(手势缩放平移) → 屏幕。
 *
 * 工具 (tool):
 *  BROWSE  浏览: 拖动平移 / 双指缩放 / 双击放大
 *  SELECT  选择编辑: 点选区域, 拖动移动, 长按/双击打开属性
 *  CIRCLE/BOX/ELLIPSE  按下-拖动绘制对应形状
 *  POLYGON 逐点点击加顶点, 点回起点或切换工具完成
 *  PROJECT 依次点选两点, 生成两点连线上的数值剖面
 */
class FitsImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { BROWSE, SELECT, CIRCLE, BOX, ELLIPSE, POLYGON, PROJECT }

    interface Listener {
        /** 触摸位置对应的数据坐标 (可能越界)，供状态栏显示 */
        fun onCursor(dataX: Double, dataY: Double)
        /** 选中某区域 (index=-1 表示取消选择) */
        fun onRegionSelected(index: Int)
        /** 请求打开某区域的属性编辑对话框 */
        fun onRegionEdit(index: Int)
        /** 投影: 两点连线 (数据坐标), 请求生成剖面折线 */
        fun onProjection(x0: Double, y0: Double, x1: Double, y1: Double)
        /** 状态提示文字 */
        fun onHint(msg: String)
    }

    var listener: Listener? = null
    var wcs: Wcs? = null
    var smoothDraw = true          // 位图绘制是否双线性滤波
        set(v) { field = v; bmpPaint.isFilterBitmap = v; invalidate() }

    /** 新建区域采用的颜色/线宽 (属性对话框会回写, 使连续新建保持一致) */
    var newColor = Color.CYAN
    var newWidth = 2

    var tool: Tool = Tool.BROWSE
        set(v) {
            if (field == Tool.POLYGON && v != Tool.POLYGON) finishPolygon(commit = true)
            field = v
            projectStart = null
            invalidate()
        }
    var selectedIndex = -1

    /** 共享天球视口 (WCS 对齐联动): 中心赤经/赤纬(deg) + 每屏幕像素对应角尺度(deg/px) */
    class SkyViewport(var centerRa: Double, var centerDec: Double, var degPerPx: Double)

    /** 非 null 且 WCS 有效时, 图像按此视口对齐到同一天区 (北上东左) */
    var skyViewport: SkyViewport? = null
        set(v) { field = v; invalidate() }
    /** 对齐模式下本图手势改变了共享视口时回调 (通知兄弟图同步) */
    var onViewportChanged: (() -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var imgW = 0
    private var imgH = 0
    /** 显示像素 → 原始像素 的抽样步长（用于坐标换算） */
    var binFactor = 1

    private val baseMatrix = Matrix()
    private val userMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val invMatrix = Matrix()

    private var northUp = false

    val regions = ArrayList<Region>()

    // 交互临时状态
    private var grabIndex = -1
    private var createIndex = -1
    private var createStart: DoubleArray? = null
    private val polygonPts = ArrayList<Double>()
    private var projectStart: DoubleArray? = null
    var projectionLine: Pair<DoubleArray, DoubleArray>? = null
    private var downX = 0f
    private var downY = 0f

    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val regionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textSize = 28f
    }
    private val handlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        isAntiAlias = true
    }
    private val overlayPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        isAntiAlias = true
    }

    // ---------- 手势: 浏览 ----------

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })

    private val browseGesture = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                panBy(-dx, -dy)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomBy(2f, e.x, e.y)
                return true
            }
            override fun onDown(e: MotionEvent): Boolean = true
        })

    // ---------- 手势: 选择/编辑 ----------

    private val selectGesture = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                grabIndex = hitTest(e.x, e.y)   // 记录初始按下命中的区域
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (grabIndex in regions.indices) {
                    // 拖动选中的区域: 用数据坐标增量平移
                    val cur = screenToData(e2.x, e2.y) ?: return true
                    val prev = screenToData(e2.x + dx, e2.y + dy) ?: return true
                    val r = regions[grabIndex]
                    r.translate(cur[0] - prev[0], cur[1] - prev[1])
                    if (r.shape == "polygon") r.recomputeCentroid()
                    invalidate()
                } else {
                    panBy(-dx, -dy)   // 空白处仍可平移 (对齐模式下联动)
                }
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                selectedIndex = hitTest(e.x, e.y)
                listener?.onRegionSelected(selectedIndex)
                invalidate()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val idx = hitTest(e.x, e.y)
                if (idx >= 0) { selectedIndex = idx; listener?.onRegionEdit(idx); invalidate() }
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val idx = hitTest(e.x, e.y)
                if (idx >= 0) { selectedIndex = idx; listener?.onRegionEdit(idx); invalidate() }
                return true
            }
        })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            if (createIndex >= 0) { createIndex = -1; createStart = null }  // 缩放时放弃创建
            invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            screenToData(event.x, event.y)?.let { listener?.onCursor(it[0], it[1]) }
        }
        when (tool) {
            Tool.BROWSE -> browseGesture.onTouchEvent(event)
            Tool.SELECT -> selectGesture.onTouchEvent(event)
            Tool.CIRCLE, Tool.BOX, Tool.ELLIPSE -> handleCreate(event)
            Tool.POLYGON -> handlePolygon(event)
            Tool.PROJECT -> handleProject(event)
        }
        return true
    }

    // ---------- 交互: 拖动绘制 圆/矩形/椭圆 ----------

    private fun handleCreate(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d = screenToData(e.x, e.y) ?: return
                createStart = d
                val reg = when (tool) {
                    Tool.CIRCLE -> Region("circle", d[0], d[1], doubleArrayOf(0.0), newColor, width = newWidth)
                    Tool.BOX -> Region("box", d[0], d[1], doubleArrayOf(0.0, 0.0, 0.0), newColor, width = newWidth)
                    else -> Region("ellipse", d[0], d[1], doubleArrayOf(0.0, 0.0, 0.0), newColor, width = newWidth)
                }
                regions.add(reg)
                createIndex = regions.size - 1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val d = screenToData(e.x, e.y) ?: return
                val s = createStart ?: return
                val r = regions.getOrNull(createIndex) ?: return
                val ax = abs(d[0] - s[0]); val ay = abs(d[1] - s[1])
                when (r.shape) {
                    "circle" -> r.p[0] = hypot(d[0] - s[0], d[1] - s[1])
                    "box" -> { r.p[0] = ax * 2; r.p[1] = ay * 2 }
                    "ellipse" -> { r.p[0] = ax; r.p[1] = ay }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = regions.getOrNull(createIndex)
                if (r != null) {
                    val minSz = max(5.0, imgW * binFactor * 0.01)   // 单击时给个默认大小
                    when (r.shape) {
                        "circle" -> if (r.p[0] < 1) r.p[0] = minSz
                        "box" -> { if (r.p[0] < 1) r.p[0] = minSz * 2; if (r.p[1] < 1) r.p[1] = minSz * 2 }
                        "ellipse" -> { if (r.p[0] < 1) r.p[0] = minSz; if (r.p[1] < 1) r.p[1] = minSz }
                    }
                    selectedIndex = createIndex
                    listener?.onRegionSelected(createIndex)
                }
                createIndex = -1; createStart = null
                invalidate()
            }
        }
    }

    // ---------- 交互: 多边形 ----------

    private fun handlePolygon(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (hypot(e.x - downX, e.y - downY) > 20f) return   // 视为拖动/误触
                val d = screenToData(e.x, e.y) ?: return
                if (polygonPts.size >= 6) {
                    val tol = 24f * binFactor / currentScale()
                    if (hypot(d[0] - polygonPts[0], d[1] - polygonPts[1]) < tol) {
                        finishPolygon(commit = true); return
                    }
                }
                polygonPts.add(d[0]); polygonPts.add(d[1])
                listener?.onHint("多边形: 已放置 ${polygonPts.size / 2} 个顶点 (点回起点或切换工具完成)")
                invalidate()
            }
        }
    }

    private fun finishPolygon(commit: Boolean) {
        if (commit && polygonPts.size >= 6) {
            val r = Region("polygon", 0.0, 0.0, polygonPts.toDoubleArray(), newColor, width = newWidth)
            r.recomputeCentroid()
            regions.add(r)
            selectedIndex = regions.size - 1
            listener?.onRegionSelected(selectedIndex)
        }
        polygonPts.clear()
        invalidate()
    }

    // ---------- 交互: 投影 ----------

    private fun handleProject(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (hypot(e.x - downX, e.y - downY) > 20f) return
                val d = screenToData(e.x, e.y) ?: return
                val s = projectStart
                if (s == null) {
                    projectStart = doubleArrayOf(d[0], d[1])
                    listener?.onHint("投影: 已选起点, 再点选终点")
                    invalidate()
                } else {
                    projectionLine = Pair(s, doubleArrayOf(d[0], d[1]))
                    projectStart = null
                    listener?.onProjection(s[0], s[1], d[0], d[1])
                    invalidate()
                }
            }
        }
    }

    private fun hitTest(sx: Float, sy: Float): Int {
        val d = screenToData(sx, sy) ?: return -1
        val tol = (24f * binFactor / currentScale()).toDouble()
        for (i in regions.indices.reversed()) if (regions[i].hit(d[0], d[1], tol)) return i
        return -1
    }

    // ---------- 数据设置 ----------

    fun setImage(bmp: Bitmap, bin: Int) {
        val sizeChanged = bmp.width != imgW || bmp.height != imgH
        bitmap = bmp
        imgW = bmp.width
        imgH = bmp.height
        binFactor = bin
        if (sizeChanged) resetView() else invalidate()
    }

    fun setNorthUp(enabled: Boolean) {
        northUp = enabled
        rebuildBaseMatrix()
        userMatrix.reset()
        invalidate()
    }

    fun resetView() {
        rebuildBaseMatrix()
        userMatrix.reset()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBaseMatrix()
    }

    /**
     * baseMatrix: 位图 → 视图中心，等比缩放适配；
     * 若 northUp 且 WCS 有效，再叠加旋转/翻转使北上东左。
     */
    private fun rebuildBaseMatrix() {
        baseMatrix.reset()
        if (imgW == 0 || imgH == 0 || width == 0 || height == 0) return

        var rotDeg = 0f
        var flipX = false
        val w = wcs
        if (northUp && w != null && w.valid) {
            calcOrientation(w)?.let { rotDeg = it.first; flipX = it.second }
        }

        // 先绕位图中心旋转/翻转，再适配窗口
        val m = Matrix()
        m.postRotate(rotDeg, imgW / 2f, imgH / 2f)
        if (flipX) {
            m.postScale(-1f, 1f, imgW / 2f, imgH / 2f)
        }
        // 旋转后的包围盒
        val corners = floatArrayOf(0f, 0f, imgW.toFloat(), 0f, 0f, imgH.toFloat(), imgW.toFloat(), imgH.toFloat())
        m.mapPoints(corners)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < corners.size) {
            minX = min(minX, corners[i]); maxX = max(maxX, corners[i])
            minY = min(minY, corners[i + 1]); maxY = max(maxY, corners[i + 1])
            i += 2
        }
        val bw = maxX - minX
        val bh = maxY - minY
        val scale = min(width / bw, height / bh) * 0.98f
        m.postTranslate(-minX, -minY)
        m.postScale(scale, scale)
        m.postTranslate((width - bw * scale) / 2f, (height - bh * scale) / 2f)
        baseMatrix.set(m)
    }

    /**
     * 由 WCS 数值求方向: 取图心, 向北/向东各偏移若干像素尺度,
     * 反解到位图坐标求方向向量。返回 (旋转角deg, 是否水平翻转)。
     */
    private fun calcOrientation(w: Wcs): Pair<Float, Boolean>? {
        val cx = imgW / 2.0
        val cy = imgH / 2.0
        // 位图坐标 → 数据坐标 (y 翻转 + bin)
        val dataC = bmpToData(cx, cy)
        val world = w.pixToWorld(dataC[0], dataC[1]) ?: return null
        val eps = w.pixelScaleDeg() * max(imgW, imgH) * binFactor * 0.05
        val pN = w.worldToPix(world[0], world[1] + eps) ?: return null
        val pE = w.worldToPix(
            world[0] + eps / max(1e-9, cos(Math.toRadians(world[1]))), world[1]
        ) ?: return null

        fun dataToBmpVec(p: DoubleArray): DoubleArray {
            val bx = p[0] / binFactor - cx
            val by = (imgH - 1 - p[1] / binFactor) - cy
            return doubleArrayOf(bx, by)
        }

        val vN = dataToBmpVec(pN)
        val vE = dataToBmpVec(pE)
        if (hypot(vN[0], vN[1]) < 1e-9) return null

        // 使北方向指向屏幕上方 (0,-1)
        val angN = Math.toDegrees(atan2(vN[1], vN[0]))
        val rot = (-90.0 - angN)
        // 旋转后检查东方向, 若指向右侧则需水平翻转
        val rad = Math.toRadians(rot)
        val ex = vE[0] * cos(rad) - vE[1] * sin(rad)
        val flip = ex > 0
        return Pair(rot.toFloat(), flip)
    }

    // ---------- 坐标换算 ----------

    /** 位图坐标 → 数据坐标 (考虑 y 翻转与降采样) */
    private fun bmpToData(bx: Double, by: Double): DoubleArray =
        doubleArrayOf(bx * binFactor, (imgH - 1 - by) * binFactor)

    /** 数据坐标 → 位图坐标 */
    fun dataToBmp(dx: Double, dy: Double): DoubleArray =
        doubleArrayOf(dx / binFactor, imgH - 1 - dy / binFactor)

    /** 屏幕坐标 → 数据坐标 */
    fun screenToData(sx: Float, sy: Float): DoubleArray? {
        if (bitmap == null) return null
        composeMatrix()
        if (!drawMatrix.invert(invMatrix)) return null
        val pts = floatArrayOf(sx, sy)
        invMatrix.mapPoints(pts)
        return bmpToData(pts[0].toDouble(), pts[1].toDouble())
    }

    private fun composeMatrix() {
        if (buildAlignedMatrix()) return
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(userMatrix)
    }

    /**
     * 对齐模式: 用 setPolyToPoly 由三点 (中心/北/东) 构造仿射矩阵,
     * 使共享视口中心落在视图中心、北向上、东向左、角尺度=degPerPx。
     * 成功写入 drawMatrix 返回 true。
     */
    private fun buildAlignedMatrix(): Boolean {
        val vp = skyViewport ?: return false
        val w = wcs ?: return false
        if (!w.valid || imgW == 0 || imgH == 0 || width == 0 || height == 0) return false
        val s = vp.degPerPx
        val l = 100.0
        val cxs = width / 2f
        val cys = height / 2f
        val cosd = cos(Math.toRadians(vp.centerDec)).let { if (abs(it) < 1e-6) 1e-6 else it }
        val pc = w.worldToPix(vp.centerRa, vp.centerDec) ?: return false
        val pn = w.worldToPix(vp.centerRa, vp.centerDec + l * s) ?: return false
        val pe = w.worldToPix(vp.centerRa + (l * s) / cosd, vp.centerDec) ?: return false
        val bc = dataToBmp(pc[0], pc[1]); val bn = dataToBmp(pn[0], pn[1]); val be = dataToBmp(pe[0], pe[1])
        val src = floatArrayOf(
            bc[0].toFloat(), bc[1].toFloat(),
            bn[0].toFloat(), bn[1].toFloat(),
            be[0].toFloat(), be[1].toFloat()
        )
        val dst = floatArrayOf(cxs, cys, cxs, cys - l.toFloat(), cxs - l.toFloat(), cys)
        drawMatrix.setPolyToPoly(src, 0, dst, 0, 3)
        return true
    }

    private fun currentScale(): Float {
        composeMatrix()
        val v = FloatArray(9)
        drawMatrix.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    private fun clampScale() {
        val s = currentScale()
        if (s > 200f) {
            val f = 200f / s
            userMatrix.postScale(f, f, width / 2f, height / 2f)
        } else if (s < 0.05f) {
            val f = 0.05f / s
            userMatrix.postScale(f, f, width / 2f, height / 2f)
        }
    }

    /** 平移: 对齐模式改共享视口 (联动), 否则平移 userMatrix。参数为内容位移(屏幕像素) */
    private fun panBy(cdx: Float, cdy: Float) {
        val vp = skyViewport
        if (vp != null && wcs?.valid == true) {
            val s = vp.degPerPx
            vp.centerDec += cdy * s
            val cosd = cos(Math.toRadians(vp.centerDec)).let { if (abs(it) < 1e-6) 1e-6 else it }
            vp.centerRa += (cdx * s) / cosd
            onViewportChanged?.invoke()
            invalidate()
        } else {
            userMatrix.postTranslate(cdx, cdy)
            invalidate()
        }
    }

    /** 缩放: 对齐模式改共享视口 degPerPx (联动), 否则缩放 userMatrix */
    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val vp = skyViewport
        if (vp != null && wcs?.valid == true) {
            vp.degPerPx /= f
            onViewportChanged?.invoke()
            invalidate()
        } else {
            userMatrix.postScale(f, f, fx, fy)
            clampScale()
            invalidate()
        }
    }

    /** 由本图 WCS 与当前视图尺寸算出默认视口 (图像大致填满视图高度) */
    fun computeDefaultViewport(): SkyViewport? {
        val w = wcs ?: return null
        if (!w.valid || imgW == 0 || imgH == 0) return null
        val dataCx = imgW * binFactor / 2.0
        val dataCy = imgH * binFactor / 2.0
        val world = w.pixToWorld(dataCx, dataCy) ?: return null
        val angH = imgH.toDouble() * binFactor * w.pixelScaleDeg()
        val viewH = if (height > 0) height.toDouble() else imgH.toDouble()
        val degPerPx = (angH / (viewH * 0.98)).let {
            if (it <= 0 || it.isNaN()) w.pixelScaleDeg() else it
        }
        return SkyViewport(world[0], world[1], degPerPx)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        composeMatrix()
        canvas.save()
        canvas.concat(drawMatrix)
        canvas.drawBitmap(bmp, 0f, 0f, bmpPaint)

        // 在位图坐标系下画区域, 线宽/字号随缩放反向补偿
        val s = currentScale().coerceAtLeast(1e-6f)
        textPaint.textSize = 28f / s
        for (r in regions) drawRegion(canvas, r, s)

        if (selectedIndex in regions.indices) drawSelection(canvas, regions[selectedIndex], s)
        drawPolygonInProgress(canvas, s)
        drawProjection(canvas, s)
        canvas.restore()
    }

    private fun drawRegion(canvas: Canvas, r: Region, s: Float) {
        regionPaint.color = r.color
        textPaint.color = r.color
        regionPaint.strokeWidth = r.width.coerceAtLeast(1) / s
        val c = dataToBmp(r.x, r.y)
        val cx = c[0].toFloat()
        val cy = c[1].toFloat()
        val bin = binFactor.toFloat()
        when (r.shape) {
            "circle" -> canvas.drawCircle(cx, cy, (r.p[0] / bin).toFloat(), regionPaint)
            "annulus" -> for (rad in r.p) canvas.drawCircle(cx, cy, (rad / bin).toFloat(), regionPaint)
            "ellipse" -> {
                canvas.save()
                // 数据坐标角度→位图坐标 y 翻转, 旋转方向取反
                canvas.rotate(-(r.p.getOrElse(2) { 0.0 }).toFloat(), cx, cy)
                val rx = (r.p[0] / bin).toFloat()
                val ry = (r.p[1] / bin).toFloat()
                canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, regionPaint)
                canvas.restore()
            }
            "box" -> {
                canvas.save()
                canvas.rotate(-(r.p.getOrElse(2) { 0.0 }).toFloat(), cx, cy)
                val hw = (r.p[0] / 2 / bin).toFloat()
                val hh = (r.p[1] / 2 / bin).toFloat()
                canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, regionPaint)
                canvas.restore()
            }
            "point" -> {
                val d = 8f / s
                canvas.drawLine(cx - d, cy, cx + d, cy, regionPaint)
                canvas.drawLine(cx, cy - d, cx, cy + d, regionPaint)
            }
            "line" -> {
                val e = dataToBmp(r.p.getOrElse(0) { r.x }, r.p.getOrElse(1) { r.y })
                canvas.drawLine(cx, cy, e[0].toFloat(), e[1].toFloat(), regionPaint)
            }
            "polygon" -> {
                val path = Path()
                var i = 0; var first = true
                while (i + 1 < r.p.size) {
                    val b = dataToBmp(r.p[i], r.p[i + 1])
                    if (first) { path.moveTo(b[0].toFloat(), b[1].toFloat()); first = false }
                    else path.lineTo(b[0].toFloat(), b[1].toFloat())
                    i += 2
                }
                path.close()
                canvas.drawPath(path, regionPaint)
            }
            "text" -> { /* 只画标签 */ }
        }
        if (r.label.isNotEmpty() || r.shape == "text") {
            val label = r.label.ifEmpty { "text" }
            canvas.drawText(label, cx + 6f / s, cy - 6f / s, textPaint)
        }
    }

    /** 选中区域: 在关键点画白色小方块手柄 */
    private fun drawSelection(canvas: Canvas, r: Region, s: Float) {
        handlePaint.color = Color.WHITE
        val hs = 6f / s
        fun handle(dx: Double, dy: Double) {
            val b = dataToBmp(dx, dy)
            val x = b[0].toFloat(); val y = b[1].toFloat()
            canvas.drawRect(x - hs, y - hs, x + hs, y + hs, handlePaint)
        }
        when (r.shape) {
            "polygon" -> { var i = 0; while (i + 1 < r.p.size) { handle(r.p[i], r.p[i + 1]); i += 2 }; handle(r.x, r.y) }
            "line" -> { handle(r.x, r.y); handle(r.p.getOrElse(0) { r.x }, r.p.getOrElse(1) { r.y }) }
            else -> handle(r.x, r.y)
        }
    }

    private fun drawPolygonInProgress(canvas: Canvas, s: Float) {
        if (polygonPts.size < 2) return
        overlayPaint.color = newColor
        overlayPaint.strokeWidth = newWidth.coerceAtLeast(1) / s
        val path = Path()
        var i = 0; var first = true
        while (i + 1 < polygonPts.size) {
            val b = dataToBmp(polygonPts[i], polygonPts[i + 1])
            if (first) { path.moveTo(b[0].toFloat(), b[1].toFloat()); first = false }
            else path.lineTo(b[0].toFloat(), b[1].toFloat())
            i += 2
        }
        canvas.drawPath(path, overlayPaint)
        handlePaint.color = Color.WHITE
        val hs = 6f / s
        i = 0
        while (i + 1 < polygonPts.size) {
            val b = dataToBmp(polygonPts[i], polygonPts[i + 1])
            val x = b[0].toFloat(); val y = b[1].toFloat()
            canvas.drawRect(x - hs, y - hs, x + hs, y + hs, handlePaint)
            i += 2
        }
    }

    private fun drawProjection(canvas: Canvas, s: Float) {
        val hs = 6f / s
        handlePaint.color = Color.YELLOW
        overlayPaint.color = Color.YELLOW
        overlayPaint.strokeWidth = 2f / s
        fun mark(a: DoubleArray) {
            val b = dataToBmp(a[0], a[1])
            val x = b[0].toFloat(); val y = b[1].toFloat()
            canvas.drawRect(x - hs, y - hs, x + hs, y + hs, handlePaint)
        }
        projectStart?.let { mark(it) }
        projectionLine?.let { (a, b) ->
            val pa = dataToBmp(a[0], a[1]); val pb = dataToBmp(b[0], b[1])
            canvas.drawLine(pa[0].toFloat(), pa[1].toFloat(), pb[0].toFloat(), pb[1].toFloat(), overlayPaint)
            mark(a); mark(b)
        }
    }
}
