package com.ncmdecrypt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs

/**
 * Renders time-synced LRC lyrics that scroll with playback: the active line is centered and
 * highlighted, the others dim. Long lines wrap to multiple centered rows (each line is laid out as
 * a [StaticLayout] sized to the view width), so nothing is clipped horizontally. Display-only (no
 * seek-by-tap in v1). Feed lines via [setLines]; drive from playback via [updatePosition].
 * Self-contained; no player dependency.
 */
class LyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var lines: List<LrcLine> = emptyList()
    private var layouts: List<StaticLayout> = emptyList()   // one wrapped block per line
    private var tops: FloatArray = FloatArray(0)             // content-space top of each block
    private var builtWidth = -1

    private var activeIndex = -1
    private var currentOffset = 0f                            // animated scroll (content px at view center)
    private var targetOffset = 0f

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(17f) }
    private val lineGap = dp(22f)
    private val sidePad = dp(28f)
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
        builtWidth = -1
        layouts = emptyList()
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

    /** (Re)build one centered, width-wrapped [StaticLayout] per line when the width or lines change. */
    private fun ensureLayouts() {
        val w = width - 2 * sidePad.toInt()
        if (w <= 0) return
        if (builtWidth == w && layouts.size == lines.size) return
        builtWidth = w
        textPaint.isFakeBoldText = false
        val built = ArrayList<StaticLayout>(lines.size)
        val newTops = FloatArray(lines.size)
        var acc = 0f
        for (i in lines.indices) {
            val text = lines[i].text
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            built.add(layout)
            newTops[i] = acc
            acc += layout.height + lineGap
        }
        layouts = built
        tops = newTops
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (lines.isEmpty()) {
            textPaint.isFakeBoldText = false
            textPaint.color = onSurfaceVariant
            textPaint.alpha = 150
            canvas.drawText(emptyText, cx - textPaint.measureText(emptyText) / 2f, cy, textPaint)
            return
        }
        ensureLayouts()
        if (layouts.isEmpty()) return

        // Scroll so the active line's block centre sits at the view centre.
        val ai = activeIndex.coerceIn(0, layouts.size - 1)
        targetOffset = tops[ai] + layouts[ai].height / 2f
        currentOffset += (targetOffset - currentOffset) * 0.18f
        if (abs(targetOffset - currentOffset) > 0.5f) postInvalidateOnAnimation()

        for (i in layouts.indices) {
            val layout = layouts[i]
            val top = cy - currentOffset + tops[i]
            if (top + layout.height < 0f || top > height) continue   // cull off-screen
            val active = i == activeIndex
            textPaint.color = if (active) onSurface else onSurfaceVariant
            textPaint.alpha = if (active) 255 else 120
            textPaint.isFakeBoldText = active
            canvas.save()
            canvas.translate(sidePad, top)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
