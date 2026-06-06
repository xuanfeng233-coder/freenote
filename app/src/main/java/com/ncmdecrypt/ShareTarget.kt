package com.ncmdecrypt

/**
 * Decides what a "share" action should hand off.
 *
 * A decrypted song is written to the user's *real* destination — MediaStore `Music/FreeNote` on
 * Android 10+, or a user-chosen SAF folder — AND mirrored to a private internal-cache copy used
 * for in-app playback and as a last-resort share fallback. Sharing must point at the real saved
 * file (its MediaStore / SAF `content://` uri), NOT the cache copy: the cache copy is a duplicate
 * the user never sees in their file manager, and several OEM share sheets / target apps fail to
 * read a `content://…fileprovider/…` uri backed by another app's internal cache. The cache copy is
 * only used when there is no public copy at all (legacy Android with storage write denied).
 */
object ShareTarget {
    /**
     * The real saved-output content uri to share, or null to fall back to the cached FileProvider
     * copy. [mediaStoreUri] is [Track.mediaStoreUri] — the MediaStore uri for the default folder or
     * the SAF document uri for a custom folder.
     */
    fun preferredOutputUri(mediaStoreUri: String?): String? =
        mediaStoreUri?.trim()?.takeIf { it.isNotEmpty() }
}
