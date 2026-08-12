package com.fitsviewer.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fitsviewer.app.fits.FitsTable
import com.fitsviewer.app.fits.Hdu
import com.fitsviewer.app.view.ChartView
import java.util.concurrent.Executors

/**
 * 表格查看: 列名/单位/格式 + 数据行；支持按列或按行绘制折线/散点/柱状图。
 */
class TableActivity : AppCompatActivity() {

    private lateinit var hdu: Hdu
    private lateinit var table: FitsTable
    private val executor = Executors.newSingleThreadExecutor()

    private var rows: List<Array<String>> = emptyList()
    private var colWidths: IntArray = IntArray(0)
    private lateinit var adapter: RowAdapter

    companion object {
        const val MAX_DISPLAY_ROWS = 2000
        const val MAX_PLOT_ROWS = 50000
        val CHART_TYPES = arrayOf("折线图", "散点图", "柱状图")
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table)

        hdu = MainActivity.hduFromIntent(this) ?: run { finish(); return }
        title = "表格 — [${hdu.index}] ${hdu.name}"

        try {
            table = FitsRepo.fits!!.openTable(hdu)
        } catch (e: Exception) {
            Toast.makeText(this, "表格解析失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val info = findViewById<TextView>(R.id.tvTableInfo)
        info.text = "${if (table.isBinary) "二进制表" else "ASCII表"}: " +
                "${table.nRows} 行 × ${table.columns.size} 列" +
                if (table.nRows > MAX_DISPLAY_ROWS) " (仅显示前 $MAX_DISPLAY_ROWS 行)" else ""

        adapter = RowAdapter()
        findViewById<ListView>(R.id.lvRows).adapter = adapter
        findViewById<Button>(R.id.btnPlot).setOnClickListener { showPlotDialog() }

        loadRows()
    }

    private fun loadRows() {
        executor.execute {
            try {
                val data = table.readRows(0, MAX_DISPLAY_ROWS)
                // 计算每列显示宽度: 列名/单位/样本单元格的最大长度 (上限 24)
                val widths = IntArray(table.columns.size) { c ->
                    var w = maxOf(table.columns[c].name.length,
                        "[${table.columns[c].unit}]".length, 6)
                    for (r in data.indices step maxOf(1, data.size / 50)) {
                        w = maxOf(w, data[r][c].length)
                    }
                    minOf(w, 24)
                }
                runOnUiThread {
                    rows = data
                    colWidths = widths
                    renderHeader()
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pad(s: String, w: Int): String {
        val t = if (s.length > w) s.substring(0, w - 1) + "…" else s
        return t.padEnd(w + 1)
    }

    private fun renderHeader() {
        val rowNumW = 6
        val names = StringBuilder("#".padEnd(rowNumW + 1))
        val units = StringBuilder(" ".repeat(rowNumW + 1))
        for ((c, col) in table.columns.withIndex()) {
            names.append(pad(col.name, colWidths[c]))
            units.append(pad(if (col.unit.isEmpty()) "-" else "[${col.unit}]", colWidths[c]))
        }
        findViewById<TextView>(R.id.tvColNames).text = names.toString()
        findViewById<TextView>(R.id.tvColUnits).text = units.toString()
    }

    private fun formatRow(index: Int): String {
        val sb = StringBuilder((index + 1).toString().padEnd(7))
        val row = rows[index]
        for (c in row.indices) sb.append(pad(row[c], colWidths[c]))
        return sb.toString()
    }

    // ---------- 绘图 ----------

    private fun showPlotDialog() {
        val numericCols = table.columns.filter { it.isNumeric }
        if (numericCols.isEmpty()) {
            Toast.makeText(this, "该表没有可绘制的数值列", Toast.LENGTH_SHORT).show()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_plot, null)
        val spX = view.findViewById<Spinner>(R.id.spX)
        val spY = view.findViewById<Spinner>(R.id.spY)
        val spType = view.findViewById<Spinner>(R.id.spChartType)
        val rbByRow = view.findViewById<RadioButton>(R.id.rbByRow)
        val layColumn = view.findViewById<View>(R.id.layColumn)
        val layRow = view.findViewById<View>(R.id.layRow)
        val etRow = view.findViewById<EditText>(R.id.etRow)

        val colNames = numericCols.map { "${it.name}${if (it.unit.isNotEmpty()) " [${it.unit}]" else ""}" }
        spX.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("行号") + colNames)
        spY.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colNames)
        spType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CHART_TYPES)

        view.findViewById<android.widget.RadioGroup>(R.id.rgMode).setOnCheckedChangeListener { _, id ->
            val byRow = id == R.id.rbByRow
            layColumn.visibility = if (byRow) View.GONE else View.VISIBLE
            layRow.visibility = if (byRow) View.VISIBLE else View.GONE
        }

        AlertDialog.Builder(this)
            .setTitle("绘制图表")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("绘制") { _, _ ->
                val type = when (spType.selectedItemPosition) {
                    1 -> ChartView.Type.SCATTER
                    2 -> ChartView.Type.BAR
                    else -> ChartView.Type.LINE
                }
                if (rbByRow.isChecked) {
                    val rowNo = etRow.text.toString().toLongOrNull()
                    if (rowNo == null || rowNo < 1 || rowNo > table.nRows) {
                        Toast.makeText(this, "行号超出范围 1..${table.nRows}", Toast.LENGTH_SHORT).show()
                    } else plotRow(rowNo - 1, type)
                } else {
                    plotColumns(spX.selectedItemPosition, spY.selectedItemPosition, numericCols, type)
                }
            }
            .show()
    }

    /** 按列: X = 行号或选定列, Y = 选定列 */
    private fun plotColumns(
        xSel: Int, ySel: Int,
        numericCols: List<com.fitsviewer.app.fits.TableColumn>,
        type: ChartView.Type
    ) {
        Toast.makeText(this, "正在读取数据…", Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val yCol = numericCols[ySel]
                val y = table.readNumericColumn(yCol.index, MAX_PLOT_ROWS)
                val x: DoubleArray
                val xLabel: String
                if (xSel == 0) {
                    x = DoubleArray(y.size) { (it + 1).toDouble() }
                    xLabel = "行号"
                } else {
                    val xCol = numericCols[xSel - 1]
                    x = table.readNumericColumn(xCol.index, MAX_PLOT_ROWS)
                    xLabel = xCol.name + if (xCol.unit.isNotEmpty()) " [${xCol.unit}]" else ""
                }
                FitsRepo.chartX = x
                FitsRepo.chartY = y
                FitsRepo.chartType = type
                FitsRepo.chartXLabel = xLabel
                FitsRepo.chartYLabel = yCol.name + if (yCol.unit.isNotEmpty()) " [${yCol.unit}]" else ""
                FitsRepo.chartTitle = "${hdu.name}: ${FitsRepo.chartYLabel} vs $xLabel"
                runOnUiThread { startActivity(Intent(this, ChartActivity::class.java)) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 按行: X = 数值列序号, Y = 该行各数值列的值 */
    private fun plotRow(row: Long, type: ChartView.Type) {
        executor.execute {
            try {
                val all = table.readNumericRow(row)
                val xs = ArrayList<Double>()
                val ys = ArrayList<Double>()
                for ((c, col) in table.columns.withIndex()) {
                    if (col.isNumeric && !all[c].isNaN()) {
                        xs.add((c + 1).toDouble()); ys.add(all[c])
                    }
                }
                FitsRepo.chartX = xs.toDoubleArray()
                FitsRepo.chartY = ys.toDoubleArray()
                FitsRepo.chartType = type
                FitsRepo.chartXLabel = "列序号"
                FitsRepo.chartYLabel = "值"
                FitsRepo.chartTitle = "${hdu.name}: 第 ${row + 1} 行"
                runOnUiThread { startActivity(Intent(this, ChartActivity::class.java)) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@TableActivity)
                .inflate(R.layout.item_card, parent, false)
            view.findViewById<TextView>(R.id.tvCard).text = formatRow(position)
            return view
        }
    }
}
