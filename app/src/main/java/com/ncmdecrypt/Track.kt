package com.ncmdecrypt

/**
 * A decrypted, playable song. [id] is the stable absolute path of the cached audio file
 * (used as the MediaItem id). [coverPath] points at a sidecar image file extracted from the
 * source (NCM embedded cover) or the decrypted file's own tags, or null when there is no art.
 * [mediaStoreUri] is the public MediaStore Music/FreeNote copy, so tag edits can be mirrored back
 * to it. [publicPath] is the legacy direct File path on Android 9 and below, re-synced after a
 * tag edit. One of [mediaStoreUri] / [publicPath] is set depending on how it was saved.
 */
data class Track(
    val id: String,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val formatTag: String,
    val coverPath: String?,
    val mediaStoreUri: String?,
    val publicPath: String? = null
) {
    /** True when this audio container supports tag editing via jAudioTagger. */
    val tagsEditable: Boolean
        get() = when (filePath.substringAfterLast('.', "").lowercase()) {
            "flac", "mp3", "ogg", "m4a", "mp4", "wav" -> true
            else -> false
        }
}
