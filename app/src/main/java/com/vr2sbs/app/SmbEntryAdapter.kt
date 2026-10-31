package com.vr2sbs.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.vr2sbs.R
import com.vr2sbs.databinding.ItemSmbEntryBinding
import com.vr2sbs.smb.MediaEntry
import com.vr2sbs.smb.SmbThumbnailLoader

class SmbEntryAdapter(
    private val entries: List<MediaEntry>,
    private val thumbnailLoader: SmbThumbnailLoader,
    private val onClick: (MediaEntry) -> Unit
) : RecyclerView.Adapter<SmbEntryAdapter.EntryHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder = EntryHolder(
        ItemSmbEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: EntryHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class EntryHolder(
        private val binding: ItemSmbEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: MediaEntry) {
            binding.smbEntryName.text = entry.name
            binding.smbEntryThumbnail.tag = null
            if (entry.directory) {
                binding.smbEntryThumbnail.setImageResource(R.drawable.ic_smb_folder)
            } else {
                thumbnailLoader.bind(entry, binding.smbEntryThumbnail)
            }
            binding.root.setOnClickListener { onClick(entry) }
        }
    }
}
