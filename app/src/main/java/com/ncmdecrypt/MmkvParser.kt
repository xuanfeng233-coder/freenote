package com.ncmdecrypt

/**
 * Minimal read-only parser for Tencent MMKV data files.
 *
 * Targets QQ Music's `MMKVStreamEncryptId` vault, which maps audio file names/paths
 * to base64 "EKey" strings and is stored **plaintext** (opened with an empty crypt key).
 *
 * Format (verified against Tencent/MMKV source + unlock-music go-mmkv fixtures):
 *   [0..4)        actualSize : uint32 LE  (length of the valid KV region)
 *   [4..8)        ItemSizeHolder (skip — not a key)
 *   [8..4+size)   entries: repeated [varint keyLen][key UTF-8][varint valLen][val]
 * The stored value is itself `[varint rawLen][raw]` — i.e. TWO varints sit between
 * the key and the raw ekey bytes. Append-only: a repeated key's last entry wins;
 * a zero-length value means the key was deleted.
 *
 * Encrypted vaults (AES-128-CFB) are NOT supported — QQ Music does not encrypt this vault.
 */
object MmkvParser {

    /**
     * Parse a whole MMKV data file into a name → value(raw, still inner-prefixed) map,
     * then resolve each value to its raw bytes via [getBytes].
     * Returns name → ekey(ASCII string). Throws on malformed input.
     */
    fun parse(data: ByteArray): Map<String, String> {
        if (data.size < 8) throw IllegalArgumentException("file too small to be MMKV")
        val actualSize = (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 24)
        val end = 4 + actualSize
        if (actualSize <= 0 || end > data.size) throw IllegalArgumentException("bad MMKV actualSize")

        var pos = 4 + 4   // skip header(4) + ItemSizeHolder(4)
        val raw = LinkedHashMap<String, ByteArray>()

        fun readVarint(): Int {
            var result = 0; var shift = 0
            while (true) {
                if (pos >= end) throw IllegalArgumentException("truncated varint")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift >= 35) throw IllegalArgumentException("malformed varint")
            }
            return result
        }
        fun readLenDelim(): ByteArray {
            val len = readVarint()
            if (len < 0 || pos + len > end) throw IllegalArgumentException("truncated value")
            val out = data.copyOfRange(pos, pos + len); pos += len; return out
        }

        while (pos < end) {
            val key = String(readLenDelim(), Charsets.UTF_8)
            val value = readLenDelim()      // still wrapped: [varint rawLen][raw]
            if (value.isEmpty()) raw.remove(key) else raw[key] = value
        }

        val out = LinkedHashMap<String, String>(raw.size)
        for ((k, v) in raw) {
            val ekey = getBytes(v) ?: continue
            out[k] = String(ekey, Charsets.ISO_8859_1)
        }
        return out
    }

    /** Strip the inner varint length prefix to recover the raw value bytes (MMKV getBytes). */
    private fun getBytes(wrapped: ByteArray): ByteArray? {
        var p = 0; var result = 0; var shift = 0
        while (true) {
            if (p >= wrapped.size) return null
            val b = wrapped[p++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 35) return null
        }
        if (result < 0 || p + result > wrapped.size) return null
        return wrapped.copyOfRange(p, p + result)
    }
}
