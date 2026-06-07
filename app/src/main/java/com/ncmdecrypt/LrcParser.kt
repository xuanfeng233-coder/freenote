package com.ncmdecrypt

/** One time-tagged lyric line. [timeMs] is the start offset; [text] is the lyric text. */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Parses LRC text into time-sorted [LrcLine]s. Handles multiple timestamps on one line
 * (`[00:01.00][00:05.00]词`), `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]` forms, and skips pure
 * ID-tag / blank lines (`[ti:]`, `[ar:]`, `[al:]`, `[by:]`, `[offset:]`, …). Pure + side-effect free.
 */
object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(lrc: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (rawLine in lrc.lineSequence()) {
            val matches = TIME_TAG.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val text = rawLine.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val millis = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out.add(LrcLine(min * 60_000 + sec * 1_000 + millis, text))
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    /** True when the text has ≥1 real timed lyric line (not just ID-tag metadata). */
    fun hasRealLyrics(lrc: String?): Boolean = !lrc.isNullOrBlank() && parse(lrc).isNotEmpty()
}
