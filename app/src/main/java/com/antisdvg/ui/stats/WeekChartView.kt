package com.antisdvg.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import com.antisdvg.R
import com.antisdvg.data.model.DaySummary

/**
 * A tiny dependency-free bar chart of reading minutes over the last 7 days.
 * The current day's bar is highlighted. Zero values render an empty tick so the
 * days stay visually aligned.
 */
class WeekChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.brown)
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.brown)
        alpha = 90
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted_text)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        alpha = 120
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted_text)
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }

    private var week: List<DaySummary> = emptyList()

    /** 0f → 1f while the bars grow from the baseline on each data refresh. */
    private var grow = 0f

    private var animator: ValueAnimator? = null

    fun setData(week: List<DaySummary>) {
        this.week = week
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                grow = it.animatedValue as Float
                invalidate()
            }
        }
        animator?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }



    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (week.isEmpty()) return
        val n = week.size
        val gap = dp(6f)
        val barWidth = (width - gap * (n - 1)) / n.toFloat()
        val max = week.maxOfOrNull { it.pages }?.coerceAtLeast(1) ?: 1
        // Reserve a band below the axis for the period/slice label.
        val baseline = height - dp(4f) - labelPaint.textSize
        val topLimit = dp(6f) + labelPaint.textSize

        for (i in week.indices) {
            val day = week[i]
            val left = i * (barWidth + gap)
            val barHeight = if (day.pages == 0) {
                dp(3f)
            } else {
                ((baseline - topLimit) * (day.pages.toFloat() / max)).coerceAtLeast(dp(8f)) * grow
            }
            val top = baseline - barHeight
            val rect = RectF(left, top, left + barWidth, baseline)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), if (i == n - 1) todayPaint else barPaint)

            // Empty-slice tick marker.
            if (day.pages == 0) {
                canvas.drawLine(left + barWidth / 2f, baseline - dp(6f), left + barWidth / 2f, baseline - dp(1f), tickPaint)
            }

            // Pages read in that slice, written above the bar (only when nonzero).
            if (day.pages > 0) {
                canvas.drawText(
                    day.pages.toString(),
                    left + barWidth / 2f,
                    top - dp(2f),
                    labelPaint
                )
            }

            // Day-of-week / range label under the axis.
            if (!day.label.isNullOrBlank()) {
                canvas.drawText(
                    day.label,
                    left + barWidth / 2f,
                    height - dp(2f),
                    labelPaint
                )
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
