package com.ncmdecrypt

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import java.io.File

/**
 * Editor for a track's title / artist / album / cover, shown as a Material bottom sheet.
 * Collects the new values and hands them to the host activity (which performs the actual
 * tag write off the main thread). New cover bytes are kept in-memory until save.
 */
class MetadataEditSheet : BottomSheetDialogFragment() {

    interface Host {
        fun onMetadataSave(
            index: Int,
            title: String,
            artist: String,
            album: String,
            newCoverBytes: ByteArray?
        )
    }

    private var pendingCover: ByteArray? = null

    private val coverPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { onCoverPicked(it) } }

    private lateinit var coverView: ShapeableImageView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_metadata, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        val index = args.getInt(ARG_INDEX)

        coverView = view.findViewById(R.id.editCover)
        val titleInput: TextInputEditText = view.findViewById(R.id.titleInput)
        val artistInput: TextInputEditText = view.findViewById(R.id.artistInput)
        val albumInput: TextInputEditText = view.findViewById(R.id.albumInput)

        titleInput.setText(args.getString(ARG_TITLE).orEmpty())
        artistInput.setText(args.getString(ARG_ARTIST).orEmpty())
        albumInput.setText(args.getString(ARG_ALBUM).orEmpty())

        args.getString(ARG_COVER)?.let { path ->
            val f = File(path)
            if (f.exists()) {
                BitmapFactory.decodeFile(path)?.let { coverView.setImageBitmap(it) }
            }
        }

        view.findViewById<MaterialButton>(R.id.changeCoverButton).setOnClickListener {
            coverPicker.launch("image/*")
        }
        view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            (activity as? Host)?.onMetadataSave(
                index,
                titleInput.text?.toString().orEmpty(),
                artistInput.text?.toString().orEmpty(),
                albumInput.text?.toString().orEmpty(),
                pendingCover
            )
            dismiss()
        }
    }

    private fun onCoverPicked(uri: Uri) {
        val bytes = try {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return
        pendingCover = bytes
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { coverView.setImageBitmap(it) }
    }

    companion object {
        private const val ARG_INDEX = "index"
        private const val ARG_TITLE = "title"
        private const val ARG_ARTIST = "artist"
        private const val ARG_ALBUM = "album"
        private const val ARG_COVER = "cover"

        fun show(fm: FragmentManager, index: Int, track: Track) {
            MetadataEditSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_INDEX, index)
                    putString(ARG_TITLE, track.title)
                    putString(ARG_ARTIST, track.artist)
                    putString(ARG_ALBUM, track.album)
                    putString(ARG_COVER, track.coverPath)
                }
            }.show(fm, "metadata_edit")
        }
    }
}
