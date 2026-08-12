package com.fitsviewer.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fitsviewer.app.fits.Hdu
import com.fitsviewer.app.view.ImagePanelView
import com.fitsviewer.app.wcs.Wcs
import java.util.concurrent.Executors

/**
 * 图像查看: 复用 ImagePanelView (缩放/平移、拉伸/色表、亮度对比度、平滑、
 * WCS 坐标读出、北上东左、ds9 region、线剖面投影)。控制栏功能全部内置于面板。
 */
class ImageActivity : AppCompatActivity() {

    private lateinit var hdu: Hdu
    private lateinit var panel: ImagePanelView
    private val executor = Executors.newSingleThreadExecutor()

    private val loadReg = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
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
        try {
            contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(panel.regionsText()) }
            Toast.makeText(this, "已保存 ${panel.regionCount()} 个区域", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)

        hdu = MainActivity.hduFromIntent(this) ?: run { finish(); return }
        title = "图像 — [${hdu.index}] ${hdu.name}"

        panel = findViewById(R.id.imagePanel)
        panel.setHeaderVisible(false)
        panel.onLoadReg = { loadReg.launch(arrayOf("*/*")) }
        panel.onSaveReg = { saveReg.launch("regions.reg") }
        loadImage()
    }

    @SuppressLint("SetTextI18n")
    private fun loadImage() {
        panel.status("正在读取图像数据…")
        executor.execute {
            try {
                val img = FitsRepo.fits!!.readImage(hdu)
                val wcs = Wcs(hdu.header).takeIf { it.valid }
                runOnUiThread { panel.setSource(img, wcs, "[${hdu.index}] ${hdu.name}") }
            } catch (e: Exception) {
                runOnUiThread { panel.status("读取失败: ${e.message}") }
            }
        }
    }
}
