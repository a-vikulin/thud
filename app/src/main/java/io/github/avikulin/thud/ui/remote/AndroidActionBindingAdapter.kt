package io.github.avikulin.thud.ui.remote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.avikulin.thud.R
import io.github.avikulin.thud.domain.model.AndroidAction
import io.github.avikulin.thud.service.RemoteControlManager.AndroidActionBinding

/**
 * Adapter for the right-column Android actions list.
 * Shows ALL AndroidAction values, each with optional key assignment (no value spinner).
 */
class AndroidActionBindingAdapter(
    private val onAssignKey: (AndroidAction) -> Unit
) : RecyclerView.Adapter<AndroidActionBindingAdapter.ViewHolder>() {

    /** Map from action → current binding (null = not assigned). */
    var bindingMap: Map<AndroidAction, AndroidActionBinding> = emptyMap()
        set(value) { field = value; notifyDataSetChanged() }

    private val allActions = AndroidAction.entries.toList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvActionLabel: TextView = view.findViewById(R.id.tvActionLabel)
        val btnAssignKey: Button = view.findViewById(R.id.btnAssignKey)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_android_action_binding, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = allActions[position]
        val binding = bindingMap[action]
        val context = holder.itemView.context

        holder.tvActionLabel.text = context.getString(action.labelResId)

        val keyLabel = binding?.keyLabel
        holder.btnAssignKey.text = keyLabel ?: context.getString(R.string.remote_key_not_assigned)
        holder.btnAssignKey.setOnClickListener {
            onAssignKey(action)
        }
    }

    override fun getItemCount() = allActions.size
}
