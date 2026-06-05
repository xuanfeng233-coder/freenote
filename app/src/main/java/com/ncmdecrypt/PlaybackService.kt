package com.ncmdecrypt

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the ExoPlayer + MediaSession so decrypted audio keeps playing in the background
 * with a system media notification and lock-screen controls. The UI talks to this service
 * through a [androidx.media3.session.MediaController] (see [PlayerHub]).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    private val trustedMediaControllerPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.bluetooth",
        "com.google.android.gms",
        "com.google.android.projection.gearhead"
    )

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // Route through the music stream + let ExoPlayer manage audio focus (pause on
            // calls / other apps, duck appropriately) and pause when headphones unplug.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    @UnstableApi
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (isAllowedController(controllerInfo)) mediaSession else null

    /**
     * The service stays exported so Android's media framework can provide notification,
     * lock-screen, Bluetooth, and Android Auto controls. Unknown third-party packages do not get a
     * session; only this app, framework UID callers, and trusted system/media packages are allowed.
     */
    @UnstableApi
    private fun isAllowedController(controllerInfo: MediaSession.ControllerInfo): Boolean {
        val controllerPackage = controllerInfo.packageName
        if (controllerPackage == packageName) return true
        if (controllerInfo.uid == Process.SYSTEM_UID) return true
        if (!controllerInfo.isTrusted) return false
        if (controllerPackage in trustedMediaControllerPackages) return true
        return isSystemPackage(controllerPackage, controllerInfo.uid)
    }

    @Suppress("DEPRECATION")
    private fun isSystemPackage(controllerPackage: String, controllerUid: Int): Boolean {
        return try {
            val uidPackages = packageManager.getPackagesForUid(controllerUid) ?: return false
            if (controllerPackage !in uidPackages) return false
            val appInfo = packageManager.getApplicationInfo(controllerPackage, 0)
            val flags = appInfo.flags
            flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        } catch (_: Exception) {
            false
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
