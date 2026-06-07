package com.ncmdecrypt

import android.graphics.BitmapFactory
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
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
            setArtwork(tag, newCover)
        }
        audioFile.commit()
    }

    /**
     * Attach front-cover artwork. FLAC is special-cased: jAudioTagger's `FlacTag.setField(Artwork)`
     * throws `UnsupportedOperationException` by design, so the PICTURE block must be built via
     * [FlacTag.createArtworkField] with explicit dimensions. Other containers (MP3/OGG/M4A/WAV)
     * accept the generic `AndroidArtwork` path. Dimensions are read cheaply (bounds only).
     */
    private fun setArtwork(tag: Tag, cover: ByteArray) {
        val mime = detectCoverMime(cover)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(cover, 0, cover.size, opts)
        val w = opts.outWidth.coerceAtLeast(0)
        val h = opts.outHeight.coerceAtLeast(0)
        if (tag is FlacTag) {
            tag.setField(tag.createArtworkField(cover, 3, mime, "", w, h, 24, 0))
        } else {
            val art = AndroidArtwork().apply {
                binaryData = cover
                mimeType = mime
                pictureType = 3 // front cover
                description = ""
                setWidth(w)
                setHeight(h)
            }
            tag.setField(art)
        }
    }

    private fun setOrDelete(tag: org.jaudiotagger.tag.Tag, key: FieldKey, value: String) {
        val v = value.trim()
        if (v.isEmpty()) {
            runCatching { tag.deleteField(key) }
        } else {
            tag.setField(key, v)
        }
    }

    /**
     * Containers jAudioTagger can write tags into. Everything else (raw AAC/ADTS, APE, unknown)
     * is skipped — embedding is a bonus, never a requirement.
     */
    fun isEmbeddable(fmt: AudioFormat): Boolean = when (fmt) {
        AudioFormat.FLAC, AudioFormat.MP3, AudioFormat.OGG, AudioFormat.WAV -> true
        else -> false
    }

    /** Magic-byte sniff for the cover MIME. Defaults to JPEG (NCM covers are JPEG). */
    fun detectCoverMime(bytes: ByteArray): String = when {
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        else -> "image/jpeg"
    }

    /**
     * Best-effort, additive tagging used right after decryption so the public output carries a
     * cover + title/artist/album like other unlockers. Only *missing* text fields are filled and
     * the cover is embedded *only when the file has none* — so a decrypted QMC/KGM output that
     * already carries its own tags is left byte-identical (no commit at all). NCM bodies have no
     * tags, so this is where their header title/artist/album/cover land in the file.
     *
     * Never throws and never touches the audio frames. Unsupported containers are skipped. The
     * file must already have the correct extension (jAudioTagger selects its reader by extension).
     */
    fun embedIfMissing(
        file: File,
        fmt: AudioFormat,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?
    ) {
        if (!isEmbeddable(fmt)) return
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            var changed = false

            fun fillIfBlank(key: FieldKey, value: String?) {
                val v = value?.trim().orEmpty()
                if (v.isEmpty()) return
                val existing = runCatching { tag.getFirst(key) }.getOrNull()
                if (existing.isNullOrBlank()) {
                    tag.setField(key, v)
                    changed = true
                }
            }
            fillIfBlank(FieldKey.TITLE, title)
            fillIfBlank(FieldKey.ARTIST, artist)
            fillIfBlank(FieldKey.ALBUM, album)

            val hasArtwork = runCatching { tag.firstArtwork != null }.getOrDefault(false)
            if (!hasArtwork && cover != null && cover.isNotEmpty()) {
                setArtwork(tag, cover)
                changed = true
            }

            if (changed) audioFile.commit()
        } catch (_: Exception) {
            // Embedding is a bonus; the raw decrypted audio is already correct. Swallow.
        }
    }

    /**
     * Best-effort read of the embedded lyric tag ([FieldKey.LYRICS] → USLT / Vorbis LYRICS comment).
     * Returns null on any failure or when no lyric is present.
     */
    fun readLyrics(file: File): String? = try {
        AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * Additive lyric tagging used right after decryption, mirroring [embedIfMissing]: writes the
     * full synced LRC text (timestamps and all) into [FieldKey.LYRICS] **only when the file has no
     * lyric** — so an output that already carries lyrics stays byte-identical. Maps to the USLT
     * frame (MP3/WAV) or the LYRICS Vorbis comment (FLAC/OGG); SYLT is intentionally not used.
     * Never throws; unsupported containers are skipped. The file must already have its real
     * extension (jAudioTagger selects its reader by extension).
     */
    fun embedLyricsIfMissing(file: File, fmt: AudioFormat, lrc: String?) {
        if (!isEmbeddable(fmt)) return
        val text = lrc?.trim().orEmpty()
        if (text.isEmpty()) return
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            val existing = runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()
            if (existing.isNullOrBlank()) {
                tag.setField(FieldKey.LYRICS, text)
                audioFile.commit()
            }
        } catch (_: Exception) {
            // Embedding is a bonus; the raw decrypted audio is already correct. Swallow.
        }
    }
}
