package com.ncmdecrypt

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Immutable snapshot of playback, pushed to the UI on every relevant change + on each tick. */
data class PlayerState(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val track: Track? = null,
    val index: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false
)

/**
 * Single point of access to playback for the whole app. Connects a [MediaController] to
 * [PlaybackService], holds the current queue of [Track]s, and re-broadcasts player events as
 * a simple [PlayerState] that the UI layer ([PlayerUiController]) renders. All callbacks run
 * on the main thread.
 */
object PlayerHub {

    private val listeners = CopyOnWriteArrayList<(PlayerState) -> Unit>()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var tracks: List<Track> = emptyList()
    private var pending: (() -> Unit)? = null

    var state: PlayerState = PlayerState()
        private set

    /** Set by the UI to surface playback errors (e.g. an unsupported APE file) to the user. */
    var onError: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val c = controller ?: return
            if (c.isPlaying) {
                publish()
                handler.postDelayed(this, 250)
            }
        }
    }

    fun init(context: Context) {
        if (controllerFuture != null) return
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context.applicationContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = try { future.get() } catch (_: Exception) { null }
            controller?.addListener(playerListener)
            pending?.let { it(); pending = null }
            publish()
        }, ContextCompat.getMainExecutor(context.applicationContext))
    }

    fun addListener(l: (PlayerState) -> Unit) {
        listeners.add(l)
        l(state) // deliver current state immediately
    }

    fun removeListener(l: (PlayerState) -> Unit) = listeners.remove(l)

    /** True once the controller has connected to the service. */
    val isConnected: Boolean get() = controller != null

    fun currentTracks(): List<Track> = tracks

    private fun runWhenReady(block: () -> Unit) {
        if (controller != null) block() else pending = block
    }

    /** Replace the queue and start playback at [startIndex]. */
    fun setQueue(newTracks: List<Track>, startIndex: Int, play: Boolean = true) {
        tracks = newTracks
        runWhenReady {
            val c = controller ?: return@runWhenReady
            val items = newTracks.map { it.toMediaItem() }
            c.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
            c.prepare()
            c.playWhenReady = play
            publish()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
    }

    fun togglePlayPause() = runWhenReady {
        val c = controller ?: return@runWhenReady
        if (c.isPlaying) c.pause() else { c.play() }
    }

    fun play() = runWhenReady { controller?.play() }
    fun pause() = runWhenReady { controller?.pause() }
    fun next() = runWhenReady { controller?.seekToNext() }
    fun previous() = runWhenReady { controller?.seekToPrevious() }
    fun seekTo(ms: Long) = runWhenReady { controller?.seekTo(ms); publish() }

    fun seekToFraction(fraction: Float) = runWhenReady {
        val c = controller ?: return@runWhenReady
        val dur = c.duration
        if (dur > 0) c.seekTo((dur * fraction).toLong())
        publish()
    }

    fun cycleRepeat() = runWhenReady {
        val c = controller ?: return@runWhenReady
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() = runWhenReady {
        val c = controller ?: return@runWhenReady
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Mirror an edited [Track] (new tags / cover) into the live queue + notification. */
    fun replaceTrack(index: Int, newTrack: Track) = runWhenReady {
        if (index !in tracks.indices) return@runWhenReady
        tracks = tracks.toMutableList().also { it[index] = newTrack }
        controller?.replaceMediaItem(index, newTrack.toMediaItem())
        publish()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
            if (player.isPlaying) {
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            onError?.invoke(error.localizedMessage ?: error.errorCodeName)
        }
    }

    private fun publish() {
        val c = controller
        state = if (c == null || c.mediaItemCount == 0) {
            PlayerState()
        } else {
            val idx = c.currentMediaItemIndex
            PlayerState(
                hasMedia = true,
                isPlaying = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                track = tracks.getOrNull(idx),
                index = idx,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = if (c.duration > 0) c.duration else 0,
                repeatMode = c.repeatMode,
                shuffle = c.shuffleModeEnabled
            )
        }
        listeners.forEach { it(state) }
    }

    private fun Track.toMediaItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply { coverPath?.let { setArtworkUri(Uri.fromFile(File(it))) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.fromFile(File(filePath)))
            .setMediaMetadata(meta)
            .build()
    }
}
