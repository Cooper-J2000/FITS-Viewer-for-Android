package com.fitsviewer.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fitsviewer.app.fits.Hdu
import com.fitsviewer.app.store.CanvasStore
import com.fitsviewer.app.store.RecentStore
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvFileInfo: TextView
    private lateinit var lvHdus: ListView
    private lateinit var lvRecent: ListView
    private lateinit var lvCanvas: ListView
    private val executor = Executors.newSingleThreadExecutor()

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFileInfo = findViewById(R.id.tvFileInfo)
        lvHdus = findViewById(R.id.lvHdus)
        lvRecent = findViewById(R.id.lvRecent)
        lvCanvas = findViewById(R.id.lvCanvas)
        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            openDoc.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnCanvas).setOnClickListener {
            startActivity(Intent(this, CanvasActivity::class.java))
        }

        lvHdus.setOnItemClickListener { _, _, pos, _ -> showHduOptions(pos) }
        setupRecentAndCanvas()

        // 文件管理器 "打开方式" 进入
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { loadUri(it) }
        } else if (FitsRepo.fits != null) {
            refreshList()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
        refreshCanvas()
    }

    // ---------- 最近文件 & 画布历史 ----------

    private fun setupRecentAndCanvas() {
        lvRecent.setOnItemClickListener { _, _, pos, _ ->
            RecentStore.list(this).getOrNull(pos)?.let { loadUri(Uri.parse(it.uri)) }
        }
        lvRecent.setOnItemLongClickListener { _, _, pos, _ ->
            val e = RecentStore.list(this).getOrNull(pos) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(arrayOf("从最近列表移除")) { _, _ -> RecentStore.remove(this, e.uri); refreshRecent() }
                .show()
            true
        }
        lvCanvas.setOnItemClickListener { _, _, pos, _ ->
            CanvasStore.list(this).getOrNull(pos)?.let {
                startActivity(Intent(this, CanvasActivity::class.java)
                    .putExtra(CanvasActivity.EXTRA_CANVAS_ID, it.id))
            }
        }
        lvCanvas.setOnItemLongClickListener { _, _, pos, _ ->
            val e = CanvasStore.list(this).getOrNull(pos) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle(e.title)
                .setItems(arrayOf("删除该画布")) { _, _ -> CanvasStore.remove(this, e.id); refreshCanvas() }
                .show()
            true
        }
    }

    private fun refreshRecent() {
        val items = RecentStore.list(this).map { it.name }
        lvRecent.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    private fun refreshCanvas() {
        val items = CanvasStore.list(this).map { it.title }
        lvCanvas.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    private fun loadUri(uri: Uri) {
        tvFileInfo.text = "正在读取…"
        executor.execute {
            try {
                FitsRepo.open(this, uri)
                runOnUiThread { refreshList() }
            } catch (e: Exception) {
                runOnUiThread {
                    tvFileInfo.text = "打开失败: ${e.message}"
                    Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshList() {
        val fits = FitsRepo.fits ?: return
        tvFileInfo.text = "文件: ${FitsRepo.fileName}\n共 ${fits.hdus.size} 个 HDU, " +
                "大小 ${"%.2f".format(fits.file.length() / 1048576.0)} MB"
        val items = fits.hdus.map { hdu ->
            "[${hdu.index}] ${hdu.name}\n    ${hdu.typeString()}   ${hdu.dimsString()}"
        }
        lvHdus.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    private fun showHduOptions(pos: Int) {
        val fits = FitsRepo.fits ?: return
        val hdu = fits.hdus[pos]
        val options = mutableListOf(getString(R.string.view_header))
        if (hdu.hasImage) options.add("查看图像")
        if (hdu.isTable) options.add("查看表格")
        AlertDialog.Builder(this)
            .setTitle("[${hdu.index}] ${hdu.name}")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.view_header) -> startHdu(HeaderActivity::class.java, pos)
                    "查看图像" -> startHdu(ImageActivity::class.java, pos)
                    "查看表格" -> startHdu(TableActivity::class.java, pos)
                }
            }
            .show()
    }

    private fun startHdu(cls: Class<*>, hduIndex: Int) {
        startActivity(Intent(this, cls).putExtra(EXTRA_HDU, hduIndex))
    }

    companion object {
        const val EXTRA_HDU = "hdu_index"

        fun hduFromIntent(activity: AppCompatActivity): Hdu? {
            val idx = activity.intent.getIntExtra(EXTRA_HDU, -1)
            return FitsRepo.fits?.hdus?.getOrNull(idx)
        }
    }
}
