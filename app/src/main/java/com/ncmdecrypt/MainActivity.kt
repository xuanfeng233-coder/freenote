package com.ncmdecrypt

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), FileListAdapter.Host, MetadataEditSheet.Host {

    private lateinit var fileRecyclerView: RecyclerView
    private lateinit var statusTextView: TextView
    private lateinit var selectFilesButton: MaterialButton
    private lateinit var decryptAllButton: MaterialButton
    private lateinit var importKeysButton: MaterialButton
    private lateinit var infoButton: ImageButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var emptyState: View
    private lateinit var playerUi: PlayerUiController

    /** Set true once we've asked for storage access this session (so we don't nag on every decrypt). */
    private var storagePromptShown = false
    /** When the user goes to grant access, resume the decrypt run on return. */
    private var startDecryptWhenGranted = false

    private val adapter = FileListAdapter(this, this)
    private val tracks = HashMap<Int, Track>()      // adapter position -> decrypted Track
    private var queuePositions: List<Int> = emptyList()  // current queue index -> adapter position

    private var pendingDecrypt = 0
    private var decryptSuccess = 0
    private var decryptFailed = 0
    /** Per-run save outcomes, used to pick an accurate "where did it go" toast. */
    private var anyMediaStoreFallback = false   // landed in MediaStore Music/FreeNote (Q+ fallback)
    private var anyCacheOnly = false            // no public copy at all (e.g. <Q without write grant)

    // ACTION_OPEN_DOCUMENT (SAF) routes to the system DocumentsUI ("Files"), which supports
    // multi-select; "*/*" keeps every file selectable (encrypted music has varied/unknown MIME
    // types). This replaces ACTION_GET_CONTENT, whose OEM pickers (e.g. Vivo) often single-select.
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            persistReadAccess(uris)
            loadFiles(uris)
        }
    }

    private val keyImporter = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importKeys(it) }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* playback works regardless; the notification just won't show if denied */ }

    // Returning from the "All files access" settings screen (Android 11+).
    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resumeDecryptAfterPermission() }

    // Legacy WRITE_EXTERNAL_STORAGE runtime grant (Android 10 and below).
    private val writeStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { resumeDecryptAfterPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fileRecyclerView = findViewById(R.id.fileRecyclerView)
        statusTextView = findViewById(R.id.statusTextView)
        selectFilesButton = findViewById(R.id.selectFilesButton)
        decryptAllButton = findViewById(R.id.decryptAllButton)
        importKeysButton = findViewById(R.id.importKeysButton)
        infoButton = findViewById(R.id.infoButton)
        progressBar = findViewById(R.id.progressBar)
        emptyState = findViewById(R.id.emptyState)

        EkeyStore.init(this)
        PlayerHub.init(this)

        fileRecyclerView.layoutManager = LinearLayoutManager(this)
        fileRecyclerView.adapter = adapter

        playerUi = PlayerUiController(this) { editCurrentTrack() }
        playerUi.attach()
        PlayerHub.onError = { _ ->
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!playerUi.onBackPressed()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        selectFilesButton.setOnClickListener { pickFiles() }
        decryptAllButton.setOnClickListener { onDecryptClicked() }
        importKeysButton.setOnClickListener { keyImporter.launch("*/*") }
        infoButton.setOnClickListener { showHelpDialog() }
        updateKeyCount()
    }

    override fun onDestroy() {
        PlayerHub.onError = null
        playerUi.detach()
        super.onDestroy()
    }

    // ── FileListAdapter.Host ─────────────────────────────────────────

    override fun trackAt(position: Int): Track? = tracks[position]

    override fun onPlay(position: Int) {
        requestNotificationPermissionIfNeeded()
        val ordered = ArrayList<Track>()
        val positions = ArrayList<Int>()
        for (i in adapter.items.indices) {
            tracks[i]?.let { ordered.add(it); positions.add(i) }
        }
        val startIndex = positions.indexOf(position)
        if (startIndex < 0 || ordered.isEmpty()) return
        queuePositions = positions
        PlayerHub.setQueue(ordered, startIndex, play = true)
    }

    override fun onEdit(position: Int) {
        val track = tracks[position] ?: return
        if (!track.tagsEditable) {
            Toast.makeText(this, R.string.tags_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        MetadataEditSheet.show(supportFragmentManager, position, track)
    }

    private fun editCurrentTrack() {
        val idx = PlayerHub.state.index
        val pos = queuePositions.getOrNull(idx) ?: return
        onEdit(pos)
    }

    // ── MetadataEditSheet.Host ───────────────────────────────────────

    override fun onMetadataSave(
        index: Int,
        title: String,
        artist: String,
        album: String,
        newCoverBytes: ByteArray?
    ) {
        val track = tracks[index] ?: return
        val file = File(track.filePath)
        Toast.makeText(this, R.string.saving, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val mime = sniffImageMime(newCoverBytes)
                MetadataEditor.write(file, title, artist, album, newCoverBytes, mime)
                val coverPath = if (newCoverBytes != null) {
                    TrackBuilder.writeCoverSidecar(file, newCoverBytes)
                } else {
                    track.coverPath
                }
                val finalTitle = title.ifBlank { track.title }
                val finalArtist = artist.ifBlank { getString(R.string.unknown_artist) }
                track.mediaStoreUri?.let {
                    mirrorToMediaStore(Uri.parse(it), file, finalTitle, finalArtist, album)
                }
                // Re-sync the public FreeNote copy with the freshly-tagged audio. Storage access can
                // be revoked between decrypt and edit, so re-check and surface a failure rather than
                // silently leaving the public file stale while claiming success.
                track.publicPath?.let { path ->
                    val resynced = canWritePublicStorage() && runCatching {
                        val pub = File(path)
                        copyFile(file, pub)
                        MediaScannerConnection.scanFile(this, arrayOf(pub.absolutePath), null, null)
                        true
                    }.getOrDefault(false)
                    if (!resynced) runOnUiThread {
                        Toast.makeText(this, R.string.resync_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                val newTrack = track.copy(
                    title = finalTitle,
                    artist = finalArtist,
                    album = album.trim(),
                    coverPath = coverPath
                )
                runOnUiThread {
                    tracks[index] = newTrack
                    adapter.refresh(index)
                    val qIdx = queuePositions.indexOf(index)
                    if (qIdx >= 0) PlayerHub.replaceTrack(qIdx, newTrack)
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.save_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun sniffImageMime(bytes: ByteArray?): String {
        if (bytes == null || bytes.size < 4) return "image/jpeg"
        // PNG signature 0x89 'P' 'N' 'G'
        return if (bytes[0].toInt() and 0xFF == 0x89 && bytes[1].toInt() == 0x50) "image/png"
        else "image/jpeg"
    }

    private fun mirrorToMediaStore(
        uri: Uri, file: File, title: String, artist: String, album: String
    ) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
            }
            contentResolver.update(uri, cv, null, null)
        } catch (_: Exception) {
            // MediaStore mirror is best-effort; the cache file (used for playback) is updated.
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── Key import ───────────────────────────────────────────────────

    private fun updateKeyCount() {
        val n = EkeyStore.count()
        importKeysButton.text = if (n > 0) getString(R.string.import_keys_n, n)
        else getString(R.string.import_keys)
    }

    private fun importKeys(uri: Uri) {
        Thread {
            val added = try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) -1 else EkeyStore.importFrom(this, bytes)
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                if (added >= 0) {
                    Toast.makeText(
                        this,
                        getString(R.string.keys_imported, added, EkeyStore.count()),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, R.string.keys_import_failed, Toast.LENGTH_LONG).show()
                }
                updateKeyCount()
            }
        }.start()
    }

    // ── File selection + decryption ──────────────────────────────────

    private fun pickFiles() {
        filePicker.launch(arrayOf("*/*"))
    }

    /**
     * Persists read access to the picked SAF documents so the decrypt run — which may start after a
     * config change or app switch — can still open them. Previously-held grants are released first to
     * stay under the system's per-app persisted-permission cap. Best-effort: providers that don't
     * support persistable grants (or ACTION_GET_CONTENT fallbacks) are simply skipped.
     */
    private fun persistReadAccess(uris: List<Uri>) {
        runCatching {
            contentResolver.persistedUriPermissions.forEach { p ->
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        }
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun loadFiles(uris: List<Uri>) {
        // Read each file's name + a small header off the UI thread so the format badge reflects the
        // *real* format (magic bytes), not just the extension — renamed NCM/KGM/VPR/KWM no longer
        // show "???". Opening N content streams on the main thread could otherwise jank/ANR.
        statusTextView.text = getString(R.string.status_selected, uris.size)
        emptyState.visibility = View.GONE
        Thread {
            val items = uris.map { uri ->
                val name = getFileName(uri) ?: "unknown"
                val header = readHeader(uri)
                FileItem(
                    path = uri.toString(),
                    displayName = name,
                    formatTag = MusicDecoder.formatTag(header, name)
                )
            }
            runOnUiThread {
                tracks.clear()
                queuePositions = emptyList()
                adapter.setItems(items)
                emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                statusTextView.text = getString(R.string.status_selected, items.size)
                decryptAllButton.isEnabled = items.isNotEmpty()
            }
        }.start()
    }

    /** Reads up to [n] header bytes from [uri] for magic-byte format detection (null on failure). */
    private fun readHeader(uri: Uri, n: Int = 64): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r <= 0) break
                read += r
            }
            if (read == n) buf else buf.copyOf(read)
        }
    } catch (_: Exception) {
        null
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIdx >= 0) {
                    return it.getString(nameIdx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    // ── Storage permission gate (for the public FreeNote output folder) ──

    /** True when we can write directly to /sdcard/FreeNote via the File API. */
    private fun canWritePublicStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Entry point for the "全部解密" button: ask for storage access once, then decrypt. */
    private fun onDecryptClicked() {
        if (!canWritePublicStorage() && !storagePromptShown) {
            storagePromptShown = true
            showStoragePermissionDialog()
        } else {
            startDecryptAll()
        }
    }

    private fun showStoragePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_permission_title)
            .setMessage(R.string.storage_permission_message)
            .setPositiveButton(R.string.action_grant) { _, _ ->
                startDecryptWhenGranted = true
                requestPublicStorageAccess()
            }
            .setNegativeButton(R.string.action_skip) { _, _ -> startDecryptAll() }
            .setOnCancelListener { startDecryptAll() }
            .show()
    }

    private fun requestPublicStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = try {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            try {
                allFilesAccessLauncher.launch(intent)
            } catch (_: Exception) {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            writeStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun resumeDecryptAfterPermission() {
        if (startDecryptWhenGranted) {
            startDecryptWhenGranted = false
            startDecryptAll()
        }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_message)
            .setPositiveButton(R.string.help_got_it, null)
            .show()
    }

    private fun startDecryptAll() {
        pendingDecrypt = adapter.itemCount
        decryptSuccess = 0
        decryptFailed = 0
        anyMediaStoreFallback = false
        anyCacheOnly = false
        progressBar.max = pendingDecrypt
        progressBar.setProgressCompat(0, false)
        progressBar.visibility = View.VISIBLE
        decryptAllButton.isEnabled = false
        selectFilesButton.isEnabled = false
        statusTextView.text = getString(R.string.status_decrypting, 0, pendingDecrypt)

        decryptNext(0)
    }

    private fun decryptNext(index: Int) {
        if (index >= adapter.itemCount) {
            onDecryptAllComplete()
            return
        }

        adapter.updateStatus(index, FileStatus.DECRYPTING)
        statusTextView.text = getString(R.string.status_decrypting, index + 1, pendingDecrypt)

        val item = adapter.items[index]
        val itemPath = item.path
        val uri = Uri.parse(itemPath)
        val displayName = item.displayName
        val formatTag = item.formatTag
        val workDir = File(cacheDir, "decrypt_work")
        workDir.mkdirs()

        Thread {
            try {
                val encryptedTemp = File(workDir, "enc_${index}_${System.nanoTime()}")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(encryptedTemp).use { output ->
                        val buf = ByteArray(65536)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                        }
                    }
                } ?: run {
                    runOnUiThread {
                        adapter.updateStatus(index, FileStatus.FAILED)
                        decryptFailed++
                        progressBar.setProgressCompat(progressBar.progress + 1, true)
                        decryptNext(index + 1)
                    }
                    encryptedTemp.delete()
                    return@Thread
                }

                val decryptedTemp = File(workDir, "dec_${index}_${System.nanoTime()}")
                val audioFmt = MusicDecoder.decryptFile(
                    encryptedTemp.absolutePath,
                    decryptedTemp.absolutePath,
                    displayName,
                    MusicDecoder.EkeyResolver { names -> EkeyStore.resolve(names) }
                )

                if (audioFmt != AudioFormat.UNKNOWN && decryptedTemp.length() > 0) {
                    val saved = saveDecrypted(displayName, decryptedTemp, audioFmt)
                    // Build the playable Track while the encrypted source still exists (NCM
                    // metadata/cover lives in its header).
                    val track = TrackBuilder.build(
                        originalName = displayName,
                        encryptedTempPath = encryptedTemp.absolutePath,
                        decryptedFile = saved.cacheFile,
                        mediaStoreUri = saved.mediaStoreUri,
                        formatTag = formatTag,
                        publicPath = saved.publicPath
                    )

                    encryptedTemp.delete()
                    decryptedTemp.delete()

                    runOnUiThread {
                        adapter.setResult(index, saved.cacheFile)
                        tracks[index] = track
                        adapter.updateStatus(index, FileStatus.SUCCESS)
                        decryptSuccess++
                        progressBar.setProgressCompat(progressBar.progress + 1, true)
                        decryptNext(index + 1)
                    }
                } else {
                    encryptedTemp.delete()
                    decryptedTemp.delete()
                    runOnUiThread {
                        adapter.updateStatus(
                            index, FileStatus.FAILED, getString(R.string.error_unknown_audio)
                        )
                        decryptFailed++
                        progressBar.setProgressCompat(progressBar.progress + 1, true)
                        decryptNext(index + 1)
                    }
                }
            } catch (e: Exception) {
                val detail = if (e is DecryptException) e.message else null
                runOnUiThread {
                    adapter.updateStatus(index, FileStatus.FAILED, detail)
                    decryptFailed++
                    progressBar.progress = progressBar.progress + 1
                    decryptNext(index + 1)
                }
            }
        }.start()
    }

    private fun onDecryptAllComplete() {
        progressBar.visibility = View.GONE
        decryptAllButton.isEnabled = true
        selectFilesButton.isEnabled = true
        statusTextView.text = getString(R.string.status_done, decryptSuccess, decryptFailed)
        if (decryptSuccess > 0) {
            val msg = when {
                anyCacheOnly -> R.string.saved_cache_only
                anyMediaStoreFallback -> R.string.saved_to_fallback
                else -> R.string.saved_to_folder
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Where a decrypted file ended up: the cached playable copy plus the public copy (one of
     *  [publicPath] = direct /sdcard/FreeNote file, or [mediaStoreUri] = MediaStore fallback). */
    private class SaveResult(
        val cacheFile: File,
        val publicPath: String?,
        val mediaStoreUri: String?
    )

    /**
     * Saves the decrypted audio: the public copy goes to the FreeNote folder under internal storage
     * (direct File write when "All files access" is granted; otherwise MediaStore "Music/FreeNote"
     * as a fallback), plus a cache copy used for in-app playback and FileProvider sharing.
     */
    private fun saveDecrypted(
        originalName: String,
        decryptedFile: File,
        audioFmt: AudioFormat
    ): SaveResult {
        val ext = extFor(audioFmt)
        val baseName = originalName.substringBeforeLast(".")
        val timestamp = SimpleDateFormat("_HHmmss", Locale.getDefault()).format(Date())
        val outputName = "${baseName}解锁$timestamp$ext"
        val mime = getMimeType(ext)

        var publicPath: String? = null
        var mediaStoreUri: String? = null

        // 1) Preferred: direct write to /sdcard/FreeNote.
        if (canWritePublicStorage()) {
            try {
                val dir = File(Environment.getExternalStorageDirectory(), getString(R.string.output_folder))
                if (dir.exists() || dir.mkdirs()) {
                    val out = File(dir, outputName)
                    copyFile(decryptedFile, out)
                    // Make it visible to music apps / the media scanner.
                    MediaScannerConnection.scanFile(this, arrayOf(out.absolutePath), arrayOf(mime), null)
                    publicPath = out.absolutePath
                }
            } catch (_: Exception) {
                publicPath = null
            }
        }

        // 2) Fallback when access wasn't granted: MediaStore Music/FreeNote (no special permission).
        if (publicPath == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreUri = saveViaMediaStore(outputName, mime, decryptedFile)
        }

        // Record the actual outcome so the completion toast is truthful about where files landed.
        when {
            publicPath != null -> { /* direct /sdcard/FreeNote — best case */ }
            mediaStoreUri != null -> anyMediaStoreFallback = true
            else -> anyCacheOnly = true   // no public copy (e.g. <Q with write denied)
        }

        // 3) Cache copy for the in-app player + sharing.
        val musicCacheDir = File(cacheDir, "decrypted_music").apply { mkdirs() }
        val cacheFile = File(musicCacheDir, outputName)
        copyFile(decryptedFile, cacheFile)

        return SaveResult(cacheFile, publicPath, mediaStoreUri)
    }

    /** Inserts [src] into MediaStore under Music/FreeNote (Android Q+). Returns its uri or null. */
    private fun saveViaMediaStore(outputName: String, mime: String, src: File): String? {
        val relPath = Environment.DIRECTORY_MUSIC + "/" + getString(R.string.output_folder)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                src.inputStream().use { it.copyTo(stream) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri.toString()
        } catch (_: Exception) {
            runCatching { contentResolver.delete(uri, null, null) }
            null
        }
    }

    private fun copyFile(src: File, dst: File) {
        src.inputStream().use { input ->
            FileOutputStream(dst).use { output -> input.copyTo(output) }
        }
    }

    private fun extFor(audioFmt: AudioFormat): String = when (audioFmt) {
        AudioFormat.MP3 -> ".mp3"
        AudioFormat.FLAC -> ".flac"
        AudioFormat.OGG -> ".ogg"
        AudioFormat.AAC -> ".aac"
        AudioFormat.WAV -> ".wav"
        AudioFormat.APE -> ".ape"
        else -> ".bin"
    }

    private fun getMimeType(ext: String): String = when (ext.lowercase()) {
        ".mp3" -> "audio/mpeg"
        ".flac" -> "audio/flac"
        ".ogg" -> "audio/ogg"
        ".wav" -> "audio/wav"
        ".aac" -> "audio/aac"
        ".ape" -> "audio/ape"
        else -> "audio/*"
    }
}
