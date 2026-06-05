package com.ncmdecrypt

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Best-effort root detection and a bounded `su` command runner. Detection is a cheap binary
 * existence check (it does NOT trigger a su grant prompt); the prompt appears the first time
 * [runAsRoot] actually executes.
 */
object RootHelper {

    private val DEFAULT_SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/odm/bin/su"
    )

    data class RootResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val launched: Boolean get() = exitCode != LAUNCH_FAILED
        companion object { const val LAUNCH_FAILED = -1000 }
    }

    fun isRootAvailable(paths: List<String> = DEFAULT_SU_PATHS): Boolean =
        paths.any { runCatching { File(it).exists() }.getOrDefault(false) }

    /** Runs [commands] (joined with newlines) under `su`, bounded by [timeoutMs]. */
    fun runAsRoot(commands: List<String>, timeoutMs: Long = 20_000): RootResult {
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(false).start()
            process.outputStream.bufferedWriter().use { w ->
                for (c in commands) { w.write(c); w.write("\n") }
                w.write("exit\n")
                w.flush()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return RootResult(RootResult.LAUNCH_FAILED, "", "timeout")
            }
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            RootResult(process.exitValue(), out, err)
        } catch (e: Exception) {
            RootResult(RootResult.LAUNCH_FAILED, "", e.message ?: "su failed")
        }
    }
}
