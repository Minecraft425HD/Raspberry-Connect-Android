package com.raspberryconnect.terminal.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.raspberryconnect.terminal.data.ConnectionProfile
import com.raspberryconnect.terminal.databinding.ItemConnectionBinding

class ConnectionListAdapter(
    private val onClick: (ConnectionProfile) -> Unit,
    private val onEdit: (ConnectionProfile) -> Unit,
    private val onDelete: (ConnectionProfile) -> Unit
) : ListAdapter<ConnectionProfile, ConnectionListAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConnectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemConnectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(profile: ConnectionProfile) {
            binding.textName.text = profile.name
            binding.textHost.text = "${profile.username}@${profile.host}:${profile.port}"
            binding.root.setOnClickListener { onClick(profile) }
            binding.buttonEdit.setOnClickListener { onEdit(profile) }
            binding.buttonDelete.setOnClickListener { onDelete(profile) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConnectionProfile>() {
            override fun areItemsTheSame(oldItem: ConnectionProfile, newItem: ConnectionProfile) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ConnectionProfile, newItem: ConnectionProfile) =
                oldItem == newItem
        }
    }
}
