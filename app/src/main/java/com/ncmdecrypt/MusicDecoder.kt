package com.ncmdecrypt

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// ──────────────────────────────────────────────────────────────────
// Types
// ──────────────────────────────────────────────────────────────────

enum class EncryptedFormat {
    /** NetEase Cloud Music (.ncm) — CTCM or CTENFDAM */
    NCM,
    /** QQ Music / Moo Music QMC family (qmc*, mflac*, mgg*, bkc*, tkm, weiyun, mmp4) */
    QMC,
    /** QQ Music iOS .tm0/.tm2/.tm3/.tm6 (plain or faked-header) */
    TM,
    /** Kugou KGM (.kgm, .kgma) */
    KGM,
    /** Kugou KGG (.kgg) */
    KGG,
    /** Kugou VPR (.vpr) */
    VPR,
    /** Kuwo KWM (.kwm) */
    KWM,
    /** Kuwo KWMA (.kwma) */
    KWMA,
    UNKNOWN
}

enum class AudioFormat {
    MP3, FLAC, OGG, AAC, WAV, APE, UNKNOWN
}

/**
 * Thrown when a file is recognised but cannot be decrypted offline — e.g. QQ Music
 * `STag` / `musicex` (.mflac0.flac) files whose key lives in the client database, not
 * in the file itself. The message is user-facing (Chinese).
 */
class DecryptException(message: String) : Exception(message)

data class DecryptResult(
    val audioData: ByteArray,
    val format: AudioFormat
) {
    val extension: String get() = when (format) {
        AudioFormat.MP3 -> ".mp3"
        AudioFormat.FLAC -> ".flac"
        AudioFormat.OGG -> ".ogg"
        AudioFormat.AAC -> ".aac"
        AudioFormat.WAV -> ".wav"
        AudioFormat.APE -> ".ape"
        AudioFormat.UNKNOWN -> ".bin"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecryptResult) return false
        return audioData.contentEquals(other.audioData) && format == other.format
    }

    override fun hashCode(): Int = audioData.contentHashCode() * 31 + format.hashCode()
}

// ──────────────────────────────────────────────────────────────────
// Unified Music Decoder
// ──────────────────────────────────────────────────────────────────

object MusicDecoder {

    private const val BUF_SIZE = 65536  // 64KB chunks

    // ── NCM AES-128-ECB key (neteasecloudmusic built-in) ──────────
    private val SCORE_KEY = byteArrayOf(
        0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
        0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
    )

