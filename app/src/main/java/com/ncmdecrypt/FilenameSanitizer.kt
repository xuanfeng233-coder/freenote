package com.ncmdecrypt

import java.text.Normalizer

/**
 * Keeps untrusted display names readable while making them safe as a single filesystem component.
 */
object FilenameSanitizer {
    private const val DEFAULT_MAX_CODE_POINTS = 64
    private val reservedChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    private val repeatedDots = Regex("\\.{2,}")
    private val whitespace = Regex("\\s+")

    fun sanitizeBase(
        value: String,
        fallback: String = "track",
        maxCodePoints: Int = DEFAULT_MAX_CODE_POINTS
    ): String = sanitizeComponent(value, fallback, maxCodePoints)

    fun sanitizeFileName(
        value: String,
        fallback: String = "file",
        maxCodePoints: Int = DEFAULT_MAX_CODE_POINTS + 32
    ): String = sanitizeComponent(value, fallback, maxCodePoints)

    private fun sanitizeComponent(
        value: String,
        fallback: String,
        maxCodePoints: Int
    ): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        val withoutReserved = buildString(normalized.length) {
            normalized.forEach { ch ->
                when {
                    ch in reservedChars -> append(' ')
                    ch.isISOControl() -> append(' ')
                    else -> append(ch)
                }
            }
        }
        val cleaned = withoutReserved
            .replace(repeatedDots, " ")
            .replace(whitespace, " ")
            .trim()
            .trim('.')
        val fallbackCleaned = fallback.trim().trim('.').ifBlank { "file" }
        val safe = cleaned.ifBlank { fallbackCleaned }
        return safe.limitCodePoints(maxCodePoints).trim().trim('.').ifBlank { fallbackCleaned }
    }

    private fun String.limitCodePoints(maxCodePoints: Int): String {
        if (maxCodePoints <= 0) return ""
        var count = 0
        var end = 0
        while (end < length && count < maxCodePoints) {
            end += Character.charCount(codePointAt(end))
            count++
        }
        return substring(0, end)
    }
}
