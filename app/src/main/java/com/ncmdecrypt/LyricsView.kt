package com.ncmdecrypt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs

/**
 * Renders time-synced LRC lyrics that scroll with playback: the active line is centered and
 * highlighted, others dim. Display-only (no seek-by-tap in v1). Feed lines via [setLines] and
 * drive it from playback position via [updatePosition]. Self-contained; no player dependency.
 */
class LyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var lines: List<LrcLine> = emptyList()
    private var activeIndex = -1
    private var currentOffset = 0f
    private var targetOffset = 0f

    private val activePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(18f); isFakeBoldText = true
    }
    private val inactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(16f) }
    private val lineSpacing = dp(16f)
    private val emptyText = context.getString(R.string.lyrics_empty)

    private val onSurface =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

    fun setLines(newLines: List<LrcLine>) {
        lines = newLines
        activeIndex = -1
        currentOffset = 0f
        targetOffset = 0f
        invalidate()
    }

    fun updatePosition(positionMs: Long) {
        if (lines.isEmpty()) return
        var idx = -1
        for (i in lines.indices) { if (lines[i].timeMs <= positionMs) idx = i else break }
        if (idx != activeIndex) {
            activeIndex = idx
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (lines.isEmpty()) {
            inactivePaint.color = onSurfaceVariant
            inactivePaint.alpha = 150
            canvas.drawText(emptyText, cx - inactivePaint.measureText(emptyText) / 2f, cy, inactivePaint)
            return
        }
        // Ease the scroll toward the active line being centered.
        targetOffset = activeIndex.coerceAtLeast(0) * lineHeight()
        currentOffset += (targetOffset - currentOffset) * 0.2f
        if (abs(targetOffset - currentOffset) > 0.5f) postInvalidateOnAnimation()

        for (i in lines.indices) {
            val y = cy + i * lineHeight() - currentOffset
            if (y < -lineHeight() || y > height + lineHeight()) continue
            val paint = if (i == activeIndex) activePaint else inactivePaint
            paint.color = if (i == activeIndex) onSurface else onSurfaceVariant
            paint.alpha = if (i == activeIndex) 255 else 140
            val text = lines[i].text
            canvas.drawText(text, cx - paint.measureText(text) / 2f, y, paint)
        }
    }

    private fun lineHeight(): Float = activePaint.textSize + lineSpacing
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