    // ── AES-128-ECB decrypt helper ───────────────────────────────
    private fun aes128EcbDecrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(SCORE_KEY, "AES"))
        return cipher.doFinal(data)
    }

    // ── Custom buildKeyBox (NOT standard RC4 KSA!) ──────────────
    // NetEase uses a bespoke key-box algorithm in Dump():
    //   last_byte=0; for i in 0..255:
    //     c = (S[i] + last_byte + key[k_off++]) & 0xFF
    //     swap S[i], S[c]; last_byte = c
    private fun buildKeyBox(key: ByteArray): IntArray {
        val S = IntArray(256) { it }
        var lastByte = 0
        var keyOffset = 0
        for (i in 0..255) {
            val swap = S[i]
            val c = (swap + lastByte + (key[keyOffset].toInt() and 0xFF)) and 0xFF
            keyOffset++
            if (keyOffset >= key.size) keyOffset = 0
            S[i] = S[c]
            S[c] = swap
            lastByte = c
        }
        return S
    }

    // ── Custom keyBox XOR decryption (NOT RC4 PRGA!) ────────────
    //   for i in 0..n: j=(i+1)&0xFF; buf[i] ^= KB[(KB[j]+KB[(KB[j]+j)&0xFF])&0xFF]
    private fun keyBoxXorChunk(data: ByteArray, keyBox: IntArray) {
        for (i in data.indices) {
            val j = (i + 1) and 0xFF
            data[i] = (data[i].toInt() xor keyBox[(keyBox[j] + keyBox[(keyBox[j] + j) and 0xFF]) and 0xFF]).toByte()
        }
    }

    // ── Format detection (from header bytes, no OOM) ────────────────

    fun detectFormat(data: ByteArray): EncryptedFormat {
        val size = data.size
        return when {
            size >= 8 && data[0] == 0x43.toByte() && data[1] == 0x54.toByte() &&
                    data[2] == 0x45.toByte() && data[3] == 0x4E.toByte() &&
                    data[4] == 0x46.toByte() && data[5] == 0x44.toByte() &&
                    data[6] == 0x41.toByte() && data[7] == 0x4D.toByte() -> EncryptedFormat.NCM
            size >= 4 && data[0] == 0x43.toByte() && data[1] == 0x54.toByte() &&
                    data[2] == 0x43.toByte() && data[3] == 0x4D.toByte() -> EncryptedFormat.NCM
            size >= 4 && data[0] == 0x4E.toByte() && data[1] == 0x43.toByte() &&
                    data[2] == 0x4D.toByte() && data[3] == 0x43.toByte() -> EncryptedFormat.NCM
            size >= 16 && matchMagic(data, KGM_MAGIC) -> EncryptedFormat.KGM
            size >= 16 && matchMagic(data, VPR_MAGIC) -> EncryptedFormat.VPR
            size >= 16 && (matchMagic(data, KWM_MAGIC1) || matchMagic(data, KWM_MAGIC2)) -> EncryptedFormat.KWM
            else -> EncryptedFormat.UNKNOWN
        }
    }

    private fun matchMagic(data: ByteArray, magic: ByteArray): Boolean {
        if (data.size < magic.size) return false
        for (i in magic.indices) if (data[i] != magic[i]) return false
        return true
    }

    // ── Extension-based detection (QMC/TM have no magic header) ─────
    // The audio body of QMC/TM files is encrypted, so there is no reliable header.
    // We match by dot-delimited extension segment, which also handles double
    // extensions like "song.mflac0.flac" (segment "mflac0" matches).

    private val QMC_EXTS = setOf(
        // QQ Music classic
        "qmc", "qmc0", "qmc2", "qmc3", "qmc4", "qmc6", "qmc8",
        "qmcflac", "qmcogg",
        "tkm",                                                   // QQ Music accompaniment M4A
        // Moo Music (Tencent)
        "bkcmp3", "bkcm4a", "bkcflac", "bkcwav", "bkcape", "bkcogg", "bkcwma",
        // QQ Music Weiyun (hex of "flac"/"mp3"/"ogg"/"m4a"/"wav")
        "666c6163", "6d7033", "6f6767", "6d3461", "776176",
        "mmp4",                                                  // QQ Music MP4 (e.g. Dolby EAC3)
        // QQ Music modern OGG/FLAC + Mac/client variant suffixes (0,1,a,h,l,m)
        "mflac", "mflac0", "mflac1", "mflaca", "mflach", "mflacl", "mflacm",
        "mgg", "mgg0", "mgg1", "mgga", "mggh", "mggl", "mggm"
    )

    private val TM_EXTS = setOf("tm0", "tm2", "tm3", "tm6")

    private fun extSegments(name: String): List<String> =
        name.lowercase().split('.').drop(1)  // drop the base name

    fun isQmcExtension(name: String): Boolean = extSegments(name).any { it in QMC_EXTS }

    private fun isTmExtension(name: String): Boolean = extSegments(name).any { it in TM_EXTS }

    /** Short tag for the file list badge. Used by the UI to label a file before decrypting. */
    fun formatTag(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".ncm") -> "NCM"
            isQmcExtension(name) -> "QMC"
            isTmExtension(name) -> "TM"
            lower.endsWith(".kgm") || lower.endsWith(".kgma") -> "KGM"
            lower.endsWith(".kgg") -> "KGG"
            lower.endsWith(".vpr") -> "VPR"
            lower.endsWith(".kwm") -> "KWM"
            lower.endsWith(".kwma") -> "KWMA"
            else -> "???"
        }
    }

    /** UI tag for an [EncryptedFormat], or null when there is none. */
    private fun tagFor(fmt: EncryptedFormat): String? = when (fmt) {
        EncryptedFormat.NCM -> "NCM"
        EncryptedFormat.QMC -> "QMC"
        EncryptedFormat.TM -> "TM"
        EncryptedFormat.KGM -> "KGM"
        EncryptedFormat.KGG -> "KGG"
        EncryptedFormat.VPR -> "VPR"
        EncryptedFormat.KWM -> "KWM"
        EncryptedFormat.KWMA -> "KWMA"
        EncryptedFormat.UNKNOWN -> null
    }

    /**
     * Best UI tag from the file's name *and* a peek at its header bytes. The extension is most
     * specific (it distinguishes e.g. KWM vs KWMA), so it wins when recognised; otherwise we fall
     * back to the magic-byte signature in [header] (matches what [decryptFile] actually detects),
     * so a magic-detectable file (NCM / KGM / VPR / KWM) that was renamed no longer shows "???".
     */
    fun formatTag(header: ByteArray?, name: String): String {
        val byName = formatTag(name)
        if (byName != "???") return byName
        if (header != null && header.isNotEmpty()) tagFor(detectFormat(header))?.let { return it }
        return "???"
    }

    fun detectAudioFormat(data: ByteArray): AudioFormat {
        return when {
            data.size >= 2 && data[0].toInt() == 0xFF && (data[1].toInt() and 0xE0) == 0xE0 -> AudioFormat.MP3
            data.size >= 4 && data[0] == 0x66.toByte() && data[1] == 0x4C.toByte() &&
                    data[2] == 0x61.toByte() && data[3] == 0x43.toByte() -> AudioFormat.FLAC
            data.size >= 4 && data[0] == 0x4F.toByte() && data[1] == 0x67.toByte() &&
                    data[2] == 0x67.toByte() && data[3] == 0x53.toByte() -> AudioFormat.OGG
            data.size >= 4 && data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                    data[2] == 0x46.toByte() && data[3] == 0x46.toByte() -> AudioFormat.WAV
            data.size >= 5 && data[0] == 0x23.toByte() && data[1] == 0x21.toByte() &&
                    data[2] == 0x41.toByte() && data[3] == 0x4D.toByte() && data[4] == 0x4C.toByte() -> AudioFormat.APE
            data.size >= 4 && data[0] == 0x41.toByte() && data[1] == 0x41.toByte() &&
                    data[2] == 0x43.toByte() && data[3] == 0x00.toByte() -> AudioFormat.AAC
            // ID3-tagged MP3
            data.size >= 3 && data[0] == 0x49.toByte() && data[1] == 0x44.toByte() &&
                    data[2] == 0x33.toByte() -> AudioFormat.MP3
            // M4A / MP4 (ftyp box at offset 4)
            data.size >= 8 && data[4] == 0x66.toByte() && data[5] == 0x74.toByte() &&
                    data[6] == 0x79.toByte() && data[7] == 0x70.toByte() -> AudioFormat.AAC
            else -> AudioFormat.UNKNOWN
        }
    }

    private fun nameToFormat(filename: String): EncryptedFormat {
        val lower = filename.lowercase()
        return when {
            isQmcExtension(filename) -> EncryptedFormat.QMC
            isTmExtension(filename) -> EncryptedFormat.TM
            lower.endsWith(".kgm") || lower.endsWith(".kgma") -> EncryptedFormat.KGM
            lower.endsWith(".vpr") -> EncryptedFormat.VPR
            lower.endsWith(".kgg") -> EncryptedFormat.KGG
            lower.endsWith(".kwm") -> EncryptedFormat.KWM
            lower.endsWith(".kwma") -> EncryptedFormat.KWMA
            else -> EncryptedFormat.UNKNOWN
        }
    }

    // ── Old ByteArray-based method (kept for small-file fallback) ──

    /** Resolves a base64 EKey given candidate file names (for STag/musicex no-key files). */
    fun interface EkeyResolver { fun resolve(candidateNames: List<String>): String? }

    fun decrypt(
        data: ByteArray,
        originalFilename: String,
        ekeyResolver: EkeyResolver? = null
    ): DecryptResult? {
        val fmt = detectFormat(data).let {
            if (it == EncryptedFormat.UNKNOWN) nameToFormat(originalFilename) else it
        }
        return when (fmt) {
            EncryptedFormat.NCM -> decryptNCM(data)
            EncryptedFormat.QMC -> decryptQMC(data, originalFilename, ekeyResolver)
            EncryptedFormat.TM -> decryptTM(data)
            EncryptedFormat.KGM, EncryptedFormat.VPR -> decryptKGM(data)
            EncryptedFormat.KGG -> throw DecryptException(MSG_KGG_DB)
            EncryptedFormat.KWM, EncryptedFormat.KWMA -> decryptKWM(data)
            EncryptedFormat.UNKNOWN -> null
        }
    }

    private fun decryptNCM(data: ByteArray): DecryptResult {
        // === ncmdump-compatible NCM decryption ===
        // Header: magic(8B) + padding(2B) + keyLen(4B LE) + keyData + metaLen(4B LE) + meta...
        var pos = 10 // skip magic (8) + padding (2)

        // Key length (4B LE)
        val keyLen = ((data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24))
        pos += 4

        // Read key data, XOR with 0x64, AES-128-ECB decrypt
        val rawKey = data.copyOfRange(pos, pos + keyLen)
        pos += keyLen
        for (i in rawKey.indices) rawKey[i] = (rawKey[i].toInt() xor 0x64).toByte()
        val decryptedKey = aes128EcbDecrypt(rawKey)

        // buildKeyBox with bytes 17+
        val keyBox = buildKeyBox(decryptedKey.copyOfRange(17, decryptedKey.size))

        // Skip metadata
        val metaLen = ((data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24))
        pos += 4 + metaLen

        // Skip CRC32 (4B) + image version (1B)
        pos += 5

        // Skip cover frame
        val coverFrameLen = ((data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24))
        pos += 4
        val imgLen = ((data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24))
        pos += 4 + imgLen + (coverFrameLen - imgLen)

        // Decrypt audio data with keyBox
        val audioData = data.copyOfRange(pos, data.size)
        val decrypted = ByteArray(audioData.size)
        System.arraycopy(audioData, 0, decrypted, 0, audioData.size)
        keyBoxXorChunk(decrypted, keyBox)

        val fmt = detectAudioFormat(decrypted)
        return DecryptResult(decrypted, fmt)
    }

    // ── NCM embedded metadata + cover art ───────────────────────────
    // NCM files carry the song info (title/artist/album) in an AES-encrypted JSON block
    // and the album art as a raw image frame — both right after the key. The decrypted
    // audio body itself usually has NO container tags, so this is the only metadata source
    // for NCM. Best-effort: any parsing hiccup just yields a null field (never throws).

    // AES-128-ECB key for the "163 key(Don't modify):" metadata block ("#14ljk_!\]&0U<'(")
    private val META_KEY = byteArrayOf(
        0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
        0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
    )

    private fun aes128EcbDecryptWith(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /** Result of [extractNcmInfo]. Any field may be null/empty when not present. */
    class NcmInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val coverBytes: ByteArray?
    )

    /**
     * Reads title/artist/album + cover bytes from an `.ncm` file header. Returns null if the
     * file is not NCM or cannot be parsed. Never throws — every step is guarded so a malformed
     * header simply drops the affected field. Mirrors the byte offsets used by [decryptNCMStream].
     */
    fun extractNcmInfo(inputPath: String): NcmInfo? {
        val raf = RandomAccessFile(inputPath, "r")
        try {
            val magic = ByteArray(8)
            if (raf.read(magic) < 8) return null
            // Accept the common NCM magics (CTENFDAM / CTCM / NCMC) — only the 8-byte CTENFDAM
            // header has the full layout we parse here.
            val isCtenfdam = magic[0] == 0x43.toByte() && magic[1] == 0x54.toByte() &&
                    magic[2] == 0x45.toByte() && magic[3] == 0x4E.toByte() &&
                    magic[4] == 0x46.toByte() && magic[5] == 0x44.toByte() &&
                    magic[6] == 0x41.toByte() && magic[7] == 0x4D.toByte()
            if (!isCtenfdam) return null

            raf.seek(10L) // magic(8) + padding(2)

            val keyLen = readLeU32(raf)
            if (keyLen <= 0 || keyLen > 1 shl 20) return null
            raf.skipBytes(keyLen) // skip key data — we only want metadata + cover here

            // Metadata block
            val metaLen = readLeU32(raf)
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            if (metaLen in 1..(1 shl 22)) {
                val meta = ByteArray(metaLen)
                raf.readFully(meta)
                try {
                    for (i in meta.indices) meta[i] = (meta[i].toInt() xor 0x63).toByte()
                    // strip "163 key(Don't modify):" (22 bytes), the rest is base64
                    val b64 = String(meta, 22, meta.size - 22, Charsets.US_ASCII)
                    val cipherBytes = java.util.Base64.getMimeDecoder().decode(b64)
                    val plain = aes128EcbDecryptWith(META_KEY, cipherBytes)
                    // plain = "music:" + json
                    val jsonStr = String(plain, Charsets.UTF_8).substringAfter("music:", "")
                    if (jsonStr.isNotEmpty()) {
                        val json = org.json.JSONObject(jsonStr)
                        title = json.optString("musicName").ifBlank { null }
                        album = json.optString("album").ifBlank { null }
                        val artists = json.optJSONArray("artist")
                        if (artists != null && artists.length() > 0) {
                            val names = ArrayList<String>()
                            for (i in 0 until artists.length()) {
                                val pair = artists.optJSONArray(i)
                                val n = pair?.optString(0)
                                if (!n.isNullOrBlank()) names.add(n)
                            }
                            if (names.isNotEmpty()) artist = names.joinToString(" / ")
                        }
                    }
                } catch (_: Exception) { /* leave fields null */ }
            }

            // CRC32(4) + gap(1) — matches decryptNCMStream's skipBytes(5)
            raf.skipBytes(5)
            // First length (cover frame; usually 0 in the gap), second is the real image size
            readLeU32(raf)
            val imgLen = readLeU32(raf)
            var cover: ByteArray? = null
            if (imgLen in 1..(1 shl 25)) {
                try {
                    val img = ByteArray(imgLen)
                    raf.readFully(img)
                    cover = img
                } catch (_: Exception) { /* no cover */ }
            }

            if (title == null && artist == null && album == null && cover == null) return null
            return NcmInfo(title, artist, album, cover)
        } catch (_: Exception) {
            return null
        } finally {
            raf.close()
        }
    }

    private fun readLeU32(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
    }

    // ──────────────────────────────────────────────────────────────
    // QQ Music QMCv2 (modern) — EKey + map/RC4/static cipher
    //
    // Ported verbatim from unlock-music CLI v0.2.12 (algo/qmc/*) and validated
    // byte-for-byte against its test vectors (deriveKey, map, rc4, footer parse,
    // chunked streaming). See research notes / QmcValidate.java.
    //
    // Pipeline:
    //   footer  → audioLen + embedded EKey (base64) [or no key → DecryptException]
    //   deriveKey(ekey) → real key (base64 → optional EncV2 → V1 TEA-CBC)
    //   cipher: len>300 → RC4 ; len 1..300 → map ; len 0 → static (legacy QMCv1)
    //   decrypt audio[0, audioLen) with offset-dependent keystream
    // ──────────────────────────────────────────────────────────────

    private const val QMC_KEY_THRESHOLD = 300

    /**
     * @param ekey            embedded base64 EKey, or null
     * @param needsExternalKey true for STag/musicex files whose key is not in the file;
     *                         caller must resolve an EKey via [externalNames] (+ the file name)
     * @param externalNames   candidate names for external-key lookup (e.g. musicex MediaFileName)
     */
    private class QmcFooter(
        val audioLen: Long,
        val ekey: ByteArray?,
        val needsExternalKey: Boolean = false,
        val externalNames: List<String> = emptyList()
    )

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun trimTrailingNul(b: ByteArray): ByteArray {
        var end = b.size
        while (end > 0 && b[end - 1] == 0.toByte()) end--
        return if (end == b.size) b else b.copyOf(end)
    }

    private const val MSG_NO_KEY =
        "此文件的密钥未嵌入文件（QQ音乐新版 musicex/STag 格式），需先「导入密钥」（从 QQ 音乐 MMKV 数据库导入）才能离线解密。"

    /** Read MediaFileName (UTF-16-LE ASCII) from a musicex tag body, for MMKV key lookup. */
    private fun readMusicExName(raf: RandomAccessFile, fileSize: Long, tagSize: Long): String {
        // trailer = [body (tagSize-16)][16-byte header]; MediaFileName at body offset 0x48
        val nameOff = fileSize - tagSize + 0x48
        if (nameOff < 0 || nameOff + 2 > fileSize) return ""
        val maxBytes = 50 * 2
        val avail = (fileSize - 16 - nameOff).coerceAtMost(maxBytes.toLong()).toInt()
        if (avail <= 0) return ""
        val buf = ByteArray(avail)
        raf.seek(nameOff)
        raf.readFully(buf)
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < buf.size) {
            val c = buf[i].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun parseQmcFooter(raf: RandomAccessFile, fileSize: Long): QmcFooter {
        if (fileSize < 4) return QmcFooter(fileSize, null)
        val tail = ByteArray(4)
        raf.seek(fileSize - 4)
        raf.readFully(tail)

        // "QTag" → [rawMeta][metaLen BE u32]["QTag"], rawMeta = "ekey,songID,extra2"
        if (tail[0] == 'Q'.code.toByte() && tail[1] == 'T'.code.toByte() &&
            tail[2] == 'a'.code.toByte() && tail[3] == 'g'.code.toByte()) {
            if (fileSize < 8) throw DecryptException(MSG_NO_KEY)
            val ml = ByteArray(4)
            raf.seek(fileSize - 8)
            raf.readFully(ml)
            val metaLen = be32(ml, 0)
            val audioLen = fileSize - 8 - metaLen
            if (audioLen < 0 || metaLen < 0) throw DecryptException(MSG_NO_KEY)
            val meta = ByteArray(metaLen)
            raf.seek(audioLen)
            raf.readFully(meta)
            val items = String(meta, Charsets.ISO_8859_1).split(",")
            if (items.isEmpty() || items[0].isEmpty()) throw DecryptException(MSG_NO_KEY)
            return QmcFooter(audioLen, items[0].toByteArray(Charsets.ISO_8859_1))
        }
        // "STag" → key not in file; [rawMeta][metaLen BE u32]["STag"]
        if (tail[0] == 'S'.code.toByte() && tail[1] == 'T'.code.toByte() &&
            tail[2] == 'a'.code.toByte() && tail[3] == 'g'.code.toByte()) {
            if (fileSize < 8) throw DecryptException(MSG_NO_KEY)
            val ml = ByteArray(4)
            raf.seek(fileSize - 8)
            raf.readFully(ml)
            val metaLen = be32(ml, 0)
            val audioLen = fileSize - 8 - metaLen
            if (audioLen < 0) throw DecryptException(MSG_NO_KEY)
            return QmcFooter(audioLen, null, needsExternalKey = true)
        }
        // "cex\0" (musicex) → key in client MMKV database; trailer header at fileSize-16
        if (tail[0] == 'c'.code.toByte() && tail[1] == 'e'.code.toByte() &&
            tail[2] == 'x'.code.toByte() && tail[3] == 0.toByte()) {
            if (fileSize < 16) throw DecryptException(MSG_NO_KEY)
            val hdr = ByteArray(4)
            raf.seek(fileSize - 16)
            raf.readFully(hdr)
            val tagSize = le32(hdr, 0)
            val audioLen = fileSize - tagSize
            if (tagSize < 16 || audioLen < 0) throw DecryptException(MSG_NO_KEY)
            val mediaName = try { readMusicExName(raf, fileSize, tagSize) } catch (_: Exception) { "" }
            val names = if (mediaName.isNotEmpty()) listOf(mediaName) else emptyList()
            return QmcFooter(audioLen, null, needsExternalKey = true, externalNames = names)
        }
        // numeric: last 4 bytes (LE) = embedded raw-key (base64 EKey) length
        val keyLen = le32(tail, 0)
        if (keyLen in 1..0xFFFF && keyLen < fileSize - 4) {
            val audioLen = fileSize - 4 - keyLen
            val ekey = ByteArray(keyLen.toInt())
            raf.seek(audioLen)
            raf.readFully(ekey)
            return QmcFooter(audioLen, trimTrailingNul(ekey))
        }
        // no recognised footer → whole file is audio, legacy static cipher
        return QmcFooter(fileSize, null)
    }

    private fun parseQmcFooterBytes(data: ByteArray): QmcFooter {
        val size = data.size
        if (size < 4) return QmcFooter(size.toLong(), null)
        val o = size - 4
        if (data[o] == 'Q'.code.toByte() && data[o + 1] == 'T'.code.toByte() &&
            data[o + 2] == 'a'.code.toByte() && data[o + 3] == 'g'.code.toByte()) {
            if (size < 8) throw DecryptException(MSG_NO_KEY)
            val metaLen = be32(data, size - 8)
            val audioLen = size - 8 - metaLen
            if (audioLen < 0 || metaLen < 0) throw DecryptException(MSG_NO_KEY)
            val items = String(data, audioLen, metaLen, Charsets.ISO_8859_1).split(",")
            if (items.isEmpty() || items[0].isEmpty()) throw DecryptException(MSG_NO_KEY)
            return QmcFooter(audioLen.toLong(), items[0].toByteArray(Charsets.ISO_8859_1))
        }
        if (data[o] == 'S'.code.toByte() && data[o + 1] == 'T'.code.toByte() &&
            data[o + 2] == 'a'.code.toByte() && data[o + 3] == 'g'.code.toByte()) {
            if (size < 8) throw DecryptException(MSG_NO_KEY)
            val metaLen = be32(data, size - 8)
            val audioLen = size - 8 - metaLen
            if (audioLen < 0) throw DecryptException(MSG_NO_KEY)
            return QmcFooter(audioLen.toLong(), null, needsExternalKey = true)
        }
        if (data[o] == 'c'.code.toByte() && data[o + 1] == 'e'.code.toByte() &&
            data[o + 2] == 'x'.code.toByte() && data[o + 3] == 0.toByte()) {
            if (size < 16) throw DecryptException(MSG_NO_KEY)
            val tagSize = le32(data, size - 16).toInt()
            val audioLen = size - tagSize
            if (tagSize < 16 || audioLen < 0) throw DecryptException(MSG_NO_KEY)
            val nameOff = size - tagSize + 0x48
            val mediaName = if (nameOff in 0 until size) {
                val sb = StringBuilder()
                var i = nameOff
                val limit = (size - 16).coerceAtMost(nameOff + 100)
                while (i + 1 < limit) {
                    val c = data[i].toInt() and 0xFF
                    if (c == 0) break
                    sb.append(c.toChar()); i += 2
                }
                sb.toString()
            } else ""
            val names = if (mediaName.isNotEmpty()) listOf(mediaName) else emptyList()
            return QmcFooter(audioLen.toLong(), null, needsExternalKey = true, externalNames = names)
        }
        val keyLen = le32(data, o)
        if (keyLen in 1..0xFFFF && keyLen < size - 4) {
            val audioLen = size - 4 - keyLen.toInt()
            val ekey = data.copyOfRange(audioLen, size - 4)
            return QmcFooter(audioLen.toLong(), trimTrailingNul(ekey))
        }
        return QmcFooter(size.toLong(), null)
    }

    private fun qmcCipherFor(realKey: ByteArray): QmcCipher = when {
        realKey.size > QMC_KEY_THRESHOLD -> QmcRc4Cipher(realKey)
        realKey.isNotEmpty() -> QmcMapCipher(realKey)
        else -> QmcStaticCipher
    }

    /** Returns the base64 EKey bytes for this file, or null for the legacy static cipher. */
    private fun resolveQmcEkey(
        footer: QmcFooter,
        filename: String,
        ekeyResolver: EkeyResolver?
    ): ByteArray? {
        if (footer.ekey != null) return footer.ekey
        if (footer.needsExternalKey) {
            val names = (footer.externalNames + filename).filter { it.isNotBlank() }.distinct()
            val resolved = ekeyResolver?.resolve(names) ?: throw DecryptException(MSG_NO_KEY)
            return resolved.toByteArray(Charsets.ISO_8859_1)
        }
        return null  // static
    }

    private fun decryptQMC(
        data: ByteArray,
        filename: String,
        ekeyResolver: EkeyResolver?
    ): DecryptResult {
        val footer = parseQmcFooterBytes(data)
        val ekeyBytes = resolveQmcEkey(footer, filename, ekeyResolver)
        val realKey = ekeyBytes?.let { deriveKey(it) } ?: ByteArray(0)
        val cipher = qmcCipherFor(realKey)
        val audio = data.copyOfRange(0, footer.audioLen.toInt())
        cipher.decrypt(audio, 0, audio.size, 0L)
        return DecryptResult(audio, detectAudioFormat(audio))
    }

    // ── QMC cipher interface + implementations ─────────────────────

    private interface QmcCipher {
        /** XOR-decrypt [len] bytes of [buf] starting at [bufOff], whose first byte sits at
         * absolute audio offset [fileOffset]. Keystream is purely offset-derived, so calls
         * may arrive in any contiguous order (streaming-safe). */
        fun decrypt(buf: ByteArray, bufOff: Int, len: Int, fileOffset: Long)
    }

    // Static cipher (legacy QMCv1, key length 0). Flat 256-byte box; byte-identical to
    // the older 8×7 seed-matrix walk (verified).
    private object QmcStaticCipher : QmcCipher {
        override fun decrypt(buf: ByteArray, bufOff: Int, len: Int, fileOffset: Long) {
            for (i in 0 until len) {
                var off = fileOffset + i
                if (off > 0x7FFF) off %= 0x7FFF
                val o = off.toInt()
                val idx = (o * o + 27) and 0xFF
                buf[bufOff + i] = (buf[bufOff + i].toInt() xor (STATIC_BOX[idx].toInt() and 0xFF)).toByte()
            }
        }
    }

    // Map cipher (key length 1..300).
    private class QmcMapCipher(private val key: ByteArray) : QmcCipher {
        private val size = key.size
        private fun mask(fileOffset: Long): Int {
            var off = fileOffset
            if (off > 0x7FFF) off %= 0x7FFF
            val o = off.toInt()
            val idx = ((o.toLong() * o + 71214) % size).toInt()
            val v = key[idx].toInt() and 0xFF
            val r = ((idx and 0x07) + 4) % 8
            return ((v shl r) and 0xFF) or (v ushr r)
        }
        override fun decrypt(buf: ByteArray, bufOff: Int, len: Int, fileOffset: Long) {
            for (i in 0 until len) {
                buf[bufOff + i] = (buf[bufOff + i].toInt() xor mask(fileOffset + i)).toByte()
            }
        }
    }

    // RC4 cipher (key length > 300). Segment-based: first 128 bytes special, then
    // 5120-byte segments each keyed from a fresh box + skip.
    private class QmcRc4Cipher(private val key: ByteArray) : QmcCipher {
        private val n = key.size
        private val box = ByteArray(n)
        private val hash: Long

        init {
            for (i in 0 until n) box[i] = i.toByte()
            var j = 0
            for (i in 0 until n) {
                j = (j + (box[i].toInt() and 0xFF) + (key[i % n].toInt() and 0xFF)) % n
                val t = box[i]; box[i] = box[j]; box[j] = t
            }
            var h = 1L
            for (i in 0 until n) {
                val v = key[i].toInt() and 0xFF
                if (v == 0) continue
                val next = (h * v) and 0xFFFFFFFFL
                if (next == 0L || next <= h) break
                h = next
            }
            hash = h
        }

        private fun segmentSkip(id: Int): Int {
            val seed = key[id % n].toInt() and 0xFF
            if (seed == 0) return 0
            val idx = (hash.toDouble() / ((id + 1).toLong() * seed).toDouble() * 100.0).toLong()
            return (idx % n).toInt()
        }

        private fun encFirstSegment(buf: ByteArray, bufOff: Int, len: Int, offset: Int) {
            for (i in 0 until len) {
                buf[bufOff + i] = (buf[bufOff + i].toInt() xor (key[segmentSkip(offset + i)].toInt() and 0xFF)).toByte()
            }
        }

        private fun encASegment(buf: ByteArray, bufOff: Int, len: Int, offset: Int) {
            val b = box.copyOf()
            var j = 0
            var k = 0
            val skipLen = (offset % SEG) + segmentSkip(offset / SEG)
            var i = -skipLen
            while (i < len) {
                j = (j + 1) % n
                k = ((b[j].toInt() and 0xFF) + k) % n
                val t = b[j]; b[j] = b[k]; b[k] = t
                if (i >= 0) {
                    val ks = b[((b[j].toInt() and 0xFF) + (b[k].toInt() and 0xFF)) % n].toInt() and 0xFF
                    buf[bufOff + i] = (buf[bufOff + i].toInt() xor ks).toByte()
                }
                i++
            }
        }

        override fun decrypt(buf: ByteArray, bufOff: Int, len: Int, fileOffset: Long) {
            // Music files are well under 2GB, so Int offset is safe (matches Go reference).
            var offset = fileOffset.toInt()
            var toProcess = len
            var processed = 0

            if (offset < FIRST) {
                val bs = minOf(toProcess, FIRST - offset)
                encFirstSegment(buf, bufOff + processed, bs, offset)
                offset += bs; toProcess -= bs; processed += bs
                if (toProcess == 0) return
            }
            if (offset % SEG != 0) {
                val bs = minOf(toProcess, SEG - offset % SEG)
                encASegment(buf, bufOff + processed, bs, offset)
                offset += bs; toProcess -= bs; processed += bs
                if (toProcess == 0) return
            }
            while (toProcess > SEG) {
                encASegment(buf, bufOff + processed, SEG, offset)
                offset += SEG; toProcess -= SEG; processed += SEG
            }
            if (toProcess > 0) encASegment(buf, bufOff + processed, toProcess, offset)
        }

        companion object {
            private const val SEG = 5120
            private const val FIRST = 128
        }
    }

    // ── EKey derivation (base64 → optional EncV2 → V1 TEA-CBC) ──────

    private val SIMPLE_KEY = byteArrayOf(0x69, 0x56, 0x46, 0x38, 0x2B, 0x20, 0x15, 0x0B)
    private val DERIVE_V2_KEY1 = byteArrayOf(
        0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
        0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28
    )
    private val DERIVE_V2_KEY2 = byteArrayOf(
        0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
        0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54
    )
    private val V2_PREFIX = "QQMusic EncV2,Key:".toByteArray(Charsets.ISO_8859_1)

    private fun base64Decode(b: ByteArray): ByteArray =
        android.util.Base64.decode(b, android.util.Base64.DEFAULT)

    private fun deriveKey(rawKey: ByteArray): ByteArray {
        var dec = base64Decode(rawKey)
        if (dec.size >= V2_PREFIX.size && V2_PREFIX.indices.all { dec[it] == V2_PREFIX[it] }) {
            dec = deriveKeyV2(dec.copyOfRange(V2_PREFIX.size, dec.size))
        }
        return deriveKeyV1(dec)
    }

    private fun deriveKeyV1(dec: ByteArray): ByteArray {
        if (dec.size < 16) throw DecryptException("无效的 QQ 音乐密钥")
        val teaKey = ByteArray(16)
        for (i in 0 until 8) {
            teaKey[i shl 1] = SIMPLE_KEY[i]
            teaKey[(i shl 1) + 1] = dec[i]
        }
        val rs = decryptTencentTea(dec.copyOfRange(8, dec.size), teaKey)
        val out = ByteArray(8 + rs.size)
        System.arraycopy(dec, 0, out, 0, 8)
        System.arraycopy(rs, 0, out, 8, rs.size)
        return out
    }

    private fun deriveKeyV2(raw: ByteArray): ByteArray {
        var buf = decryptTencentTea(raw, DERIVE_V2_KEY1)
        buf = decryptTencentTea(buf, DERIVE_V2_KEY2)
        return base64Decode(buf)
    }

    // ── Tencent TEA in CBC mode (TarsCpp oi_symmetry_decrypt2) ─────

    private fun beReadInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun beWriteInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    // TEA block decrypt: big-endian words, DELTA=0x9E3779B9, 16 cycles (golang.org/x/crypto rounds=32).
    private fun teaDecryptBlock(block: ByteArray, k: IntArray) {
        var v0 = beReadInt(block, 0)
        var v1 = beReadInt(block, 4)
        val delta = 0x9E3779B9.toInt()
        var sum = 0xE3779B90.toInt()   // = (delta * 16) mod 2^32; decrypt starts at the top sum
        repeat(16) {
            v1 -= ((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3])
            v0 -= ((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1])
            sum -= delta
        }
        beWriteInt(block, 0, v0)
        beWriteInt(block, 4, v1)
    }

    private fun decryptTencentTea(inBuf: ByteArray, key: ByteArray): ByteArray {
        val saltLen = 2
        val zeroLen = 7
        if (inBuf.size % 8 != 0) throw DecryptException("TEA 输入长度非法")
        if (inBuf.size < 16) throw DecryptException("TEA 输入过短")
        val tk = intArrayOf(beReadInt(key, 0), beReadInt(key, 4), beReadInt(key, 8), beReadInt(key, 12))

        val destBuf = inBuf.copyOfRange(0, 8)
        teaDecryptBlock(destBuf, tk)
        val padLen = destBuf[0].toInt() and 0x07
        val outLen = inBuf.size - 1 - padLen - saltLen - zeroLen
        if (outLen < 0) throw DecryptException("TEA 解密失败")
        val out = ByteArray(outLen)

        val ivPrev = ByteArray(8)
        val ivCur = inBuf.copyOfRange(0, 8)
        var inPos = 8
        var destIdx = 1 + padLen

        fun cryptBlock() {
            System.arraycopy(ivCur, 0, ivPrev, 0, 8)
            System.arraycopy(inBuf, inPos, ivCur, 0, 8)
            for (i in 0 until 8) destBuf[i] = (destBuf[i].toInt() xor inBuf[inPos + i].toInt()).toByte()
            teaDecryptBlock(destBuf, tk)
            inPos += 8
            destIdx = 0
        }

        var i = 1
        while (i <= saltLen) {
            if (destIdx < 8) { destIdx++; i++ } else cryptBlock()
        }
        var outPos = 0
        while (outPos < outLen) {
            if (destIdx < 8) {
                out[outPos] = (destBuf[destIdx].toInt() xor ivPrev[destIdx].toInt()).toByte()
                destIdx++; outPos++
            } else cryptBlock()
        }
        // zero-check (guarded): a correct key leaves 7 trailing zero bytes matching ivPrev.
        for (z in 1..zeroLen) {
            if (destIdx < 8 && destBuf[destIdx] != ivPrev[destIdx])
                throw DecryptException("QQ 音乐密钥校验失败")
        }
        return out
    }

    // QMCv1 static cipher box (unlock-music cipher_static.go).
    private val STATIC_BOX = byteArrayOf(
        0x77, 0x48, 0x32, 0x73, 0xDE.toByte(), 0xF2.toByte(), 0xC0.toByte(), 0xC8.toByte(),
        0x95.toByte(), 0xEC.toByte(), 0x30, 0xB2.toByte(), 0x51, 0xC3.toByte(), 0xE1.toByte(), 0xA0.toByte(),
        0x9E.toByte(), 0xE6.toByte(), 0x9D.toByte(), 0xCF.toByte(), 0xFA.toByte(), 0x7F, 0x14, 0xD1.toByte(),
        0xCE.toByte(), 0xB8.toByte(), 0xDC.toByte(), 0xC3.toByte(), 0x4A, 0x67, 0x93.toByte(), 0xD6.toByte(),
        0x28, 0xC2.toByte(), 0x91.toByte(), 0x70, 0xCA.toByte(), 0x8D.toByte(), 0xA2.toByte(), 0xA4.toByte(),
        0xF0.toByte(), 0x08, 0x61, 0x90.toByte(), 0x7E, 0x6F, 0xA2.toByte(), 0xE0.toByte(),
        0xEB.toByte(), 0xAE.toByte(), 0x3E, 0xB6.toByte(), 0x67, 0xC7.toByte(), 0x92.toByte(), 0xF4.toByte(),
        0x91.toByte(), 0xB5.toByte(), 0xF6.toByte(), 0x6C, 0x5E, 0x84.toByte(), 0x40, 0xF7.toByte(),
        0xF3.toByte(), 0x1B, 0x02, 0x7F, 0xD5.toByte(), 0xAB.toByte(), 0x41, 0x89.toByte(),
        0x28, 0xF4.toByte(), 0x25, 0xCC.toByte(), 0x52, 0x11, 0xAD.toByte(), 0x43,
        0x68, 0xA6.toByte(), 0x41, 0x8B.toByte(), 0x84.toByte(), 0xB5.toByte(), 0xFF.toByte(), 0x2C,
        0x92.toByte(), 0x4A, 0x26, 0xD8.toByte(), 0x47, 0x6A, 0x7C, 0x95.toByte(),
        0x61, 0xCC.toByte(), 0xE6.toByte(), 0xCB.toByte(), 0xBB.toByte(), 0x3F, 0x47, 0x58,
        0x89.toByte(), 0x75, 0xC3.toByte(), 0x75, 0xA1.toByte(), 0xD9.toByte(), 0xAF.toByte(), 0xCC.toByte(),
        0x08, 0x73, 0x17, 0xDC.toByte(), 0xAA.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0x16,
        0x41, 0xD8.toByte(), 0xA2.toByte(), 0x06, 0xC6.toByte(), 0x8B.toByte(), 0xFC.toByte(), 0x66,
        0x34, 0x9F.toByte(), 0xCF.toByte(), 0x18, 0x23, 0xA0.toByte(), 0x0A, 0x74,
        0xE7.toByte(), 0x2B, 0x27, 0x70, 0x92.toByte(), 0xE9.toByte(), 0xAF.toByte(), 0x37,
        0xE6.toByte(), 0x8C.toByte(), 0xA7.toByte(), 0xBC.toByte(), 0x62, 0x65, 0x9C.toByte(), 0xC2.toByte(),
        0x08, 0xC9.toByte(), 0x88.toByte(), 0xB3.toByte(), 0xF3.toByte(), 0x43, 0xAC.toByte(), 0x74,
        0x2C, 0x0F, 0xD4.toByte(), 0xAF.toByte(), 0xA1.toByte(), 0xC3.toByte(), 0x01, 0x64,
        0x95.toByte(), 0x4E, 0x48, 0x9F.toByte(), 0xF4.toByte(), 0x35, 0x78, 0x95.toByte(),
        0x7A, 0x39, 0xD6.toByte(), 0x6A, 0xA0.toByte(), 0x6D, 0x40, 0xE8.toByte(),
        0x4F, 0xA8.toByte(), 0xEF.toByte(), 0x11, 0x1D, 0xF3.toByte(), 0x1B, 0x3F,
        0x3F, 0x07, 0xDD.toByte(), 0x6F, 0x5B, 0x19, 0x30, 0x19,
        0xFB.toByte(), 0xEF.toByte(), 0x0E, 0x37, 0xF0.toByte(), 0x0E, 0xCD.toByte(), 0x16,
        0x49, 0xFE.toByte(), 0x53, 0x47, 0x13, 0x1A, 0xBD.toByte(), 0xA4.toByte(),
        0xF1.toByte(), 0x40, 0x19, 0x60, 0x0E, 0xED.toByte(), 0x68, 0x09,
        0x06, 0x5F, 0x4D, 0xCF.toByte(), 0x3D, 0x1A, 0xFE.toByte(), 0x20,
        0x77, 0xE4.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xF9.toByte(), 0xA4.toByte(), 0x2B, 0x76,
        0x1C, 0x71, 0xDB.toByte(), 0x00, 0xBC.toByte(), 0xFD.toByte(), 0x0C, 0x6C,
        0xA5.toByte(), 0x47, 0xF7.toByte(), 0xF6.toByte(), 0x00, 0x79, 0x4A, 0x11
    )

    // ──────────────────────────────────────────────────────────────
    // QQ Music iOS .tm0/.tm2/.tm3/.tm6 (tm.go)
    //   .tm2/.tm6 (M4A): first 4 bytes are "QQMU"; replace first 8 bytes with ftyp header.
    //   .tm0/.tm3 (MP3): not encrypted, passthrough.
    // ──────────────────────────────────────────────────────────────

    private val TM_MAGIC = byteArrayOf(0x51, 0x51, 0x4D, 0x55) // "QQMU"
    private val TM_REPLACE_HEADER = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70)

    private fun decryptTM(data: ByteArray): DecryptResult {
        val out = data.copyOf()
        if (out.size >= 4 && TM_MAGIC.indices.all { out[it] == TM_MAGIC[it] }) {
            System.arraycopy(TM_REPLACE_HEADER, 0, out, 0, 8)
        }
        return DecryptResult(out, detectAudioFormat(out))
    }

    // ──────────────────────────────────────────────────────────────
    // Kugou KGM / VPR (crypto v3) — ported from unlock-music algo/kgm
    //   header: magic(16) AudioOffset(u32@0x10) CryptoVersion(u32@0x14)
    //           CryptoSlot(u32@0x18) testData(16@0x1c) CryptoKey(16@0x2c)
    //   v3: slotBox = kugouMd5(slotKey[slot]); fileBox = kugouMd5(CryptoKey)+0x6b
    //   per byte: ^fileBox[p%17]; ^=(b<<4); ^slotBox[p%16]; ^xorCollapse(p)
    //   v5 (.kgg) needs the Kugou KGMusicV3.db — not offline-decryptable.
    // ──────────────────────────────────────────────────────────────

    private val KGM_MAGIC = byteArrayOf(
        0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(), 0x86.toByte(), 0x02, 0x7F, 0x4B,
        0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(), 0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14
    )
    private val VPR_MAGIC = byteArrayOf(
        0x05, 0x28, 0xBC.toByte(), 0x96.toByte(), 0xE9.toByte(), 0xE4.toByte(), 0x5A, 0x43,
        0x91.toByte(), 0xAA.toByte(), 0xBD.toByte(), 0xD0.toByte(), 0x7A, 0xF5.toByte(), 0x36, 0x31
    )
    private val KGM_V3_SLOT_KEYS = mapOf(1 to byteArrayOf(0x6C, 0x2C, 0x2F, 0x27))
    private const val MSG_KGG_DB =
        "酷狗 .kgg / 新版 KGM(v5) 的密钥保存在客户端数据库（KGMusicV3.db），无法离线解密。"

    private fun kugouMd5(b: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("MD5").digest(b)
        val ret = ByteArray(16)
        var i = 0
        while (i < 16) { ret[i] = d[14 - i]; ret[i + 1] = d[14 - i + 1]; i += 2 }
        return ret
    }

    private class KgmV3Cipher(slotKey: ByteArray, cryptoKey: ByteArray) {
        private val slotBox = kugouMd5(slotKey)               // 16
        private val fileBox = kugouMd5(cryptoKey) + 0x6b.toByte()  // 17
        fun decrypt(buf: ByteArray, off: Int, len: Int, fileOffset: Long) {
            for (i in 0 until len) {
                val p = fileOffset + i
                var b = buf[off + i].toInt() and 0xFF
                b = b xor (fileBox[(p % 17).toInt()].toInt() and 0xFF)
                b = b xor ((b shl 4) and 0xFF)
                b = b xor (slotBox[(p % 16).toInt()].toInt() and 0xFF)
                val x = p.toInt()
                b = b xor ((x xor (x ushr 8) xor (x ushr 16) xor (x ushr 24)) and 0xFF)
                buf[off + i] = b.toByte()
            }
        }
    }

    /** Parse a KGM/VPR header → (cipher, audioOffset). Throws for v5 (.kgg, needs DB). */
    private fun kgmCipher(header: ByteArray): Pair<KgmV3Cipher, Long> {
        if (header.size < 0x3C) throw DecryptException("KGM 头部过短")
        val audioOffset = le32(header, 0x10)
        val cryptoVersion = le32(header, 0x14).toInt()
        val cryptoSlot = le32(header, 0x18).toInt()
        if (cryptoVersion != 3) throw DecryptException(MSG_KGG_DB)
        val slotKey = KGM_V3_SLOT_KEYS[cryptoSlot]
            ?: throw DecryptException("不支持的 KGM 密钥槽 $cryptoSlot")
        val cryptoKey = header.copyOfRange(0x2C, 0x2C + 16)
        return KgmV3Cipher(slotKey, cryptoKey) to audioOffset
    }

    private fun decryptKGM(data: ByteArray): DecryptResult {
        if (!matchMagic(data, KGM_MAGIC) && !matchMagic(data, VPR_MAGIC))
            throw DecryptException("不是有效的 KGM/VPR 文件")
        val (cipher, audioOffset) = kgmCipher(data)
        val audio = data.copyOfRange(audioOffset.toInt(), data.size)
        cipher.decrypt(audio, 0, audio.size, 0L)
        return DecryptResult(audio, detectAudioFormat(audio))
    }

    // ──────────────────────────────────────────────────────────────
    // Kuwo KWM — ported from unlock-music algo/kwm
    //   header: 0x400 bytes; magic "yeelion-kuwo-tme" / "yeelion-kuwo\0\0\0\0"
    //   key = header[0x18:0x20] (8 bytes); mask = predef ^ decimal(LE u64 key)
    //   per byte: buf[i] ^= mask[(off+i) & 0x1F]; audio starts at 0x400
    // ──────────────────────────────────────────────────────────────

    private const val KWM_HEADER = 0x400
    private val KWM_MAGIC1 = "yeelion-kuwo-tme".toByteArray(Charsets.ISO_8859_1)
    private val KWM_MAGIC2 = "yeelion-kuwo\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val KWM_PREDEF = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk".toByteArray(Charsets.ISO_8859_1)

    private class KwmCipher(key: ByteArray) {
        private val mask = generate(key)
        private fun generate(key: ByteArray): ByteArray {
            var keyInt = 0L
            for (i in 0 until 8) keyInt = keyInt or ((key[i].toLong() and 0xFF) shl (8 * i))
            val keyStr = java.lang.Long.toUnsignedString(keyInt)
            val trimmed = padOrTruncate(keyStr, 32)
            val m = ByteArray(32)
            for (i in 0 until 32) m[i] = ((KWM_PREDEF[i].toInt() and 0xFF) xor trimmed[i].code).toByte()
            return m
        }
        private fun padOrTruncate(raw: String, length: Int): String {
            if (raw.isEmpty()) return " ".repeat(length)
            if (raw.length >= length) return raw.substring(0, length)
            val sb = StringBuilder(length)
            for (i in 0 until length) sb.append(raw[i % raw.length])
            return sb.toString()
        }
        fun decrypt(buf: ByteArray, off: Int, len: Int, fileOffset: Long) {
            for (i in 0 until len) {
                val mi = ((fileOffset + i) and 0x1FL).toInt()
                buf[off + i] = (buf[off + i].toInt() xor (mask[mi].toInt() and 0xFF)).toByte()
            }
        }
    }

    private fun kwmCipher(header: ByteArray): KwmCipher {
        if (header.size < 0x20) throw DecryptException("KWM 头部过短")
        if (!matchMagic(header, KWM_MAGIC1) && !matchMagic(header, KWM_MAGIC2))
            throw DecryptException("不是有效的 KWM 文件")
        return KwmCipher(header.copyOfRange(0x18, 0x20))
    }

    private fun decryptKWM(data: ByteArray): DecryptResult {
        if (data.size < KWM_HEADER) throw DecryptException("KWM 文件过短")
        val cipher = kwmCipher(data)
        val audio = data.copyOfRange(KWM_HEADER, data.size)
        cipher.decrypt(audio, 0, audio.size, 0L)
        return DecryptResult(audio, detectAudioFormat(audio))
    }

    // ──────────────────────────────────────────────────────────────
    // Streaming decryption — file-based, OOM-proof
    // ──────────────────────────────────────────────────────────────

    /**
     * Decrypt a file using streaming — never loads the whole file into memory.
     * Reads from [inputPath] (encrypted), writes decrypted audio to [outputPath].
     * Returns the detected audio format.
     * @throws DecryptException for recognised-but-undecryptable files (e.g. musicex/STag).
     */
    fun decryptFile(
        inputPath: String,
        outputPath: String,
        filename: String,
        ekeyResolver: EkeyResolver? = null
    ): AudioFormat {
        val raf = RandomAccessFile(inputPath, "r")
        try {
            // Read first 8KB to detect format, then rewind to 0 for streaming
            val headerBuf = ByteArray(8192)
            val headerRead = raf.read(headerBuf, 0, headerBuf.size)
            if (headerRead <= 0) return AudioFormat.UNKNOWN
            val header = if (headerRead < headerBuf.size) headerBuf.copyOf(headerRead) else headerBuf
            raf.seek(0L)  // Rewind — each format handler seeks to its exact audio offset

            val fmt = detectFormat(header).let {
                if (it == EncryptedFormat.UNKNOWN) nameToFormat(filename) else it
            }

            return when (fmt) {
                EncryptedFormat.NCM -> decryptNCMStream(raf, outputPath, header)
                EncryptedFormat.QMC -> decryptQMCStream(raf, outputPath, filename, ekeyResolver)
                EncryptedFormat.TM -> decryptTMStream(raf, outputPath, header)
                EncryptedFormat.KGM, EncryptedFormat.VPR -> decryptKGMStream(raf, outputPath, header)
                EncryptedFormat.KGG -> throw DecryptException(MSG_KGG_DB)
                EncryptedFormat.KWM, EncryptedFormat.KWMA -> decryptKWMStream(raf, outputPath, header)
                EncryptedFormat.UNKNOWN -> AudioFormat.UNKNOWN
            }
        } finally {
            raf.close()
        }
    }

    // ── NCM streaming ──────────────────────────────────────────────

    private fun decryptNCMStream(
        raf: RandomAccessFile,
        outputPath: String,
        header: ByteArray
    ): AudioFormat {
        // NCM header is too large to fit in 8KB, so parse it from the file directly
        // raf position is at 0 after rewind in decryptFile
        raf.seek(10L) // skip magic(8) + padding(2)

        // Key length (4B LE)
        val keyLenBuf = ByteArray(4)
        raf.readFully(keyLenBuf)
        val keyLen = (keyLenBuf[0].toInt() and 0xFF) or
                ((keyLenBuf[1].toInt() and 0xFF) shl 8) or
                ((keyLenBuf[2].toInt() and 0xFF) shl 16) or
                ((keyLenBuf[3].toInt() and 0xFF) shl 24)

        // Read key data, XOR with 0x64, AES-128-ECB decrypt
        val rawKey = ByteArray(keyLen)
        raf.readFully(rawKey)
        for (i in rawKey.indices) rawKey[i] = (rawKey[i].toInt() xor 0x64).toByte()
        val decryptedKey = aes128EcbDecrypt(rawKey)

        // buildKeyBox with bytes 17+
        val keyBox = buildKeyBox(decryptedKey.copyOfRange(17, decryptedKey.size))

        // Metadata length (4B LE)
        val metaLenBuf = ByteArray(4)
        raf.readFully(metaLenBuf)
        val metaLen = (metaLenBuf[0].toInt() and 0xFF) or
                ((metaLenBuf[1].toInt() and 0xFF) shl 8) or
                ((metaLenBuf[2].toInt() and 0xFF) shl 16) or
                ((metaLenBuf[3].toInt() and 0xFF) shl 24)
        if (metaLen > 0) raf.skipBytes(metaLen)

        // CRC32 (4B) + image version (1B)
        raf.skipBytes(5)

        // Cover frame length (4B LE)
        val coverLenBuf = ByteArray(4)
        raf.readFully(coverLenBuf)
        val coverFrameLen = (coverLenBuf[0].toInt() and 0xFF) or
                ((coverLenBuf[1].toInt() and 0xFF) shl 8) or
                ((coverLenBuf[2].toInt() and 0xFF) shl 16) or
                ((coverLenBuf[3].toInt() and 0xFF) shl 24)

        // Image length (4B LE)
        val imgLenBuf = ByteArray(4)
        raf.readFully(imgLenBuf)
        val imgLen = (imgLenBuf[0].toInt() and 0xFF) or
                ((imgLenBuf[1].toInt() and 0xFF) shl 8) or
                ((imgLenBuf[2].toInt() and 0xFF) shl 16) or
                ((imgLenBuf[3].toInt() and 0xFF) shl 24)
        if (imgLen > 0) raf.skipBytes(imgLen)
        raf.skipBytes(coverFrameLen - imgLen)

        // Now raf is at audio data start — stream decrypt with keyBox
        return streamKeyBoxAndDetect(raf, outputPath, keyBox)
    }

    /**
     * Stream-keyBox-decrypt from raf to outputPath, detect audio format from first chunk.
     */
    private fun streamKeyBoxAndDetect(
        raf: RandomAccessFile,
        outputPath: String,
        keyBox: IntArray
    ): AudioFormat {
        var audioFmt = AudioFormat.UNKNOWN
        var isFirstChunk = true

        FileOutputStream(outputPath).use { output ->
            val buf = ByteArray(BUF_SIZE)
            var read: Int
            while (raf.read(buf).also { read = it } != -1) {
                val chunk = if (read < buf.size) buf.copyOf(read) else buf

                // Custom keyBox XOR (NOT RC4)
                keyBoxXorChunk(chunk, keyBox)

                // Detect audio format from first chunk
                if (isFirstChunk && chunk.size >= 2) {
                    audioFmt = detectAudioFormat(chunk)
                    isFirstChunk = false
                }

                output.write(chunk, 0, chunk.size)
            }
        }

        return audioFmt
    }

    // ── QMC streaming ──────────────────────────────────────────────

    private fun decryptQMCStream(
        raf: RandomAccessFile,
        outputPath: String,
        filename: String,
        ekeyResolver: EkeyResolver?
    ): AudioFormat {
        val fileSize = raf.length()
        val footer = parseQmcFooter(raf, fileSize)          // may throw DecryptException
        val ekeyBytes = resolveQmcEkey(footer, filename, ekeyResolver)
        val realKey = ekeyBytes?.let { deriveKey(it) } ?: ByteArray(0)
        val cipher = qmcCipherFor(realKey)

        var audioFmt = AudioFormat.UNKNOWN
        var firstChunk = true
        var offset = 0L
        raf.seek(0L)

        FileOutputStream(outputPath).use { output ->
            val buf = ByteArray(BUF_SIZE)
            while (offset < footer.audioLen) {
                val toRead = minOf(BUF_SIZE.toLong(), footer.audioLen - offset).toInt()
                val read = raf.read(buf, 0, toRead)
                if (read <= 0) break
                cipher.decrypt(buf, 0, read, offset)
                if (firstChunk) {
                    audioFmt = detectAudioFormat(buf)
                    firstChunk = false
                }
                output.write(buf, 0, read)
                offset += read
            }
        }
        return audioFmt
    }

    // ── TM streaming ───────────────────────────────────────────────

    private fun decryptTMStream(
        raf: RandomAccessFile,
        outputPath: String,
        header: ByteArray
    ): AudioFormat {
        val isFaked = header.size >= 4 && TM_MAGIC.indices.all { header[it] == TM_MAGIC[it] }
        raf.seek(0L)
        FileOutputStream(outputPath).use { output ->
            val buf = ByteArray(BUF_SIZE)
            var read = raf.read(buf)
            if (read <= 0) return AudioFormat.UNKNOWN
            if (isFaked) {
                // Replace the first 8 bytes ("QQMU"+4) with the real M4A ftyp header
                val n = minOf(8, read)
                System.arraycopy(TM_REPLACE_HEADER, 0, buf, 0, n)
            }
            output.write(buf, 0, read)
            while (raf.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
            }
        }
        return detectFormatFromFile(outputPath)
    }

    // ── Generic offset-based streaming helper ──────────────────────

    private inline fun streamCipher(
        raf: RandomAccessFile,
        outputPath: String,
        transform: (ByteArray, Int, Long) -> Unit
    ): AudioFormat {
        var fmt = AudioFormat.UNKNOWN
        var first = true
        var offset = 0L
        FileOutputStream(outputPath).use { output ->
            val buf = ByteArray(BUF_SIZE)
            var read: Int
            while (raf.read(buf).also { read = it } != -1) {
                transform(buf, read, offset)
                if (first) { fmt = detectAudioFormat(buf); first = false }
                output.write(buf, 0, read)
                offset += read
            }
        }
        return fmt
    }

    // ── KGM / VPR streaming (crypto v3) ─────────────────────────────

    private fun decryptKGMStream(
        raf: RandomAccessFile,
        outputPath: String,
        header: ByteArray
    ): AudioFormat {
        if (!matchMagic(header, KGM_MAGIC) && !matchMagic(header, VPR_MAGIC))
            throw DecryptException("不是有效的 KGM/VPR 文件")
        val (cipher, audioOffset) = kgmCipher(header)
        raf.seek(audioOffset)
        return streamCipher(raf, outputPath) { buf, n, off -> cipher.decrypt(buf, 0, n, off) }
    }

    // ── KWM streaming ──────────────────────────────────────────────

    private fun decryptKWMStream(
        raf: RandomAccessFile,
        outputPath: String,
        header: ByteArray
    ): AudioFormat {
        val cipher = kwmCipher(header)
        raf.seek(KWM_HEADER.toLong())
        return streamCipher(raf, outputPath) { buf, n, off -> cipher.decrypt(buf, 0, n, off) }
    }

    // ── Helper: detect format from output file ──────────────────────

    private fun detectFormatFromFile(path: String): AudioFormat {
        val raf = try {
            RandomAccessFile(path, "r")
        } catch (e: Exception) { return AudioFormat.UNKNOWN }
        try {
            val buf = ByteArray(8192)
            val read = raf.read(buf, 0, buf.size)
            if (read <= 0) return AudioFormat.UNKNOWN
            val header = if (read < buf.size) buf.copyOf(read) else buf
            return detectAudioFormat(header)
        } finally {
            raf.close()
        }
    }
}
