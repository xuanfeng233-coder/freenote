package com.ncmdecrypt

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shared best-effort HTTP GET helpers for the app's only networked features — online cover
 * ([CoverFetcher]) and lyric ([LyricsFetcher]) completion. Plain HttpURLConnection; any failure
 * returns null and never throws. Audio decryption never touches this; only title/artist text does.
 */
object MusicHttp {
    private const val TIMEOUT_MS = 8000
    const val UA = "Mozilla/5.0 (Linux; Android) FreeNote"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun getString(urlStr: String, referer: String? = null): String? =
        openGet(urlStr, referer) { it.bufferedReader(Charsets.UTF_8).readText() }

    fun getBytes(urlStr: String, referer: String? = null): ByteArray? =
        openGet(urlStr, referer) { it.readBytes() }

    private fun <T> openGet(urlStr: String, referer: String?, read: (java.io.InputStream) -> T): T? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            if (referer != null) setRequestProperty("Referer", referer)
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.use { read(it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
