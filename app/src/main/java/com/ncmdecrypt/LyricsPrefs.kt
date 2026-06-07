package com.ncmdecrypt

import android.content.Context

/**
 * User toggle for online lyric completion ("联网补全歌词"). Defaults to ON. Independent from
 * [CoverPrefs] so cover and lyric network use can be toggled separately. When off, no lyric
 * network call is made (already-embedded lyrics are still surfaced — that uses no network).
 */
object LyricsPrefs {

    private const val PREFS = "lyrics_fetch"
    private const val KEY_ENABLED = "online_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
