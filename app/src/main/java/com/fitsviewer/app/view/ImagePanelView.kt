package com.fitsviewer.app.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import com.fitsviewer.app.ChartActivity
import com.fitsviewer.app.FitsRepo
import com.fitsviewer.app.R
import com.fitsviewer.app.fits.ImageData
import com.fitsviewer.app.region.Ds9
import com.fitsviewer.app.region.Region
import com.fitsviewer.app.render.ImageRenderer
import com.fitsviewer.app.wcs.Wcs
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 可复用的图像面板: 一个 FitsImageView + 状态栏 + 可折叠控制栏,
 * 内含 ImageActivity 的全部功能 (拉伸/色表/平滑、亮度对比度、北上东左、
 * region 绘制与属性编辑、线剖面投影)。ImageActivity 与 CanvasActivity 共用。
 */
class ImagePanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val fitsView: FitsImageView
    private val tvStatus: TextView
    private val tvTitle: TextView
    private val scrollControls: ScrollView
    private val executor = Executors.newSingleThreadExecutor()

    private var raw: ImageData? = null
    private var current: FloatArray? = null
    private var vmin = 0f
    private var vmax = 1f
    var wcs: Wcs? = null
        private set

    private var scaleMode = 0
    private var cmap = 0
    private var smoothMode = 0
    private var brightness = 0f
    private var contrast = 1f

    /** 画布用: 点"图源"按钮的回调 (为 null 时按钮隐藏) */
    var onPickSource: (() -> Unit)? = null
        set(v) { field = v; findViewById<Button>(R.id.btnPickSource).visibility = if (v != null) View.VISIBLE else View.GONE }
    /** 载入/保存 reg 需 Activity 的 SAF, 委托给宿主 */
    var onLoadReg: (() -> Unit)? = null
    var onSaveReg: (() -> Unit)? = null

    private val ds9Colors = linkedMapOf(
        "green" to Color.GREEN, "red" to Color.RED, "cyan" to Color.CYAN,
        "yellow" to Color.YELLOW, "magenta" to Color.MAGENTA,
        "blue" to Color.rgb(64, 128, 255), "white" to Color.WHITE, "black" to Color.BLACK
    )

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_image_panel, this, true)
        fitsView = findViewById(R.id.fitsView)
        tvStatus = findViewById(R.id.tvStatus)
        tvTitle = findViewById(R.id.tvPanelTitle)
        scrollControls = findViewById(R.id.scrollControls)
        setupControls()
        setupToolSpinner()
        setupListener()
        findViewById<Button>(R.id.btnToggleControls).setOnClickListener { toggleControls() }
        findViewById<Button>(R.id.btnPickSource).setOnClickListener { onPickSource?.invoke() }
    }

    // ---------- 对外接口 ----------

    /** 绑定图像数据 (UI 线程): 计算限幅、设置 WCS、渲染 */
    @SuppressLint("SetTextI18n")
    fun setSource(img: ImageData, wcs: Wcs?, title: String) {
        this.raw = img
        this.current = img.data
        this.wcs = wcs
        fitsView.wcs = wcs
        val limits = ImageRenderer.percentileLimits(img.data)
        vmin = limits.first
        vmax = limits.second
        tvTitle.text = title
        rerender()
        val wcsNote = if (wcs != null) " | WCS: ${wcs.ctype1}/${wcs.ctype2}" else " | 无WCS"
        val binNote = if (img.bin > 1) " (降采样 1/${img.bin})" else ""
        tvStatus.text = "${img.width}×${img.height}$binNote$wcsNote"
    }

    fun setTitle(t: String) { tvTitle.text = t }
    fun setHeaderVisible(v: Boolean) { findViewById<View>(R.id.panelHeader).visibility = if (v) View.VISIBLE else View.GONE }
    fun setControlsCollapsed(collapsed: Boolean) {
        scrollControls.visibility = if (collapsed) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnToggleControls).text = if (collapsed) "控制▸" else "控制▾"
    }
    private fun toggleControls() = setControlsCollapsed(scrollControls.visibility == View.VISIBLE)

    fun hasImage(): Boolean = raw != null
    fun hasRegions(): Boolean = fitsView.regions.isNotEmpty()
    fun regionCount(): Int = fitsView.regions.size
    fun regionsText(): String = Ds9.serialize(fitsView.regions, wcs)
    fun addRegionsFromText(text: String): Pair<Int, Int> {
        val (regs, skipped) = Ds9.parse(text, wcs)
        fitsView.regions.addAll(regs)
        fitsView.invalidate()
        return regs.size to skipped
    }

    fun status(msg: String) { tvStatus.text = msg }

    // ---------- 渲染 ----------

    private fun rerender() {
        val img = raw ?: return
        val data = current ?: return
        val bmp = ImageRenderer.render(
            data, img.width, img.height, vmin, vmax, scaleMode, brightness, contrast, cmap
        )
        fitsView.setImage(bmp, img.bin)
    }

    @SuppressLint("SetTextI18n")
    private fun applySmooth() {
        val img = raw ?: return
        val sigma = when (smoothMode) { 1 -> 1.0; 2 -> 2.0; else -> 0.0 }
        if (sigma == 0.0) { current = img.data; rerender(); return }
        tvStatus.text = "平滑处理中…"
        executor.execute {
            val sm = ImageRenderer.gaussianSmooth(img.data, img.width, img.height, sigma)
            post {
                current = sm
                rerender()
                tvStatus.text = "已应用高斯平滑 σ=$sigma"
            }
        }
    }

    // ---------- 交互监听 ----------

    @SuppressLint("SetTextI18n")
    private fun setupListener() {
        fitsView.listener = object : FitsImageView.Listener {
            override fun onCursor(dataX: Double, dataY: Double) {
                val img = raw ?: return
                val ix = (dataX / img.bin).roundToInt()
                val iy = (dataY / img.bin).roundToInt()
                val v = if (ix in 0 until img.width && iy in 0 until img.height)
                    current?.get(iy * img.width + ix) else null
                val sb = StringBuilder("X=%.1f Y=%.1f".format(dataX + 1, dataY + 1))
                if (v != null) sb.append("  值=%.4g".format(v))
                wcs?.pixToWorld(dataX, dataY)?.let {
                    sb.append("  ${Wcs.formatRa(it[0])} ${Wcs.formatDec(it[1])}")
                }
                tvStatus.text = sb.toString()
            }

            override fun onRegionSelected(index: Int) {
                if (index in fitsView.regions.indices) {
                    val r = fitsView.regions[index]
                    tvStatus.text = "已选中: ${shapeName(r.shape)}" +
                        (if (r.label.isNotEmpty()) " \"${r.label}\"" else "") +
                        "  (点\"选中项属性\"或长按编辑)"
                } else tvStatus.text = "未选中区域"
            }

            override fun onRegionEdit(index: Int) = showRegionDialog(index)

            override fun onProjection(x0: Double, y0: Double, x1: Double, y1: Double) =
                computeProjection(x0, y0, x1, y1)

            override fun onHint(msg: String) { tvStatus.text = msg }
        }
    }

    private fun shapeName(s: String) = when (s) {
        "circle" -> "圆形"; "ellipse" -> "椭圆"; "box" -> "矩形"; "polygon" -> "多边形"
        "line" -> "线"; "point" -> "点"; "annulus" -> "环"; "text" -> "文本"; else -> s
    }

    // ---------- region 属性对话框 ----------

    @SuppressLint("SetTextI18n")
    private fun showRegionDialog(index: Int) {
        val r = fitsView.regions.getOrNull(index) ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(px(20), px(12), px(20), 0)
        }
        fun row(labelText: String, field: View): View {
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(4), 0, px(4))
            }
            rowView.addView(TextView(context).apply { text = labelText; width = px(72) })
            field.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            rowView.addView(field)
            return rowView
        }

        val etLabel = EditText(context).apply { setText(r.label); hint = "文本标签(可空)" }
        root.addView(row("文本", etLabel))

        val colorNames = ds9Colors.keys.toList()
        val spColor = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, colorNames)
            val cur = colorNames.indexOfFirst { ds9Colors[it] == r.color }
            if (cur >= 0) setSelection(cur)
        }
        root.addView(row("颜色", spColor))

        val etWidth = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(r.width.toString())
        }
        root.addView(row("线宽", etWidth))

        val sizeFields = ArrayList<Pair<String, EditText>>()
        fun sizeRow(name: String, value: Double) {
            val et = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText("%.2f".format(value))
            }
            sizeFields.add(name to et)
            root.addView(row(name, et))
        }
        when (r.shape) {
            "circle" -> sizeRow("半径", r.p.getOrElse(0) { 0.0 })
            "ellipse" -> {
                sizeRow("长半轴", r.p.getOrElse(0) { 0.0 })
                sizeRow("短半轴", r.p.getOrElse(1) { 0.0 })
                sizeRow("角度", r.p.getOrElse(2) { 0.0 })
            }
            "box" -> {
                sizeRow("宽", r.p.getOrElse(0) { 0.0 })
                sizeRow("高", r.p.getOrElse(1) { 0.0 })
                sizeRow("角度", r.p.getOrElse(2) { 0.0 })
            }
        }
        root.addView(TextView(context).apply {
            text = "位置: X=%.1f Y=%.1f (1-based, 拖动可移动)".format(r.x + 1, r.y + 1)
            setPadding(0, px(8), 0, 0)
        })

        AlertDialog.Builder(context)
            .setTitle("区域属性 — ${shapeName(r.shape)}")
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("确定") { _, _ ->
                r.label = etLabel.text.toString()
                ds9Colors[colorNames[spColor.selectedItemPosition]]?.let { r.color = it }
                r.width = etWidth.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: r.width
                for ((name, et) in sizeFields) {
                    val v = et.text.toString().toDoubleOrNull() ?: continue
                    when (name) {
                        "半径", "长半轴", "宽" -> r.p[0] = v
                        "短半轴", "高" -> if (r.p.size >= 2) r.p[1] = v
                        "角度" -> if (r.p.size >= 3) r.p[2] = v
                    }
                }
                fitsView.newColor = r.color; fitsView.newWidth = r.width
                fitsView.invalidate()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ ->
                if (index in fitsView.regions.indices) fitsView.regions.removeAt(index)
                fitsView.selectedIndex = -1
                fitsView.invalidate()
                Toast.makeText(context, "已删除区域", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- 线剖面投影 ----------

    private fun computeProjection(x0: Double, y0: Double, x1: Double, y1: Double) {
        val img = raw ?: return
        val data = current ?: return
        val n = max(2, hypot(x1 - x0, y1 - y0).roundToInt())
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            val dx = x0 + (x1 - x0) * t
            val dy = y0 + (y1 - y0) * t
            val ix = (dx / img.bin).roundToInt()
            val iy = (dy / img.bin).roundToInt()
            xs[i] = hypot(dx - x0, dy - y0)
            ys[i] = if (ix in 0 until img.width && iy in 0 until img.height)
                data[iy * img.width + ix].toDouble() else Double.NaN
        }
        FitsRepo.chartX = xs; FitsRepo.chartY = ys
        FitsRepo.chartType = ChartView.Type.LINE
        FitsRepo.chartXLabel = "沿线距离 (像素)"
        FitsRepo.chartYLabel = "像素值"
        FitsRepo.chartTitle = "线剖面投影  (%.0f,%.0f)→(%.0f,%.0f)".format(x0 + 1, y0 + 1, x1 + 1, y1 + 1)
        context.startActivity(Intent(context, ChartActivity::class.java))
    }

    // ---------- 控件初始化 ----------

    private fun setupControls() {
        setupSpinner(R.id.spScale, ImageRenderer.SCALE_MODES) { scaleMode = it; rerender() }
        setupSpinner(R.id.spCmap, ImageRenderer.COLORMAPS) { cmap = it; rerender() }
        setupSpinner(R.id.spSmooth, ImageRenderer.SMOOTH_MODES) { smoothMode = it; applySmooth() }

        findViewById<SeekBar>(R.id.sbBrightness).setOnSeekBarChangeListener(seekListener {
            brightness = (it - 100) / 100f; rerender()
        })
        findViewById<SeekBar>(R.id.sbContrast).setOnSeekBarChangeListener(seekListener {
            contrast = if (it <= 100) 0.1f + it / 100f * 0.9f else 1f + (it - 100) / 100f * 3f
            rerender()
        })

        findViewById<ToggleButton>(R.id.btnNorthUp).setOnCheckedChangeListener { _, checked ->
            if (checked && wcs == null) {
                Toast.makeText(context, "该图像 Header 中无有效 WCS 信息", Toast.LENGTH_SHORT).show()
                findViewById<ToggleButton>(R.id.btnNorthUp).isChecked = false
                return@setOnCheckedChangeListener
            }
            fitsView.setNorthUp(checked)
        }
        findViewById<Button>(R.id.btnRegionProps).setOnClickListener {
            val idx = fitsView.selectedIndex
            if (idx in fitsView.regions.indices) showRegionDialog(idx)
            else Toast.makeText(context, "请先在\"选择/编辑\"工具下点选一个区域", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnLoadReg).setOnClickListener { onLoadReg?.invoke() }
        findViewById<Button>(R.id.btnSaveReg).setOnClickListener {
            if (fitsView.regions.isEmpty())
                Toast.makeText(context, "当前没有区域可保存", Toast.LENGTH_SHORT).show()
            else onSaveReg?.invoke()
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener { fitsView.resetView() }
    }

    @SuppressLint("SetTextI18n")
    private fun setupToolSpinner() {
        val toolNames = arrayOf("浏览", "选择/编辑", "圆形", "矩形", "椭圆", "多边形", "投影")
        val toolEnum = arrayOf(
            FitsImageView.Tool.BROWSE, FitsImageView.Tool.SELECT, FitsImageView.Tool.CIRCLE,
            FitsImageView.Tool.BOX, FitsImageView.Tool.ELLIPSE, FitsImageView.Tool.POLYGON,
            FitsImageView.Tool.PROJECT
        )
        val hints = arrayOf(
            "浏览: 拖动平移 / 双指缩放 / 双击放大",
            "选择/编辑: 点选区域, 拖动移动, 长按/双击编辑属性",
            "圆形: 按住拖动绘制", "矩形: 按住拖动绘制", "椭圆: 按住拖动绘制",
            "多边形: 逐点点击加顶点, 点回起点或切换工具完成",
            "投影: 依次点选两点, 生成连线上的数值剖面"
        )
        val sp = findViewById<Spinner>(R.id.spTool)
        sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, toolNames)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                fitsView.tool = toolEnum[pos]
                tvStatus.text = hints[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSpinner(id: Int, items: Array<String>, onSelect: (Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var first = true
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                onSelect(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
