package com.ncmdecrypt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class Phase4CoreLogicTest {

    @Test
    fun filenameSanitizerRemovesUnsafePathFragments() {
        val sanitized = FilenameSanitizer.sanitizeBase("  ../bad\\name:*?\"<>|\u0000  ")

        assertEquals("bad name", sanitized)
    }

    @Test
    fun filenameSanitizerKeepsReadableUnicodeAndLimitsByCodePoint() {
        val sanitized = FilenameSanitizer.sanitizeBase("歌名 😀 remix version", maxCodePoints = 6)

        assertEquals("歌名 😀 r", sanitized)
    }

    @Test
    fun filenameSanitizerFallsBackWhenNothingUsableRemains() {
        val sanitized = FilenameSanitizer.sanitizeFileName(".../\u0007\\\\", fallback = "fallback.name")

        assertEquals("fallback.name", sanitized)
    }

    @Test
    fun ekeyStoreParsesJsonFixtureAndFiltersInvalidValues() {
        val parsed = EkeyStore.parseImportedKeys(
            """
            {
              "song.mflac0.flac": "QUJDREVGR0hJSktMTU5PUA==",
              "ignore.mflac": "not an ekey"
            }
            """.trimIndent().toByteArray()
        )

        assertEquals(mapOf("song.mflac0.flac" to "QUJDREVGR0hJSktMTU5PUA=="), parsed)
    }

    @Test
    fun ekeyStoreParsesLineBasedTextFixture() {
        val parsed = EkeyStore.parseImportedKeys(
            """
            # comments are ignored
            first.mflac	QUJDREVGR0hJSktMTU5PUA==
            second.mgg=YWJjZGVmZ2hpamtsbW5vcA==
            third.qmc,MTIzNDU2Nzg5MGFiY2RlZg==
            malformed line
            """.trimIndent().toByteArray()
        )

        assertEquals(
            mapOf(
                "first.mflac" to "QUJDREVGR0hJSktMTU5PUA==",
                "second.mgg" to "YWJjZGVmZ2hpamtsbW5vcA==",
                "third.qmc" to "MTIzNDU2Nzg5MGFiY2RlZg=="
            ),
            parsed
        )
    }

    @Test
    fun ekeyStoreParsesMmkvFixtureWithLastWriteWinsAndDeletes() {
        val data = mmkv(
            "deleted.mflac" to "QUJDREVGR0hJSktMTU5PUA==".toByteArray(),
            "song.mflac0.flac" to "oldValueIgnored==".toByteArray(),
            "deleted.mflac" to null,
            "song.mflac0.flac" to "YWJjZGVmZ2hpamtsbW5vcA==".toByteArray()
        )

        val parsed = EkeyStore.parseImportedKeys(data)

        assertEquals(mapOf("song.mflac0.flac" to "YWJjZGVmZ2hpamtsbW5vcA=="), parsed)
    }

    @Test
    fun qmcSTagRejectsOversizedFooterMetadataBeforeAllocating() {
        val data = byteArrayOf(0x01, 0x02) + be32(1_048_577) + "STag".ascii()

        val ex = assertDecryptFails(data, "bad.qmc")

        assertEquals("QMC 尾部元数据长度非法", ex.message)
    }

    @Test
    fun qmcQTagRejectsNegativeAudioLength() {
        val data = byteArrayOf(0x01, 0x02) + be32(3) + "QTag".ascii()

        val ex = assertDecryptFails(data, "bad.qmc")

        assertEquals("QMC 尾部元数据长度非法", ex.message)
    }

    @Test
    fun qmcMusicExPassesMediaFileNameCandidatesToExternalResolver() {
        val data = musicExFixture(mediaFileName = "vault-name.mflac")
        var candidates: List<String> = emptyList()

        val ex = assertDecryptFails(data, "selected.mflac0.flac") { names ->
            candidates = names
            null
        }

        assertTrue(candidates.contains("vault-name.mflac"))
        assertTrue(candidates.contains("selected.mflac0.flac"))
        assertTrue(ex.message!!.contains("密钥未嵌入文件"))
    }

    @Test
    fun ncmRejectsOversizedKeyLength() {
        val data = "CTENFDAM".ascii() + byteArrayOf(0, 0) + le32(1_048_577)

        val ex = assertDecryptFails(data, "bad.ncm")

        assertEquals("NCM 密钥长度非法", ex.message)
    }

    @Test
    fun ncmRejectsTruncatedKeyData() {
        val data = "CTENFDAM".ascii() + byteArrayOf(0, 0) + le32(16) + byteArrayOf(1, 2, 3)

        val ex = assertDecryptFails(data, "bad.ncm")

        assertEquals("NCM 密钥数据不完整", ex.message)
    }

    @Test
    fun kgmRejectsInvalidAudioOffset() {
        val data = ByteArray(0x3C)
        System.arraycopy(KGM_MAGIC, 0, data, 0, KGM_MAGIC.size)
        writeLe32(data, 0x10, 0x20)
        writeLe32(data, 0x14, 3)
        writeLe32(data, 0x18, 1)

        val ex = assertDecryptFails(data, "bad.kgm")

        assertEquals("KGM 音频偏移非法", ex.message)
    }

    @Test
    fun kwmRejectsShortFileBeforeSeekingPastHeader() {
        val data = ByteArray(0x40)
        System.arraycopy("yeelion-kuwo-tme".ascii(), 0, data, 0, "yeelion-kuwo-tme".length)

        val ex = assertDecryptFails(data, "bad.kwm")

        assertEquals("KWM 文件过短", ex.message)
    }

    @Test
    fun audioFormatDetectionCoversKnownHeaders() {
        assertEquals(AudioFormat.MP3, MusicDecoder.detectAudioFormat(byteArrayOf(0xFF.toByte(), 0xFB.toByte())))
        assertEquals(AudioFormat.MP3, MusicDecoder.detectAudioFormat("ID3".ascii()))
        assertEquals(AudioFormat.FLAC, MusicDecoder.detectAudioFormat("fLaC".ascii()))
        assertEquals(AudioFormat.OGG, MusicDecoder.detectAudioFormat("OggS".ascii()))
        assertEquals(AudioFormat.WAV, MusicDecoder.detectAudioFormat("RIFF----WAVE".ascii()))
        assertEquals(AudioFormat.APE, MusicDecoder.detectAudioFormat("#!AML".ascii()))
        assertEquals(AudioFormat.AAC, MusicDecoder.detectAudioFormat(byteArrayOf(0x41, 0x41, 0x43, 0x00)))
        assertEquals(AudioFormat.AAC, MusicDecoder.detectAudioFormat(byteArrayOf(0, 0, 0, 0) + "ftyp".ascii()))
        assertEquals(AudioFormat.UNKNOWN, MusicDecoder.detectAudioFormat(byteArrayOf(0x12, 0x34, 0x56)))
    }

    @Test
    fun formatTagUsesExtensionSegmentsAndMagicFallback() {
        assertEquals("QMC", MusicDecoder.formatTag(null, "song.mflac0.flac"))
        assertEquals("KWM", MusicDecoder.formatTag(KWM_MAGIC_1, "renamed.bin"))
        assertEquals("???", MusicDecoder.formatTag(null, "plain.bin"))
    }

    private fun assertDecryptFails(
        data: ByteArray,
        filename: String,
        resolver: MusicDecoder.EkeyResolver? = null
    ): DecryptException {
        return try {
            MusicDecoder.decrypt(data, filename, resolver)
            fail("Expected DecryptException for $filename")
            throw AssertionError("unreachable")
        } catch (ex: DecryptException) {
            ex
        }
    }

    private fun mmkv(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0, 0, 0, 0))
        for ((key, value) in entries) {
            body.write(varint(key.toByteArray().size))
            body.write(key.toByteArray())
            if (value == null) {
                body.write(varint(0))
            } else {
                val wrapped = varint(value.size) + value
                body.write(varint(wrapped.size))
                body.write(wrapped)
            }
        }
        val validRegion = body.toByteArray()
        return le32(validRegion.size) + validRegion
    }

    private fun musicExFixture(mediaFileName: String): ByteArray {
        val tagSize = 128
        val audioLen = 3
        val data = ByteArray(audioLen + tagSize)
        val tagStart = audioLen
        val headerOffset = data.size - 16
        writeLe32(data, headerOffset, tagSize)
        val encodedName = ByteArray(mediaFileName.length * 2 + 2)
        for (i in mediaFileName.indices) {
            encodedName[i * 2] = mediaFileName[i].code.toByte()
        }
        System.arraycopy(encodedName, 0, data, tagStart + 0x48, encodedName.size)
        System.arraycopy("cex\u0000".ascii(), 0, data, data.size - 4, 4)
        return data
    }

    private fun varint(value: Int): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.add(v.toByte())
                return out.toByteArray()
            }
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte()
    )

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun writeLe32(target: ByteArray, offset: Int, value: Int) {
        val bytes = le32(value)
        System.arraycopy(bytes, 0, target, offset, bytes.size)
    }

    private fun String.ascii(): ByteArray = toByteArray(Charsets.ISO_8859_1)

    private companion object {
        val KGM_MAGIC = byteArrayOf(
            0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(), 0x86.toByte(), 0x02, 0x7F, 0x4B,
            0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(), 0x0F,
            0xFF.toByte(), 0x99.toByte(), 0x14
        )
        val KWM_MAGIC_1 = "yeelion-kuwo-tme".toByteArray(Charsets.ISO_8859_1)
    }
}
