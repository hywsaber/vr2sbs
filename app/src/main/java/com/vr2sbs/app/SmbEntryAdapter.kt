package com.vr2sbs.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vr2sbs.R
import com.vr2sbs.databinding.ItemSmbEntryBinding
import com.vr2sbs.smb.MediaEntry
import com.vr2sbs.smb.SmbMediaLoader

class SmbEntryAdapter(
    private val entries: List<MediaEntry>,
    private val mediaLoader: SmbMediaLoader,
    private val highlightedUri: String?,
    private val onClick: (MediaEntry) -> Unit
) : RecyclerView.Adapter<SmbEntryAdapter.EntryHolder>() {
    private val positionsByUri = entries
        .asSequence()
        .mapIndexed { index, entry -> entry.uri to index }
        .toMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder = EntryHolder(
        ItemSmbEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: EntryHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun onBindViewHolder(holder: EntryHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_METADATA)) {
            holder.bindMetadata(entries[position])
        } else {
            holder.bind(entries[position])
        }
    }

    override fun getItemCount(): Int = entries.size

    /** Refreshes one row's details after its metadata was read. */
    fun refreshMetadata(uri: String) {
        val position = positionsByUri[uri] ?: return
        notifyItemChanged(position, PAYLOAD_METADATA)
    }

    /** Starts thumbnail loads for the rows currently on screen. */
    fun refreshThumbnails() {
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    inner class EntryHolder(
        private val binding: ItemSmbEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: MediaEntry) {
            binding.smbEntryName.text = entry.name
            binding.smbEntryThumbnail.tag = null
            if (entry.directory) {
                binding.smbEntryMeta.visibility = View.GONE
                binding.smbEntryThumbnail.setImageResource(R.drawable.ic_smb_folder)
            } else {
                binding.smbEntryMeta.visibility = View.VISIBLE
                bindMetadata(entry)
                mediaLoader.bind(entry, binding.smbEntryThumbnail)
            }
            applyHighlight(entry)
            binding.root.setOnClickListener { onClick(entry) }
        }

        fun bindMetadata(entry: MediaEntry) {
            if (entry.directory) return
            binding.smbEntryMeta.text = formatSmbEntryDetails(
                binding.root.context,
                entry,
                mediaLoader.cachedMetadata(entry)
            )
        }

        private fun applyHighlight(entry: MediaEntry) {
            val highlighted = entry.uri == highlightedUri
            binding.root.strokeWidth = binding.root.resources.getDimensionPixelSize(
                if (highlighted) {
                    R.dimen.smb_entry_highlight_stroke_width
                } else {
                    R.dimen.smb_entry_stroke_width
                }
            )
            binding.root.strokeColor = ContextCompat.getColor(
                binding.root.context,
                if (highlighted) R.color.catppuccin_mocha_blue else R.color.launcher_outline
            )
        }
    }

    private companion object {
        const val PAYLOAD_METADATA = "smb_entry_metadata"
    }
}
