package com.test.bafangcon

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet

class AssistChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val chart = LineChart(context)

    init {
        addView(chart, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setupChart()
    }

    private fun setupChart() {
        chart.setBackgroundColor(Color.parseColor("#1a1a2e"))
        chart.description.isEnabled = true
        chart.description.text = "Motor power [W]"
        chart.description.textColor = Color.parseColor("#aaaaaa")
        chart.description.textSize = 10f
        chart.legend.isEnabled = true
        chart.legend.textColor = Color.parseColor("#cccccc")
        chart.legend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
        chart.legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
        chart.legend.orientation = Legend.LegendOrientation.HORIZONTAL
        chart.legend.formSize = 12f
        chart.legend.textSize = 10f
        chart.setTouchEnabled(false)
        chart.animateX(800)

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.textColor = Color.parseColor("#aaaaaa")
        xAxis.gridColor = Color.parseColor("#333355")
        xAxis.axisLineColor = Color.parseColor("#555577")
        xAxis.textSize = 9f
        xAxis.setLabelCount(6, false)
        xAxis.axisMinimum = 0f
        xAxis.axisMaximum = 100f

        val leftAxis = chart.axisLeft
        leftAxis.textColor = Color.parseColor("#aaaaaa")
        leftAxis.gridColor = Color.parseColor("#333355")
        leftAxis.axisLineColor = Color.parseColor("#555577")
        leftAxis.textSize = 9f
        leftAxis.setLabelCount(6, false)
        leftAxis.axisMinimum = 0f
        leftAxis.axisMaximum = 600f

        val rightAxis = chart.axisRight
        rightAxis.isEnabled = false
    }

    fun setData(
        levelLabels: List<String>,
        levelColors: List<Int>,
        gearSpeedPercents: List<Int>,
        gearCurrentPercents: List<Int>
    ) {
        val dataSets = mutableListOf<ILineDataSet>()

        for (i in levelLabels.indices) {
            val label = levelLabels.getOrElse(i) { "?" }
            val color = levelColors.getOrElse(i) { Color.WHITE }
            val speedPct = gearSpeedPercents.getOrElse(i) { 0 }.coerceIn(0, 100)
            val currentPct = gearCurrentPercents.getOrElse(i) { 0 }.coerceIn(0, 100)

            val powerLimitW = (currentPct / 100f) * 600f

            val entries = if (powerLimitW <= 0f || speedPct <= 0) {
                listOf(Entry(0f, 0f), Entry(100f, 0f))
            } else {
                val slope = (speedPct / 20f) * 6f
                val breakX = (powerLimitW / slope).coerceAtMost(100f)
                listOf(
                    Entry(0f, 0f),
                    Entry(breakX, powerLimitW),
                    Entry(100f, minOf(100f * slope, powerLimitW))
                )
            }

            val dataSet = LineDataSet(entries, label).apply {
                setColor(color)
                setDrawCircles(false)
                lineWidth = 1.8f
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
                setDrawHighlightIndicators(false)
            }
            dataSets.add(dataSet)
        }

        chart.data = LineData(dataSets)
        chart.invalidate()
    }
}
