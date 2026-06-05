package com.ncmdecrypt

import android.content.Context
import java.io.File

/**
 * On a rooted device, copies QQ Music's plaintext MMKV ekey vault out of its private
 * /data/data/<pkg>/files/mmkv directory and imports the keys via [EkeyStore]. Only the
 * plaintext MMKVStreamEncryptId vault is parseable (see CLAUDE.md invariant #5); an encrypted
 * vault yields zero keys and is reported as [ImportResult.NoData]. Run off the main thread.
 */
object QqMusicKeyImporter {

    private val CANDIDATE_PACKAGES = listOf(
        "com.tencent.qqmusic", "com.tencent.qqmusiclite", "com.tencent.qqmusicpad"
    )

    private const val FOUND_MARKER = "FREENOTE_FOUND:"

    sealed class ImportResult {
        data class Success(val added: Int, val total: Int, val pkg: String) : ImportResult()
        object NoRoot : ImportResult()
        object RootDenied : ImportResult()
        object NoData : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    fun importEkeys(context: Context): ImportResult {
        if (!RootHelper.isRootAvailable()) return ImportResult.NoRoot

        val tmpRoot = File(context.cacheDir, "root_vault_tmp")
        runCatching { tmpRoot.deleteRecursively() }
        tmpRoot.mkdirs()

        // For each candidate, copy its mmkv dir into tmp/<pkg> and echo a marker if present.
        val script = buildList {
            for (pkg in CANDIDATE_PACKAGES) {
                val dst = File(tmpRoot, pkg).absolutePath
                val srcDir = "/data/data/$pkg/files/mmkv"
                add(
                    "if [ -d $srcDir ]; then mkdir -p $dst; cp -r $srcDir/. $dst/ 2>/dev/null; " +
                        "chmod -R 666 $dst 2>/dev/null; echo $FOUND_MARKER$pkg; fi"
                )
            }
        }

        val result = RootHelper.runAsRoot(script)
        if (!result.launched) return ImportResult.RootDenied

        val foundPkgs = result.stdout.lineSequence()
            .filter { it.startsWith(FOUND_MARKER) }
            .map { it.removePrefix(FOUND_MARKER).trim() }
            .toList()

        return try {
            var added = 0
            var contributingPkg: String? = null
            for (pkg in foundPkgs) {
                val dir = File(tmpRoot, pkg)
                val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".crc") } ?: emptyList()
                for (f in files) {
                    val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                    val n = runCatching { EkeyStore.importFrom(context, bytes) }.getOrDefault(0)
                    if (n > 0) {
                        added += n
                        if (contributingPkg == null) contributingPkg = pkg
                    }
                }
            }
            when {
                foundPkgs.isEmpty() && result.stdout.isBlank() && result.stderr.isNotBlank() ->
                    ImportResult.RootDenied
                added > 0 ->
                    ImportResult.Success(added, EkeyStore.count(), contributingPkg ?: foundPkgs.first())
                else -> ImportResult.NoData
            }
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "import failed")
        } finally {
            runCatching { tmpRoot.deleteRecursively() }
        }
    }
}
