package com.ncmdecrypt

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File

data class FileItem(
    val path: String,
    val displayName: String,
    val formatTag: String,
    val status: FileStatus = FileStatus.PENDING,
    val detail: String? = null   // failure reason, shown on FAILED
)

enum class FileStatus {
    PENDING,
    DECRYPTING,
    SUCCESS,
    FAILED
}

class FileListAdapter(
    private val context: Context,
    private val host: Host
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    /** Callbacks into the hosting activity. */
    interface Host {
        fun onPlay(position: Int)
        fun onEdit(position: Int)
        fun trackAt(position: Int): Track?
    }

    val items: MutableList<FileItem> = mutableListOf()
    private val results = mutableMapOf<Int, File>()  // position -> decrypted output file

    fun setItems(newItems: List<FileItem>) {
        items.clear()
        items.addAll(newItems)
        results.clear()
        notifyDataSetChanged()
    }

    fun updateStatus(position: Int, status: FileStatus, detail: String? = null) {
        if (position in items.indices) {
            items[position] = items[position].copy(status = status, detail = detail)
            notifyItemChanged(position)
        }
    }

    fun setResult(position: Int, outputFile: File) {
        results[position] = outputFile
    }

    fun getDecryptedFile(position: Int): File? = results[position]

    /** Re-bind a row after its track metadata / cover changed. */
    fun refresh(position: Int) {
        if (position in items.indices) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverThumb: ShapeableImageView = itemView.findViewById(R.id.coverThumb)
        private val formatBadge: TextView = itemView.findViewById(R.id.formatBadge)
        private val fileName: TextView = itemView.findViewById(R.id.fileNameTextView)
        private val detailText: TextView = itemView.findViewById(R.id.detailTextView)
        private val statusIcon: ImageView = itemView.findViewById(R.id.statusIcon)
        private val actionsRow: View = itemView.findViewById(R.id.actionsRow)
        private val playButton: MaterialButton = itemView.findViewById(R.id.playButton)
        private val editTagsButton: MaterialButton = itemView.findViewById(R.id.editTagsButton)
        private val shareButton: MaterialButton = itemView.findViewById(R.id.shareButton)
        private val itemProgress: CircularProgressIndicator = itemView.findViewById(R.id.itemProgress)

        fun bind(item: FileItem, position: Int) {
            formatBadge.text = item.formatTag
            fileName.text = item.displayName
            val outputFile = results[position]
            val track = host.trackAt(position)

            if (item.status == FileStatus.FAILED && !item.detail.isNullOrEmpty()) {
                detailText.text = item.detail
                detailText.visibility = View.VISIBLE
            } else {
                detailText.visibility = View.GONE
            }

            bindCover(track, item.status)

            when (item.status) {
                FileStatus.PENDING -> {
                    statusIcon.visibility = View.GONE
                    itemProgress.visibility = View.GONE
                    actionsRow.visibility = View.GONE
                }
                FileStatus.DECRYPTING -> {
                    statusIcon.visibility = View.GONE
                    itemProgress.visibility = View.VISIBLE
                    itemProgress.contentDescription = context.getString(R.string.cd_decrypting)
                    actionsRow.visibility = View.GONE
                }
                FileStatus.SUCCESS -> {
                    itemProgress.visibility = View.GONE
                    showStatusIcon(
                        R.drawable.ic_check_circle,
                        com.google.android.material.R.attr.colorPrimary,
                        R.string.cd_status_success
                    )
                    if (outputFile != null && outputFile.exists()) {
                        actionsRow.visibility = View.VISIBLE
                        playButton.setOnClickListener { host.onPlay(bindingAdapterPosition) }
                        editTagsButton.visibility =
                            if (track?.tagsEditable == true) View.VISIBLE else View.GONE
                        editTagsButton.setOnClickListener { host.onEdit(bindingAdapterPosition) }
                        setupShareButton(outputFile)
                    } else {
                        actionsRow.visibility = View.GONE
                    }
                }
                FileStatus.FAILED -> {
                    itemProgress.visibility = View.GONE
                    actionsRow.visibility = View.GONE
                    showStatusIcon(
                        R.drawable.ic_error,
                        com.google.android.material.R.attr.colorError,
                        R.string.cd_status_failed
                    )
                }
            }
        }

        private fun bindCover(track: Track?, status: FileStatus) {
            val path = track?.coverPath
            if (status == FileStatus.SUCCESS && path != null && File(path).exists()) {
                val bmp = runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(path, opts)
                }.getOrNull()
                if (bmp != null) {
                    coverThumb.setImageBitmap(bmp)
                    coverThumb.visibility = View.VISIBLE
                    return
                }
            }
            coverThumb.visibility = View.GONE
        }

        private fun showStatusIcon(iconRes: Int, colorAttr: Int, descRes: Int) {
            statusIcon.setImageResource(iconRes)
            statusIcon.setColorFilter(MaterialColors.getColor(statusIcon, colorAttr))
            statusIcon.contentDescription = context.getString(descRes)
            statusIcon.visibility = View.VISIBLE
        }

        private fun setupShareButton(file: File) {
            shareButton.setOnClickListener {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = getMimeType(file.extension)
                    putExtra(Intent.EXTRA_STREAM, uriFor(file))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.share_chooser_title))
                    )
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.error_share_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun uriFor(file: File) = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        private fun getMimeType(ext: String): String = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "ape" -> "audio/ape"
            else -> "audio/*"
        }
    }
}
