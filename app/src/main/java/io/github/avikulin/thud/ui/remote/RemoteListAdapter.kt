package io.github.avikulin.thud.ui.remote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.avikulin.thud.R
import io.github.avikulin.thud.service.RemoteControlManager.RemoteConfig

class RemoteListAdapter(
    private val onItemSelected: (Int) -> Unit,
    private val onEnabledChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<RemoteListAdapter.ViewHolder>() {

    var items: List<RemoteConfig> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var selectedIndex: Int = -1
        set(value) { val old = field; field = value; if (old >= 0) notifyItemChanged(old); if (value >= 0) notifyItemChanged(value) }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRemoteName: TextView = view.findViewById(R.id.tvRemoteName)
        val tvBindingsCount: TextView = view.findViewById(R.id.tvBindingsCount)
        val cbEnabled: CheckBox = view.findViewById(R.id.cbEnabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_remote_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = items[position]
        val context = holder.itemView.context

        holder.tvRemoteName.text = config.alias.ifBlank { config.deviceName }
        val bindingCount = config.bindings.count { it.keyCode != null }
        holder.tvBindingsCount.text = context.getString(R.string.remote_bindings_count, bindingCount)

        holder.cbEnabled.setOnCheckedChangeListener(null)
        holder.cbEnabled.isChecked = config.enabled
        holder.cbEnabled.setOnCheckedChangeListener { _, isChecked ->
            onEnabledChanged(holder.adapterPosition, isChecked)
        }

        val isSelected = position == selectedIndex
        holder.itemView.setBackgroundColor(
            if (isSelected) ContextCompat.getColor(context, R.color.workout_list_item_selected)
            else ContextCompat.getColor(context, R.color.workout_list_background)
        )

        holder.itemView.setOnClickListener {
            onItemSelected(holder.adapterPosition)
        }
    }

    override fun getItemCount() = items.size
}
