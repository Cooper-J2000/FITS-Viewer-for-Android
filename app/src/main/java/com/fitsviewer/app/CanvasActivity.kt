package com.fitsviewer.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fitsviewer.app.store.CanvasEntry
import com.fitsviewer.app.store.CanvasStore
import com.fitsviewer.app.store.RecentStore
import com.fitsviewer.app.store.SourceRef
import com.fitsviewer.app.view.ImagePanelView
import com.fitsviewer.app.wcs.Wcs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 画布: 竖屏上下两个方形图像面板, 各自独立选择图源 (来自最近文件或浏览新文件),
 * 支持 WCS 对齐 (两图对齐到同一天区 + 联动缩放/平移)。可保存为画布历史再打开。
 */
class CanvasActivity : AppCompatActivity() {

    private lateinit var panelTop: ImagePanelView
    private lateinit var panelBottom: ImagePanelView
    private lateinit var btnAlign: ToggleButton
    private val executor = Executors.newSingleThreadExecutor()

    private var canvasId: String = ""
    private var topSrc: SourceRef? = null
    private var botSrc: SourceRef? = null
    private var aligned = false
    private var pendingAlign = false

    private var pickingTop = true      // 图源选择目标
    private var regTargetTop = true    // reg 载入/保存目标

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        openAndPickHdu(pickingTop, uri, FitsRepo.displayName(this, uri))
    }

    private val loadReg = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val panel = if (regTargetTop) panelTop else panelBottom
        try {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            val (n, skipped) = panel.addRegionsFromText(text)
            val msg = "载入 $n 个区域" + if (skipped > 0) " (跳过 $skipped 条)" else ""
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val saveReg = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val panel = if (regTargetTop) panelTop else panelBottom
        try {
            contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(panel.regionsText()) }
            Toast.makeText(this, "已保存 ${panel.regionCount()} 个区域", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_canvas)
        title = "画布"

        panelTop = findViewById(R.id.panelTop)
        panelBottom = findViewById(R.id.panelBottom)
        btnAlign = findViewById(R.id.btnAlign)

        setupPanel(panelTop, true)
        setupPanel(panelBottom, false)

        btnAlign.setOnCheckedChangeListener { _, checked ->
            aligned = checked
            applyAlignment()
        }
        findViewById<Button>(R.id.btnSaveCanvas).setOnClickListener { saveCanvas() }

        canvasId = intent.getStringExtra(EXTRA_CANVAS_ID) ?: CanvasStore.newId()
        CanvasStore.get(this, canvasId)?.let { restore(it) }
    }

    private fun setupPanel(panel: ImagePanelView, top: Boolean) {
        panel.setHeaderVisible(true)
        panel.setControlsCollapsed(true)   // 竖屏空间紧张, 默认收起控制栏
        panel.setTitle(if (top) "上: 未选择图源" else "下: 未选择图源")
        panel.onPickSource = { pickSource(top) }
        panel.onLoadReg = { regTargetTop = top; loadReg.launch(arrayOf("*/*")) }
        panel.onSaveReg = { regTargetTop = top; saveReg.launch("regions.reg") }
    }

    // ---------- 图源选择 ----------

    private fun pickSource(top: Boolean) {
        pickingTop = top
        val recents = RecentStore.list(this)
        val labels = recents.map { it.name }.toMutableList()
        labels.add("浏览文件…")
        AlertDialog.Builder(this)
            .setTitle(if (top) "上图 图源" else "下图 图源")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == recents.size) {
                    openDoc.launch(arrayOf("*/*"))
                } else {
                    val e = recents[which]
                    openAndPickHdu(top, Uri.parse(e.uri), e.name)
                }
            }
            .show()
    }

    /** 打开文件 (后台), 列出图像 HDU 供选择, 然后载入面板 */
    private fun openAndPickHdu(top: Boolean, uri: Uri, name: String) {
        val panel = if (top) panelTop else panelBottom
        panel.status("正在打开 $name …")
        executor.execute {
            try {
                val f = FitsRepo.openShared(this, uri)
                val imgHdus = f.hdus.filter { it.hasImage }
                runOnUiThread {
                    when {
                        imgHdus.isEmpty() -> {
                            panel.status("该文件无图像 HDU")
                            Toast.makeText(this, "该文件无图像 HDU", Toast.LENGTH_SHORT).show()
                        }
                        imgHdus.size == 1 -> loadInto(top, uri, name, imgHdus[0].index)
                        else -> {
                            val labels = imgHdus.map { "[${it.index}] ${it.name}  ${it.dimsString()}" }
                            AlertDialog.Builder(this)
                                .setTitle("选择图像 HDU — $name")
                                .setItems(labels.toTypedArray()) { _, w ->
                                    loadInto(top, uri, name, imgHdus[w].index)
                                }
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    panel.status("打开失败: ${e.message}")
                    Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 后台读取指定 HDU 图像并载入面板 */
    @SuppressLint("SetTextI18n")
    private fun loadInto(top: Boolean, uri: Uri, name: String, hduIndex: Int) {
        val panel = if (top) panelTop else panelBottom
        panel.status("读取图像数据…")
        executor.execute {
            try {
                val f = FitsRepo.openShared(this, uri)
                val hdu = f.hdus.getOrNull(hduIndex) ?: throw IllegalStateException("HDU 不存在")
                val img = f.readImage(hdu)
                val wcs = Wcs(hdu.header).takeIf { it.valid }
                runOnUiThread {
                    val prefix = if (top) "上" else "下"
                    panel.setSource(img, wcs, "$prefix: $name [${hdu.index}]")
                    val src = SourceRef(uri.toString(), name, hduIndex)
                    if (top) topSrc = src else botSrc = src
                    if (aligned) applyAlignment()
                    tryPendingAlign()
                }
            } catch (e: Exception) {
                runOnUiThread { panel.status("读取失败: ${e.message}") }
            }
        }
    }

    // ---------- WCS 对齐 + 联动 ----------

    private fun applyAlignment() {
        val vt = panelTop.fitsView
        val vb = panelBottom.fitsView
        if (!aligned) {
            vt.onViewportChanged = null; vb.onViewportChanged = null
            vt.skyViewport = null; vb.skyViewport = null
            vt.resetView(); vb.resetView()
            return
        }
        if (panelTop.wcs == null || panelBottom.wcs == null) {
            Toast.makeText(this, "两幅图都需有有效 WCS 才能对齐", Toast.LENGTH_SHORT).show()
            aligned = false
            btnAlign.isChecked = false
            return
        }
        val vp = vt.computeDefaultViewport() ?: vb.computeDefaultViewport()
        if (vp == null) {
            Toast.makeText(this, "无法从 WCS 计算视口", Toast.LENGTH_SHORT).show()
            aligned = false
            btnAlign.isChecked = false
            return
        }
        // 两图共享同一视口对象: 一方手势改动后通知另一方重绘
        vt.skyViewport = vp
        vb.skyViewport = vp
        vt.onViewportChanged = { vb.invalidate() }
        vb.onViewportChanged = { vt.invalidate() }
    }

    private fun tryPendingAlign() {
        if (pendingAlign && panelTop.wcs != null && panelBottom.wcs != null) {
            pendingAlign = false
            btnAlign.isChecked = true   // 触发 applyAlignment
        }
    }

    // ---------- 保存 / 恢复 ----------

    private fun saveCanvas() {
        if (topSrc == null && botSrc == null) {
            Toast.makeText(this, "请先为至少一个框选择图源", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val names = listOfNotNull(topSrc?.name, botSrc?.name).joinToString(" / ")
        val title = if (names.isNotEmpty()) names else "画布"
        CanvasStore.save(this, CanvasEntry(canvasId, "$title  ($stamp)", System.currentTimeMillis(), topSrc, botSrc, aligned))
        Toast.makeText(this, "已保存画布", Toast.LENGTH_SHORT).show()
    }

    private fun restore(entry: CanvasEntry) {
        entry.top?.let { loadInto(true, Uri.parse(it.uri), it.name, it.hduIndex) }
        entry.bottom?.let { loadInto(false, Uri.parse(it.uri), it.name, it.hduIndex) }
        if (entry.align) pendingAlign = true
    }

    companion object {
        const val EXTRA_CANVAS_ID = "canvas_id"
    }
}
