package com.ncmdecrypt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure helpers behind auto-embedding. The actual jAudioTagger write path needs a real
 * audio container, so it is verified by on-device smoke test (per CLAUDE.md), not here.
 */
class MetadataEditorTest {

    @Test
    fun detectsPngCover() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("image/png", MetadataEditor.detectCoverMime(png))
    }

    @Test
    fun detectsJpegCover() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg", MetadataEditor.detectCoverMime(jpeg))
    }

    @Test
    fun defaultsToJpegForUnknownOrTinyData() {
        assertEquals("image/jpeg", MetadataEditor.detectCoverMime(ByteArray(0)))
        assertEquals("image/jpeg", MetadataEditor.detectCoverMime(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun embeddableFormatsAreTaggable() {
        assertTrue(MetadataEditor.isEmbeddable(AudioFormat.FLAC))
        assertTrue(MetadataEditor.isEmbeddable(AudioFormat.MP3))
        assertTrue(MetadataEditor.isEmbeddable(AudioFormat.OGG))
        assertTrue(MetadataEditor.isEmbeddable(AudioFormat.WAV))
    }

    @Test
    fun rawAndUnknownFormatsAreSkipped() {
        assertFalse(MetadataEditor.isEmbeddable(AudioFormat.AAC))
        assertFalse(MetadataEditor.isEmbeddable(AudioFormat.APE))
        assertFalse(MetadataEditor.isEmbeddable(AudioFormat.UNKNOWN))
    }

    @Test
    fun embedLyricsSkipsNonEmbeddableWithoutThrowing() {
        // AAC is not embeddable → early return, no file touched, no throw.
        MetadataEditor.embedLyricsIfMissing(java.io.File("/no/such/file.aac"), AudioFormat.AAC, "[00:01.00]x")
    }

    @Test
    fun embedLyricsSkipsBlankLyricsWithoutThrowing() {
        // Embeddable format but blank/null lyric → early return before touching the file.
        MetadataEditor.embedLyricsIfMissing(java.io.File("/no/such/file.flac"), AudioFormat.FLAC, null)
        MetadataEditor.embedLyricsIfMissing(java.io.File("/no/such/file.flac"), AudioFormat.FLAC, "   ")
    }
}
