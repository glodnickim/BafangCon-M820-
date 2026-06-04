package com.test.bafangcon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class AssistLineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class LevelSeries(
        val label: String,
        val color: Int,
        val assistValue: Float,
        val motorValue: Float
    )

    private var series: List<LevelSeries> = emptyList()
    private var maxValue: Float = 100f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x555556
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x4c4c4d
        strokeWidth = 2f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x2e2e2f
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x555556
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    fun setData(
        levels: List<String>,
        colors: List<Int>,
        assistValues: List<Byte>,
        motorValues: List<Byte>
    ) {
        series = levels.mapIndexed { i, label ->
            val av = (assistValues.getOrElse(i) { 0 }.toInt() and 0xFF).toFloat()
            val mv = (motorValues.getOrElse(i) { 0 }.toInt() and 0xFF).toFloat()
            LevelSeries(label, colors.getOrElse(i) { -0x1000000 }, av, mv)
        }
        maxValue = (series.flatMap { listOf(it.assistValue, it.motorValue) }.maxOrNull() ?: 100f)
            .coerceAtLeast(100f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (series.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val padLeft = 100f
        val padRight = 80f
        val padTop = 60f
        val padBottom = 100f
        val chartLeft = padLeft
        val chartRight = w - padRight
        val chartTop = padTop
        val chartBottom = h - padBottom
        val chartW = chartRight - chartLeft
        val chartH = chartBottom - chartTop
        if (chartW <= 0 || chartH <= 0) return

        val x0 = chartLeft
        val x1 = chartRight

        // grid lines
        val gridStep = (maxValue / 5f).coerceAtLeast(1f)
        var gv = 0f
        while (gv <= maxValue) {
            val gy = chartBottom - (gv / maxValue) * chartH
            canvas.drawLine(chartLeft, gy, chartRight, gy, gridPaint)
            canvas.drawText("${gv.toInt()}", chartLeft - 16f, gy + 12f, labelPaint)
            gv += gridStep
        }

        // x-axis labels
        val xAssist = x0 + chartW * 0.25f
        val xMotor = x0 + chartW * 0.75f
        canvas.drawText("Assist Level", xAssist, chartBottom + 48f, labelPaint)
        canvas.drawText("Motor Power", xMotor, chartBottom + 48f, labelPaint)

        // axes
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        val pointRadius = 14f
        val strokeWidth = 6f

        for (s in series) {
            val yAssist = chartBottom - (s.assistValue / maxValue) * chartH
            val yMotor = chartBottom - (s.motorValue / maxValue) * chartH

            linePaint.color = s.color
            linePaint.strokeWidth = strokeWidth.toFloat()

            val path = Path()
            path.moveTo(xAssist, yAssist)
            path.lineTo(xMotor, yMotor)
            canvas.drawPath(path, linePaint)

            pointPaint.color = s.color
            canvas.drawCircle(xAssist, yAssist, pointRadius, pointPaint)
            canvas.drawCircle(xMotor, yMotor, pointRadius, pointPaint)

            // value labels
            canvas.drawText(
                "${s.assistValue.toInt()}%", xAssist, yAssist - 20f, textPaint
            )
            canvas.drawText(
                "${s.motorValue.toInt()}%", xMotor, yMotor - 20f, textPaint
            )
        }

        // legend
        val legendY = chartBottom + 80f
        var legendX = chartLeft + 20f
        for (s in series) {
            linePaint.strokeWidth = 8f
            linePaint.color = s.color
            canvas.drawLine(legendX, legendY, legendX + 40f, legendY, linePaint)
            labelPaint.textSize = 28f
            labelPaint.color = s.color
            canvas.drawText(s.label, legendX + 60f, legendY + 10f, labelPaint)
            legendX += 120f
        }
    }
}
