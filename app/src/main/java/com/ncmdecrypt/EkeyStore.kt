package com.ncmdecrypt

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Persistent store of QQ Music EKeys imported by the user (from an MMKV vault file,
 * a JSON map, or a line-based text dump). Maps file names → base64 EKey, and resolves
 * an EKey for a file being decrypted by matching on the file name / stem.
 *
 * This is what makes "no-key" QQ Music files (STag / musicex, e.g. `.mflac0.flac`)
 * decryptable offline: the user supplies the keys that the client kept in its database.
 */
object EkeyStore {

    private const val STORE_FILE = "ekeys.json"

    // Extensions stripped when reducing a name to its "stem", so that e.g.
    // "song.mflac0.flac" and "song.mflac" match the same key.
    private val STRIP_EXTS = setOf(
        "flac", "ogg", "mp3", "m4a", "wav", "ape", "aac", "mp4",
        "qmc", "qmc0", "qmc2", "qmc3", "qmc4", "qmc6", "qmc8", "qmcflac", "qmcogg",
        "mflac", "mflac0", "mflac1", "mflaca", "mflach", "mflacl", "mflacm",
        "mgg", "mgg0", "mgg1", "mgga", "mggh", "mggl", "mggm",
        "bkcmp3", "bkcm4a", "bkcflac", "bkcwav", "bkcape", "bkcogg", "bkcwma"
    )

    private val raw = LinkedHashMap<String, String>()   // original name → ekey
    private val byBase = HashMap<String, String>()       // normalized basename → ekey
    private val byStem = HashMap<String, String>()       // normalized stem → ekey
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        val f = File(context.filesDir, STORE_FILE)
        if (f.exists()) {
            try {
                val obj = JSONObject(f.readText())
                for (k in obj.keys()) putInternal(k, obj.getString(k))
            } catch (_: Exception) { /* corrupt store → start empty */ }
        }
        loaded = true
    }

    @Synchronized
    fun count(): Int = raw.size

    @Synchronized
    fun clear(context: Context) {
        raw.clear(); byBase.clear(); byStem.clear()
        File(context.filesDir, STORE_FILE).delete()
    }

    /** Resolve an EKey for any of [candidateNames] (file name / path / musicex MediaFileName). */
    @Synchronized
    fun resolve(candidateNames: List<String>): String? {
        for (name in candidateNames) {
            if (name.isBlank()) continue
            byBase[normBase(name)]?.let { return it }
        }
        for (name in candidateNames) {
            if (name.isBlank()) continue
            byStem[stem(name)]?.let { return it }
        }
        return null
    }

    /**
     * Import keys from a file's raw bytes. Auto-detects MMKV / JSON / line-based text.
     * Returns the number of keys added. Persists the merged store.
     */
    @Synchronized
    fun importFrom(context: Context, bytes: ByteArray): Int {
        val before = raw.size
        val map = parseImportedKeys(bytes)
        for ((name, ekey) in map) putInternal(name, ekey)
        save(context)
        return raw.size - before
    }

    internal fun parseImportedKeys(bytes: ByteArray): Map<String, String> = parseAny(bytes)

    private fun parseAny(bytes: ByteArray): Map<String, String> {
        // 1) MMKV binary
        try {
            val m = MmkvParser.parse(bytes)
            val filtered = m.filterValues { looksLikeEkey(it) }
            if (filtered.isNotEmpty()) return filtered
        } catch (_: Exception) { /* not MMKV */ }

        val text = String(bytes, Charsets.UTF_8).trim()
        // 2) JSON object { name: ekey }
        if (text.startsWith("{")) {
            try {
                val obj = JSONObject(text)
                val out = LinkedHashMap<String, String>()
                for (k in obj.keys()) {
                    val v = obj.optString(k)
                    if (looksLikeEkey(v)) out[k] = v
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) { /* not JSON */ }
        }
        // 3) line-based: name<sep>ekey  (sep = tab, '=', or ',')
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val sepIdx = listOf(l.indexOf('\t'), l.indexOf('='), l.lastIndexOf(','))
                .filter { it > 0 }
                .minOrNull() ?: -1
            if (sepIdx <= 0) continue
            val name = l.substring(0, sepIdx).trim()
            val ekey = l.substring(sepIdx + 1).trim()
            if (name.isNotEmpty() && looksLikeEkey(ekey)) out[name] = ekey
        }
        return out
    }

    private fun looksLikeEkey(v: String): Boolean {
        if (v.length < 16) return false
        return v.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }

    private fun putInternal(name: String, ekey: String) {
        raw[name] = ekey
        byBase[normBase(name)] = ekey
        byStem[stem(name)] = ekey
    }

    private fun save(context: Context) {
        val obj = JSONObject()
        for ((k, v) in raw) obj.put(k, v)
        File(context.filesDir, STORE_FILE).writeText(obj.toString())
    }

    private fun normBase(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return Normalizer.normalize(base, Normalizer.Form.NFC).lowercase().trim()
    }

    private fun stem(name: String): String {
        var s = normBase(name)
        while (true) {
            val dot = s.lastIndexOf('.')
            if (dot <= 0) break
            val ext = s.substring(dot + 1)
            if (ext in STRIP_EXTS) s = s.substring(0, dot) else break
        }
        return s
    }
}
