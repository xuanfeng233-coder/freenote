package com.ncmdecrypt

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Persists the user's optional custom output directory: a SAF tree Uri the user picked via
 * ACTION_OPEN_DOCUMENT_TREE. When unset (or the persisted permission was lost), callers fall
 * back to the default MediaStore Music/FreeNote (Q+) or legacy /sdcard/FreeNote (<=P).
 */
object OutputDirStore {

    private const val PREFS = "output_dir"
    private const val KEY_TREE_URI = "tree_uri"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Store the chosen tree Uri string. Persistable permission is taken by the caller. */
    fun set(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    /** Forget the override and release any persisted permission we hold for it. */
    fun clear(context: Context) {
        val current = rawUri(context)
        prefs(context).edit().remove(KEY_TREE_URI).apply()
        if (current != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    current,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    /** The stored tree Uri only if we still hold a writable persisted permission; else null. */
    fun getTreeUri(context: Context): Uri? {
        val uri = rawUri(context) ?: return null
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return if (held) uri else null
    }

    /** Human-readable folder name for the button label, or null if unavailable. */
    fun displayName(context: Context): String? {
        val uri = getTreeUri(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
    }

    private fun rawUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
}
