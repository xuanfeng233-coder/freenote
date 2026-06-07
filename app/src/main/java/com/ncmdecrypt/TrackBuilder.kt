package com.ncmdecrypt

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Builds a [Track] for a just-decrypted file: pulls title / artist / album / cover from the
 * NCM header (for `.ncm`) or from the decrypted file's own tags (everything else), writes the
 * cover to a sidecar file next to the cached audio, and fills sensible fallbacks.
 */
object TrackBuilder {

    fun build(
        originalName: String,
        encryptedTempPath: String?,
        decryptedFile: File,
        mediaStoreUri: String?,
        formatTag: String,
        publicPath: String? = null,
        lyrics: String? = null
    ): Track {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var cover: ByteArray? = null

        if (formatTag == "NCM" && encryptedTempPath != null) {
            MusicDecoder.extractNcmInfo(encryptedTempPath)?.let { info ->
                title = info.title
                artist = info.artist
                album = info.album
                cover = info.coverBytes
            }
        }

        // For non-NCM, or to fill any gaps the NCM header left, read the decrypted file's tags.
        if (title.isNullOrBlank() || artist.isNullOrBlank() || cover == null) {
            readWithRetriever(decryptedFile)?.let { r ->
                if (title.isNullOrBlank()) title = r.title
                if (artist.isNullOrBlank()) artist = r.artist
                if (album.isNullOrBlank()) album = r.album
                if (cover == null) cover = r.cover
            }
        }

        // MediaMetadataRetriever silently fails on some valid containers (notably FLAC/OGG with
        // certain tag blocks). jAudioTagger parses those reliably, so use it as a second source
        // for anything still missing. Best-effort: any failure just leaves the gaps for fallbacks.
        if (title.isNullOrBlank() || artist.isNullOrBlank() || album.isNullOrBlank() || cover == null) {
            runCatching { MetadataEditor.read(decryptedFile) }.getOrNull()?.let { t ->
                if (title.isNullOrBlank()) title = t.title.ifBlank { null }
                if (artist.isNullOrBlank()) artist = t.artist.ifBlank { null }
                if (album.isNullOrBlank()) album = t.album.ifBlank { null }
                if (cover == null) cover = t.coverBytes
            }
        }

        val coverPath = cover?.let { writeCoverSidecar(decryptedFile, it) }

        // Lyrics: prefer the value threaded from the decrypt flow (fetched / pre-existing), else
        // read the file's own embedded lyric. Write a sidecar next to the cached copy for the
        // in-app lyrics tab.
        val lyricsText = lyrics?.takeIf { LrcParser.hasRealLyrics(it) }
            ?: MetadataEditor.readLyrics(decryptedFile)?.takeIf { LrcParser.hasRealLyrics(it) }
        val lyricsPath = lyricsText?.let { writeLyricsSidecar(decryptedFile, it) }

        val fallbackTitle = originalName.substringBeforeLast('.').ifBlank { originalName }
        return Track(
            id = decryptedFile.absolutePath,
            filePath = decryptedFile.absolutePath,
            title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            artist = artist?.takeIf { it.isNotBlank() } ?: "未知艺术家",
            album = album?.takeIf { it.isNotBlank() } ?: "",
            formatTag = formatTag,
            coverPath = coverPath,
            lyricsPath = lyricsPath,
            mediaStoreUri = mediaStoreUri,
            publicPath = publicPath
        )
    }

    private class RetrieverResult(
        val title: String?,
        val artist: String?,
        val album: String?,
        val cover: ByteArray?
    )

    private fun readWithRetriever(file: File): RetrieverResult? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            RetrieverResult(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                cover = mmr.embeddedPicture
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Writes cover bytes to "<audio>.cover" next to the cached file; returns its path or null. */
    fun writeCoverSidecar(audioFile: File, bytes: ByteArray): String? = try {
        val sidecarName = FilenameSanitizer.sanitizeFileName(audioFile.name + ".cover", "cover.cover")
        val sidecar = File(audioFile.parentFile, sidecarName)
        sidecar.writeBytes(bytes)
        sidecar.absolutePath
    } catch (_: Exception) {
        null
    }

    /**
     * Writes lyrics to "<audio-name-without-ext>.lrc" next to the cached file, UTF-8 + BOM. NOTE the
     * stem differs from the cover sidecar ("song.lrc", NOT "song.flac.lrc") so players pair it with
     * the audio. Returns its path or null.
     */
    fun writeLyricsSidecar(audioFile: File, lrc: String): String? = try {
        val sidecarName = FilenameSanitizer.sanitizeFileName(audioFile.nameWithoutExtension + ".lrc", "lyrics.lrc")
        val sidecar = File(audioFile.parentFile, sidecarName)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        sidecar.writeBytes(bom + lrc.toByteArray(Charsets.UTF_8))
        sidecar.absolutePath
    } catch (_: Exception) {
        null
    }
}
