package com.ncmdecrypt

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.slider.Slider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Binds the bottom-sheet player views to [PlayerHub] and animates them in the Apple-Music idiom:
 *  • spring-driven cover scale + corner rounding on play/pause,
 *  • a slide-driven "container expand" morph from the mini bar to the full screen,
 *  • a Palette-derived ambient gradient that cross-fades on every track change,
 *  • fluid seek progress and marquee titles.
 */
class PlayerUiController(
    private val activity: AppCompatActivity,
    private val onEditRequested: () -> Unit
) {
    private val sheet: View = activity.findViewById(R.id.playerSheet)
    private val scrim: View = activity.findViewById(R.id.playerScrim)
    private val ambient: View = activity.findViewById(R.id.ambientBackground)
    private val miniBar: View = activity.findViewById(R.id.miniBar)
    private val fullPlayer: View = activity.findViewById(R.id.fullPlayer)
    private val coverContainer: View = activity.findViewById(R.id.coverContainer)

    private val miniCover: ShapeableImageView = activity.findViewById(R.id.miniCover)
    private val miniTitle: TextView = activity.findViewById(R.id.miniTitle)
    private val miniArtist: TextView = activity.findViewById(R.id.miniArtist)
    private val miniPlayPause: ImageButton = activity.findViewById(R.id.miniPlayPause)
    private val miniNext: ImageButton = activity.findViewById(R.id.miniNext)
    private val miniProgress: LinearProgressIndicator = activity.findViewById(R.id.miniProgress)

    private val fullCover: ShapeableImageView = activity.findViewById(R.id.fullCover)
    private val fullTitle: TextView = activity.findViewById(R.id.fullTitle)
    private val fullArtist: TextView = activity.findViewById(R.id.fullArtist)
    private val seekBar: Slider = activity.findViewById(R.id.seekBar)
    private val positionText: TextView = activity.findViewById(R.id.positionText)
    private val durationText: TextView = activity.findViewById(R.id.durationText)
    private val fullPlayPause: FloatingActionButton = activity.findViewById(R.id.fullPlayPause)
    private val collapseButton: ImageButton = activity.findViewById(R.id.collapseButton)
    private val editButton: ImageButton = activity.findViewById(R.id.editButton)
    private val shuffleButton: ImageButton = activity.findViewById(R.id.shuffleButton)
    private val repeatButton: ImageButton = activity.findViewById(R.id.repeatButton)
    private val prevButton: ImageButton = activity.findViewById(R.id.prevButton)
    private val nextButton: ImageButton = activity.findViewById(R.id.nextButton)
    private val playerTabs: TabLayout = activity.findViewById(R.id.playerTabs)
    private val lyricsView: LyricsView = activity.findViewById(R.id.lyricsView)

    private val behavior = BottomSheetBehavior.from(sheet)
    private val coverExecutor = Executors.newSingleThreadExecutor()
    private val coverLoadToken = AtomicLong(0)
    private val lyricsLoadToken = AtomicLong(0)

    // Springs for the play/pause cover scale (the signature Apple Music effect).
    private val scaleXSpring = spring(fullCover, DynamicAnimation.SCALE_X)
    private val scaleYSpring = spring(fullCover, DynamicAnimation.SCALE_Y)

    private val ambientGradient = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT)
    )
    private var ambientTop = surfaceColor()
    private var ambientBottom = surfaceColor()
    private var ambientAnimator: ValueAnimator? = null
    private var cornerAnimator: ValueAnimator? = null
    private var coverCorner = dp(12f)

    // Change-detection so per-tick renders don't reset marquee / reload covers.
    private var lastTrackId: String? = null
    private var lastPlaying: Boolean? = null
    private var lastRepeat = -1
    private var lastShuffle: Boolean? = null
    private var userSeeking = false

    private val hubListener: (PlayerState) -> Unit = { render(it) }

    fun attach() {
        ambient.background = ambientGradient
        behavior.isHideable = true
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        sheet.visibility = View.VISIBLE
        applySlide(0f)
        miniBar.visibility = View.VISIBLE
        fullPlayer.visibility = View.INVISIBLE

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                applySlide(slideOffset.coerceIn(0f, 1f))
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        sheet.visibility = View.INVISIBLE
                        scrim.visibility = View.GONE
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        fullPlayer.visibility = View.INVISIBLE
                        miniBar.visibility = View.VISIBLE
                        scrim.visibility = View.GONE
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        fullPlayer.visibility = View.VISIBLE
                        miniBar.visibility = View.INVISIBLE
                    }
                    else -> { // dragging / settling
                        sheet.visibility = View.VISIBLE
                        fullPlayer.visibility = View.VISIBLE
                        miniBar.visibility = View.VISIBLE
                    }
                }
            }
        })

        miniBar.setOnClickListener { expand() }
        collapseButton.setOnClickListener { collapse() }
        editButton.setOnClickListener { onEditRequested() }

        val toggle = View.OnClickListener { PlayerHub.togglePlayPause() }
        miniPlayPause.setOnClickListener(toggle)
        fullPlayPause.setOnClickListener(toggle)
        miniNext.setOnClickListener { PlayerHub.next() }
        nextButton.setOnClickListener { PlayerHub.next() }
        prevButton.setOnClickListener { PlayerHub.previous() }
        shuffleButton.setOnClickListener { PlayerHub.toggleShuffle() }
        repeatButton.setOnClickListener { PlayerHub.cycleRepeat() }

        playerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showLyrics(tab.position == 1) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        showLyrics(false)

        seekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { userSeeking = true }
            override fun onStopTrackingTouch(slider: Slider) {
                userSeeking = false
                PlayerHub.seekToFraction(slider.value)
            }
        })
        seekBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val dur = PlayerHub.state.durationMs
                positionText.text = formatTime((dur * value).toLong())
            }
        }

        miniProgress.max = 1000
        PlayerHub.addListener(hubListener)
    }

    fun detach() {
        PlayerHub.removeListener(hubListener)
        coverExecutor.shutdownNow()
    }

    /** True if the back press was consumed by collapsing an expanded player. */
    fun onBackPressed(): Boolean {
        if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            collapse()
            return true
        }
        return false
    }

    fun expand() { behavior.state = BottomSheetBehavior.STATE_EXPANDED }
    fun collapse() { behavior.state = BottomSheetBehavior.STATE_COLLAPSED }

    // ── State rendering ─────────────────────────────────────────────

    private fun render(state: PlayerState) {
        if (!state.hasMedia || state.track == null) {
            if (behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                behavior.state = BottomSheetBehavior.STATE_HIDDEN
            }
            lastTrackId = null
            return
        }

        // Reveal the sheet the first time a track loads.
        if (behavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            sheet.visibility = View.VISIBLE
            sheet.post { behavior.state = BottomSheetBehavior.STATE_COLLAPSED }
        }

        val track = state.track
        if (track.id != lastTrackId) {
            lastTrackId = track.id
            bindTrackText(track)
            loadCover(track)
            loadLyrics(track)
        }

        // Play/pause icon + spring cover scale.
        if (lastPlaying != state.isPlaying) {
            lastPlaying = state.isPlaying
            val icon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            miniPlayPause.setImageResource(icon)
            fullPlayPause.setImageResource(icon)
            animateCover(state.isPlaying)
        }

        // Progress.
        val dur = state.durationMs
        seekBar.isEnabled = dur > 0
        val fraction = if (dur > 0) (state.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
        if (!userSeeking) {
            if (seekBar.value != fraction) seekBar.value = fraction
            positionText.text = formatTime(state.positionMs)
        }
        durationText.text = formatTime(dur)
        miniProgress.setProgressCompat((fraction * 1000).toInt(), true)
        lyricsView.updatePosition(state.positionMs)

        // Repeat / shuffle affordances.
        if (lastRepeat != state.repeatMode) {
            lastRepeat = state.repeatMode
            val (res, active) = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one to true
                Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat to true
                else -> R.drawable.ic_repeat to false
            }
            repeatButton.setImageResource(res)
            tintButton(repeatButton, active)
        }
        if (lastShuffle != state.shuffle) {
            lastShuffle = state.shuffle
            tintButton(shuffleButton, state.shuffle)
        }
    }

    private fun bindTrackText(track: Track) {
        miniTitle.text = track.title
        miniArtist.text = track.artist
        fullTitle.text = track.title
        fullArtist.text = if (track.album.isBlank()) track.artist else "${track.artist} — ${track.album}"
        // (re)start the marquee
        miniTitle.isSelected = true
        fullTitle.isSelected = true
    }

    // ── Cover + ambient gradient ─────────────────────────────────────

    private fun loadCover(track: Track) {
        val token = coverLoadToken.incrementAndGet()
        val path = track.coverPath
        coverExecutor.execute {
            val bmp = path?.let { decodeBitmap(it, 1024) }
            val palette = bmp?.let { runCatching { Palette.from(it).generate() }.getOrNull() }
            val (top, bottom) = ambientColorsFor(palette)
            activity.runOnUiThread {
                if (token != coverLoadToken.get()) return@runOnUiThread // stale
                if (bmp != null) {
                    fullCover.setImageBitmap(bmp)
                    miniCover.setImageBitmap(bmp)
                } else {
                    fullCover.setImageResource(R.drawable.ic_default_cover)
                    miniCover.setImageResource(R.drawable.ic_default_cover)
                }
                animateAmbient(top, bottom)
            }
        }
    }

    /** Toggle the cover/lyrics tab: lyrics replaces cover + title + artist; transport stays. */
    private fun showLyrics(show: Boolean) {
        lyricsView.visibility = if (show) View.VISIBLE else View.GONE
        val coverVis = if (show) View.INVISIBLE else View.VISIBLE
        coverContainer.visibility = coverVis
        fullTitle.visibility = coverVis
        fullArtist.visibility = coverVis
    }

    private fun loadLyrics(track: Track) {
        val token = lyricsLoadToken.incrementAndGet()
        val path = track.lyricsPath
        coverExecutor.execute {
            val lines = path?.let {
                runCatching {
                    LrcParser.parse(File(it).readText(Charsets.UTF_8).removePrefix("﻿"))
                }.getOrNull()
            } ?: emptyList()
            activity.runOnUiThread {
                if (token != lyricsLoadToken.get()) return@runOnUiThread
                lyricsView.setLines(lines)
            }
        }
    }

    private fun ambientColorsFor(palette: Palette?): Pair<Int, Int> {
        if (palette == null) return surfaceColor() to surfaceColor()
        val base = palette.getDarkVibrantColor(
            palette.getVibrantColor(
                palette.getDarkMutedColor(palette.getDominantColor(surfaceColor()))
            )
        )
        val top = ColorUtils.blendARGB(base, Color.BLACK, 0.25f)
        val bottom = ColorUtils.blendARGB(base, Color.BLACK, 0.62f)
        return top to bottom
    }

    private fun animateAmbient(top: Int, bottom: Int) {
        ambientAnimator?.cancel()
        val fromTop = ambientTop
        val fromBottom = ambientBottom
        val evaluator = ArgbEvaluator()
        ambientAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                ambientTop = evaluator.evaluate(f, fromTop, top) as Int
                ambientBottom = evaluator.evaluate(f, fromBottom, bottom) as Int
                ambientGradient.colors = intArrayOf(ambientTop, ambientBottom)
            }
            start()
        }
    }

    // ── Animations ───────────────────────────────────────────────────

    /** Slide morph: cross-fade mini ↔ full and grow the cover as the container expands. */
    private fun applySlide(offset: Float) {
        val miniAlpha = (1f - offset / 0.22f).coerceIn(0f, 1f)
        val fullAlpha = ((offset - 0.12f) / 0.6f).coerceIn(0f, 1f)
        miniBar.alpha = miniAlpha
        miniProgress.alpha = miniAlpha
        fullPlayer.alpha = fullAlpha
        ambient.alpha = fullAlpha
        scrim.alpha = offset * 0.5f
        scrim.visibility = if (offset > 0.001f) View.VISIBLE else View.GONE
        val s = 0.7f + 0.3f * offset
        coverContainer.scaleX = s
        coverContainer.scaleY = s
    }

    private fun animateCover(playing: Boolean) {
        val targetScale = if (playing) 1f else 0.82f
        startSpring(scaleXSpring, targetScale)
        startSpring(scaleYSpring, targetScale)
        animateCorner(if (playing) dp(12f) else dp(28f))
    }

    private fun startSpring(anim: SpringAnimation, target: Float) {
        anim.spring.finalPosition = target
        anim.start()
    }

    private fun animateCorner(target: Float) {
        cornerAnimator?.cancel()
        cornerAnimator = ValueAnimator.ofFloat(coverCorner, target).apply {
            duration = 350
            addUpdateListener { a ->
                coverCorner = a.animatedValue as Float
                fullCover.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(coverCorner)
                    .build()
            }
            start()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun spring(view: View, prop: FloatPropertyCompat<View>) =
        SpringAnimation(view, prop).apply {
            spring = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = 0.62f
            }
        }

    private fun tintButton(button: ImageButton, active: Boolean) {
        val color = if (active) {
            MaterialColors.getColor(button, com.google.android.material.R.attr.colorPrimary)
        } else {
            MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        button.setColorFilter(color)
    }

    private fun decodeBitmap(path: String, reqSize: Int): Bitmap? {
        if (!File(path).exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val largest = max(bounds.outWidth, bounds.outHeight)
            while (largest / sample > reqSize) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun surfaceColor() =
        MaterialColors.getColor(sheet, com.google.android.material.R.attr.colorSurface)

    private fun dp(value: Float) = value * activity.resources.displayMetrics.density
}
