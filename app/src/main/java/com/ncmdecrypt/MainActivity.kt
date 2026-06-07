package com.ncmdecrypt

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var outputDirChip: Chip
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var emptyState: View
    private lateinit var playerUi: PlayerUiController

    /** Set true once we've asked for legacy write access this session (so we don't nag). */
    private var legacyWritePromptShown = false
    /** When the legacy permission sheet returns, resume the decrypt run. */
    private var startDecryptAfterLegacyGrant = false

    private val adapter = FileListAdapter(this, this)
    private val tracks = HashMap<Int, Track>()      // adapter position -> decrypted Track
    private var queuePositions: List<Int> = emptyList()  // current queue index -> adapter position

    private var pendingDecrypt = 0
    private var decryptSuccess = 0
    private var decryptFailed = 0
    /** Per-run save outcomes, used to pick an accurate "where did it go" toast. */
    private var anyMediaStoreOutput = false     // landed in MediaStore Music/FreeNote (Q+ default)
    private var anyCustomDirOutput = false      // landed in the user's custom SAF folder
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

    private val outputDirPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            OutputDirStore.set(this, uri)
            updateOutputDirLabel()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* playback works regardless; the notification just won't show if denied */ }

    // Legacy WRITE_EXTERNAL_STORAGE runtime grant (Android 9 and below).
    private val writeStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { resumeDecryptAfterLegacyPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fileRecyclerView = findViewById(R.id.fileRecyclerView)
        statusTextView = findViewById(R.id.statusTextView)
        selectFilesButton = findViewById(R.id.selectFilesButton)
        decryptAllButton = findViewById(R.id.decryptAllButton)
        topAppBar = findViewById(R.id.topAppBar)
        outputDirChip = findViewById(R.id.outputDirChip)
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
        topAppBar.inflateMenu(R.menu.main_menu)
        topAppBar.menu.findItem(R.id.action_root_import).isVisible = RootHelper.isRootAvailable()
        topAppBar.menu.findItem(R.id.action_fetch_covers).isChecked = CoverPrefs.isEnabled(this)
        topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_keys -> { keyImporter.launch("*/*"); true }
                R.id.action_root_import -> { onRootImportClicked(); true }
                R.id.action_fetch_covers -> {
                    val enabled = !item.isChecked
                    item.isChecked = enabled
                    CoverPrefs.setEnabled(this, enabled)
                    true
                }
                R.id.action_help -> { showHelpDialog(); true }
                else -> false
            }
        }
        outputDirChip.setOnClickListener { onOutputDirClicked() }
        updateKeyCount()
        updateOutputDirLabel()
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
                var publicSyncFailed = false
                track.mediaStoreUri?.let {
                    if (!mirrorToMediaStore(Uri.parse(it), file, finalTitle, finalArtist, album)) {
                        publicSyncFailed = true
                    }
                }
                // Re-sync the legacy direct-path copy with the freshly-tagged audio. Storage access
                // can be revoked between decrypt and edit, so re-check and surface a failure rather
                // than silently leaving the public file stale while claiming success.
                track.publicPath?.let { path ->
                    val resynced = canWriteLegacyPublicStorage() && runCatching {
                        val pub = File(path)
                        copyFile(file, pub)
                        MediaScannerConnection.scanFile(this, arrayOf(pub.absolutePath), null, null)
                        true
                    }.getOrDefault(false)
                    if (!resynced) publicSyncFailed = true
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
                    val message = if (publicSyncFailed) R.string.resync_failed else R.string.saved
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
    ): Boolean {
        return try {
            val wrote = contentResolver.openOutputStream(uri, "wt")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (!wrote) return false
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
            }
            contentResolver.update(uri, cv, null, null)
            true
        } catch (_: Exception) {
            false
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
        topAppBar.menu.findItem(R.id.action_import_keys).title =
            if (n > 0) getString(R.string.import_keys_n, n) else getString(R.string.import_keys)
    }

    private fun updateOutputDirLabel() {
        val name = OutputDirStore.displayName(this)
        outputDirChip.text = if (name != null) getString(R.string.output_dir_set, name)
        else getString(R.string.output_dir_default)
    }

    private fun onOutputDirClicked() {
        if (OutputDirStore.getTreeUri(this) == null) {
            outputDirPicker.launch(null)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.output_dir_title)
            .setItems(
                arrayOf(getString(R.string.output_dir_change), getString(R.string.output_dir_reset))
            ) { _, which ->
                when (which) {
                    0 -> outputDirPicker.launch(null)
                    1 -> { OutputDirStore.clear(this); updateOutputDirLabel() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onRootImportClicked() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.root_import_confirm_title)
            .setMessage(R.string.root_import_confirm_msg)
            .setPositiveButton(R.string.root_import_continue) { _, _ -> runRootImport() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runRootImport() {
        Toast.makeText(this, R.string.root_import_running, Toast.LENGTH_SHORT).show()
        Thread {
            val result = QqMusicKeyImporter.importEkeys(this)
            runOnUiThread {
                val msg = when (result) {
                    is QqMusicKeyImporter.ImportResult.Success ->
                        getString(R.string.root_import_success, result.added, result.total)
                    is QqMusicKeyImporter.ImportResult.NoData ->
                        getString(R.string.root_import_no_keys)
                    is QqMusicKeyImporter.ImportResult.NoRoot ->
                        getString(R.string.root_import_no_root)
                    is QqMusicKeyImporter.ImportResult.RootDenied ->
                        getString(R.string.root_import_denied)
                    is QqMusicKeyImporter.ImportResult.Error ->
                        getString(R.string.root_import_failed)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                updateKeyCount()
            }
        }.start()
    }

    private fun importKeys(uri: Uri) {
        Thread {
            var tooLarge = false
            val added = try {
                val bytes = BoundedInput.readBytes(contentResolver, uri, MAX_KEY_IMPORT_BYTES)
                EkeyStore.importFrom(this, bytes)
            } catch (_: InputTooLargeException) {
                tooLarge = true
                -1
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                when {
                    added >= 0 -> {
                        Toast.makeText(
                            this,
                            getString(R.string.keys_imported, added, EkeyStore.count()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    tooLarge -> {
                        Toast.makeText(
                            this,
                            getString(R.string.keys_import_too_large, MAX_KEY_IMPORT_MB),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> {
                        Toast.makeText(this, R.string.keys_import_failed, Toast.LENGTH_LONG).show()
                    }
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

    // ── Storage permission gate (legacy Android 9 and below only) ──

    private fun needsLegacyWritePermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /** True when we can write directly to /sdcard/FreeNote via the File API on legacy Android. */
    private fun canWriteLegacyPublicStorage(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Entry point for the "全部解密" button: request only legacy write access when needed. */
    private fun onDecryptClicked() {
        if (needsLegacyWritePermission() && !legacyWritePromptShown) {
            legacyWritePromptShown = true
            startDecryptAfterLegacyGrant = true
            writeStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDecryptAll()
        }
    }

    private fun resumeDecryptAfterLegacyPermission() {
        if (startDecryptAfterLegacyGrant) {
            startDecryptAfterLegacyGrant = false
            startDecryptAll()
        }
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_message)
            .setNeutralButton(R.string.help_download_apk) { _, _ -> openDowngradeApks() }
            .setPositiveButton(R.string.help_got_it, null)
            .show()
    }

    private fun openDowngradeApks() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.help_apk_url))))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDecryptAll() {
        pendingDecrypt = adapter.itemCount
        decryptSuccess = 0
        decryptFailed = 0
        anyMediaStoreOutput = false
        anyCustomDirOutput = false
        anyCacheOnly = false
        progressBar.max = pendingDecrypt
        progressBar.setProgressCompat(0, false)
        progressBar.visibility = View.VISIBLE
        decryptAllButton.isEnabled = false
        selectFilesButton.isEnabled = false
        statusTextView.text = getString(R.string.status_decrypting, 0, pendingDecrypt)
        cleanDecryptWorkDir()

        decryptNext(0)
    }

    private fun cleanDecryptWorkDir() {
        val workDir = File(cacheDir, "decrypt_work")
        if (workDir.exists()) {
            workDir.listFiles()?.forEach { child ->
                runCatching { child.deleteRecursively() }
            }
        }
        workDir.mkdirs()
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
            var encryptedTemp: File? = null
            var decryptedTemp: File? = null
            try {
                val encryptedFile = File(workDir, "enc_${index}_${System.nanoTime()}")
                encryptedTemp = encryptedFile
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(encryptedFile).use { output ->
                        val buf = ByteArray(65536)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                        }
                    }
                } ?: run {
                    runOnUiThread {
                        adapter.updateStatus(
                            index, FileStatus.FAILED, getString(R.string.error_read_failed)
                        )
                        decryptFailed++
                        progressBar.setProgressCompat(progressBar.progress + 1, true)
                        decryptNext(index + 1)
                    }
                    return@Thread
                }

                val decryptedFile = File(workDir, "dec_${index}_${System.nanoTime()}")
                decryptedTemp = decryptedFile
                val audioFmt = MusicDecoder.decryptFile(
                    encryptedFile.absolutePath,
                    decryptedFile.absolutePath,
                    displayName,
                    MusicDecoder.EkeyResolver { names -> EkeyStore.resolve(names) }
                )

                if (audioFmt != AudioFormat.UNKNOWN && decryptedFile.length() > 0) {
                    // Embed cover + title/artist/album so the public output matches other
                    // unlockers. jAudioTagger picks its reader by extension, so give the temp the
                    // right one first. NCM bodies carry no tags — their metadata/cover live in the
                    // encrypted header; QMC/KGM bodies already carry tags, so this is a no-op for
                    // them (file stays byte-identical). Best-effort: never fails the decrypt.
                    val toSave = run {
                        val tagged = File(workDir, decryptedFile.name + extFor(audioFmt))
                        if (decryptedFile.renameTo(tagged)) {
                            decryptedTemp = tagged
                            val ncm = runCatching {
                                MusicDecoder.extractNcmInfo(encryptedFile.absolutePath)
                            }.getOrNull()
                            MetadataEditor.embedIfMissing(
                                tagged, audioFmt,
                                ncm?.title, ncm?.artist, ncm?.album, ncm?.coverBytes
                            )
                            tagged
                        } else {
                            decryptedFile
                        }
                    }

                    // If the output still has no cover (QMC/KGM/KWM often ship cover-less, unlike
                    // NCM whose header carries one), look it up online from the origin platform.
                    // Best-effort, gated by the user toggle; never fails the decrypt.
                    maybeFetchCover(toSave, audioFmt, formatTag, displayName)

                    val saved = saveDecrypted(displayName, toSave, audioFmt)
                    // Build the playable Track while the encrypted source still exists (NCM
                    // metadata/cover lives in its header).
                    val track = TrackBuilder.build(
                        originalName = displayName,
                        encryptedTempPath = encryptedFile.absolutePath,
                        decryptedFile = saved.cacheFile,
                        mediaStoreUri = saved.mediaStoreUri ?: saved.customDirUri,
                        formatTag = formatTag,
                        publicPath = saved.publicPath
                    )

                    runOnUiThread {
                        adapter.setResult(index, saved.cacheFile)
                        tracks[index] = track
                        adapter.updateStatus(index, FileStatus.SUCCESS)
                        decryptSuccess++
                        progressBar.setProgressCompat(progressBar.progress + 1, true)
                        decryptNext(index + 1)
                    }
                } else {
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
                val detail = safeDecryptError(e)
                runOnUiThread {
                    adapter.updateStatus(index, FileStatus.FAILED, detail)
                    decryptFailed++
                    progressBar.progress = progressBar.progress + 1
                    decryptNext(index + 1)
                }
            } finally {
                runCatching { encryptedTemp?.delete() }
                runCatching { decryptedTemp?.delete() }
            }
        }.start()
    }

    private fun safeDecryptError(e: Exception): String {
        val message = if (e is DecryptException) e.message else null
        return message
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_ERROR_DETAIL_CHARS)
            ?: getString(R.string.error_decrypt_failed)
    }

    /**
     * When enabled, look the album cover up online for an output that has none, and embed it. NCM
     * already carries its cover in the header (embedded earlier), so this mainly serves QMC/KGM/KWM,
     * whose decrypted streams are frequently cover-less. Queries the origin platform first (see
     * [CoverFetcher]). Runs on the existing background thread; best-effort, swallows all errors.
     */
    private fun maybeFetchCover(
        file: File,
        audioFmt: AudioFormat,
        platformTag: String,
        displayName: String
    ) {
        if (!CoverPrefs.isEnabled(this)) return
        if (!MetadataEditor.isEmbeddable(audioFmt)) return
        try {
            val existing = MetadataEditor.read(file)
            val existingCover = existing?.coverBytes
            if (existingCover != null && existingCover.isNotEmpty()) return // already has artwork

            // Try the file name first (its "Artist - Title" is usually truer than generic embedded
            // tags like "track 03"), then fall back to the embedded title/artist.
            val candidates = buildList {
                CoverFetcher.parseFromFilename(displayName)?.let { add(it) }
                val a = existing?.artist?.takeIf { it.isNotBlank() }
                val t = existing?.title?.takeIf { it.isNotBlank() }
                if (a != null && t != null) add(CoverFetcher.Query(a, t))
            }.distinct()

            for (q in candidates) {
                val cover = CoverFetcher.fetch(platformTag, q) ?: continue
                MetadataEditor.embedIfMissing(file, audioFmt, q.title, q.artist, existing?.album, cover)
                return
            }
        } catch (_: Exception) {
            // Best-effort: the decrypted audio is already correct without a cover.
        }
    }

    private fun onDecryptAllComplete() {
        progressBar.visibility = View.GONE
        decryptAllButton.isEnabled = true
        selectFilesButton.isEnabled = true
        statusTextView.text = getString(R.string.status_done, decryptSuccess, decryptFailed)
        if (decryptSuccess > 0) {
            val msg = when {
                anyCacheOnly -> R.string.saved_cache_only
                anyCustomDirOutput -> R.string.saved_to_custom_dir
                anyMediaStoreOutput -> R.string.saved_to_music_folder
                else -> R.string.saved_to_folder
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Where a decrypted file ended up: the cached playable copy plus the public copy (one of
     *  [mediaStoreUri] = MediaStore Music/FreeNote, or [publicPath] = legacy direct path). */
    private class SaveResult(
        val cacheFile: File,
        val publicPath: String?,
        val mediaStoreUri: String?,
        val customDirUri: String?
    )

    /**
     * Saves the decrypted audio: Android 10+ writes the public copy through MediaStore under
     * Music/FreeNote, legacy Android writes directly to /sdcard/FreeNote when WRITE permission is
     * granted, and every successful decrypt gets a cache copy for playback/FileProvider sharing.
     */
    private fun saveDecrypted(
        originalName: String,
        decryptedFile: File,
        audioFmt: AudioFormat
    ): SaveResult {
        val ext = extFor(audioFmt)
        val baseName = FilenameSanitizer.sanitizeBase(originalName.substringBeforeLast("."))
        val timestamp = SimpleDateFormat("_HHmmss", Locale.getDefault()).format(Date())
        val outputName = FilenameSanitizer.sanitizeFileName("${baseName}解锁$timestamp$ext")
        val mime = getMimeType(ext)

        var publicPath: String? = null
        var mediaStoreUri: String? = null
        var customDirUri: String? = null

        // 0) User-chosen custom folder (SAF) takes priority on any Android version.
        OutputDirStore.getTreeUri(this)?.let { tree ->
            customDirUri = saveViaTreeUri(tree, outputName, mime, decryptedFile)
        }

        // 1) Preferred on public releases: MediaStore Music/FreeNote, no broad storage permission.
        if (customDirUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreUri = saveViaMediaStore(outputName, mime, decryptedFile)
        }

        // 2) Legacy Android 9 and below: direct write to /sdcard/FreeNote when permitted.
        if (customDirUri == null && mediaStoreUri == null &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            canWriteLegacyPublicStorage()
        ) {
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

        // Record the actual outcome so the completion toast is truthful about where files landed.
        when {
            customDirUri != null -> anyCustomDirOutput = true
            mediaStoreUri != null -> anyMediaStoreOutput = true
            publicPath != null -> { /* legacy direct /sdcard/FreeNote */ }
            else -> anyCacheOnly = true   // no public copy (e.g. <Q with write denied)
        }

        // 3) Cache copy for the in-app player + sharing.
        val musicCacheDir = File(cacheDir, "decrypted_music").apply { mkdirs() }
        val cacheFile = File(musicCacheDir, outputName)
        copyFile(decryptedFile, cacheFile)

        return SaveResult(cacheFile, publicPath, mediaStoreUri, customDirUri)
    }

    /** Inserts [src] into MediaStore under Music/FreeNote (Android Q+). Returns its uri or null. */
    private fun saveViaMediaStore(outputName: String, mime: String, src: File): String? {
        val relPath = Environment.DIRECTORY_MUSIC + "/" + getString(R.string.output_folder)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val copied = contentResolver.openOutputStream(uri)?.use { stream ->
                src.inputStream().use { it.copyTo(stream) }
                true
            } ?: false
            if (!copied) throw IllegalStateException("MediaStore output unavailable")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri.toString()
        } catch (_: Exception) {
            runCatching { contentResolver.delete(uri, null, null) }
            null
        }
    }

    /** Writes [src] into the user's chosen SAF tree. Returns the new document uri or null. */
    private fun saveViaTreeUri(treeUri: Uri, outputName: String, mime: String, src: File): String? {
        return try {
            val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return null
            val doc = dir.createFile(mime, outputName) ?: return null
            val ok = contentResolver.openOutputStream(doc.uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (ok) doc.uri.toString() else { runCatching { doc.delete() }; null }
        } catch (_: Exception) {
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

    companion object {
        private const val MAX_KEY_IMPORT_MB = 16
        private const val MAX_KEY_IMPORT_BYTES = MAX_KEY_IMPORT_MB * 1024L * 1024L
        private const val MAX_ERROR_DETAIL_CHARS = 96
    }
}
