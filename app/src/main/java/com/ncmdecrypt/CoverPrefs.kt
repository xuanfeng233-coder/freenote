package com.ncmdecrypt

import android.content.Context

/**
 * User toggle for online cover completion ("联网补全封面"). Defaults to ON so it works out of the
 * box; users who want the app to stay fully offline can turn it off from the overflow menu. This is
 * the only switch that gates any network access in the app.
 */
object CoverPrefs {

    private const val PREFS = "cover_fetch"
    private const val KEY_ENABLED = "online_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
