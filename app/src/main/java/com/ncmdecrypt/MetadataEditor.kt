package com.ncmdecrypt

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads and writes audio container tags (title / artist / album / cover art) via jAudioTagger.
 * Supports FLAC / MP3 / OGG(Vorbis) / M4A / WAV — the formats our decoder emits that carry
 * editable tags. Throws on unsupported containers (callers guard with [Track.tagsEditable]).
 */
object MetadataEditor {

    init {
        // jAudioTagger is extremely chatty; silence it.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    data class Tags(
        val title: String,
        val artist: String,
        val album: String,
        val coverBytes: ByteArray?
    )

    /** Best-effort read of existing tags. Returns null on any failure. */
    fun read(file: File): Tags? = try {
        val tag = AudioFileIO.read(file).tag
        if (tag == null) null else Tags(
            title = tag.getFirst(FieldKey.TITLE) ?: "",
            artist = tag.getFirst(FieldKey.ARTIST) ?: "",
            album = tag.getFirst(FieldKey.ALBUM) ?: "",
            coverBytes = runCatching { tag.firstArtwork?.binaryData }.getOrNull()
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Write tags back into [file]. When [newCover] is non-null the existing artwork is replaced;
     * when it is null the artwork is left untouched. Blank text fields are deleted. Runs on the
     * caller's (background) thread.
     */
    fun write(
        file: File,
        title: String,
        artist: String,
        album: String,
        newCover: ByteArray?,
        coverMime: String = "image/jpeg"
    ) {
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault
        setOrDelete(tag, FieldKey.TITLE, title)
        setOrDelete(tag, FieldKey.ARTIST, artist)
        setOrDelete(tag, FieldKey.ALBUM, album)
        if (newCover != null && newCover.isNotEmpty()) {
            tag.deleteArtworkField()
            val art = AndroidArtwork().apply {
                binaryData = newCover
                mimeType = coverMime
                pictureType = 3 // front cover
                description = ""
            }
            tag.setField(art)
        }
        audioFile.commit()
    }

    private fun setOrDelete(tag: org.jaudiotagger.tag.Tag, key: FieldKey, value: String) {
        val v = value.trim()
        if (v.isEmpty()) {
            runCatching { tag.deleteField(key) }
        } else {
            tag.setField(key, v)
        }
    }
}
