package com.fitsviewer.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fitsviewer.app.view.ChartView

/** 显示由 TableActivity 准备好的图表数据 */
class ChartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart)
        title = "图表"
        findViewById<TextView>(R.id.tvChartTitle).text = FitsRepo.chartTitle
        findViewById<ChartView>(R.id.chartView).setData(
            FitsRepo.chartX, FitsRepo.chartY, FitsRepo.chartType,
            FitsRepo.chartXLabel, FitsRepo.chartYLabel
        )
    }
}
