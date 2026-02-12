package io.github.avikulin.thud.ui.remote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.avikulin.thud.R
import io.github.avikulin.thud.domain.model.RemoteAction
import io.github.avikulin.thud.service.RemoteControlManager.ActionBinding
import io.github.avikulin.thud.ui.components.TouchSpinner

/**
 * Adapter for the right-pane action bindings list.
 * Shows ALL RemoteAction values, each with optional key assignment and value spinner.
 */
class ActionBindingAdapter(
    private val onAssignKey: (RemoteAction) -> Unit,
    private val onValueChanged: (RemoteAction, Double) -> Unit
) : RecyclerView.Adapter<ActionBindingAdapter.ViewHolder>() {

    /** Map from action → current binding (null = not assigned). */
    var bindingMap: Map<RemoteAction, ActionBinding> = emptyMap()
        set(value) { field = value; notifyDataSetChanged() }

    private val allActions = RemoteAction.entries.filter { it != RemoteAction.TOGGLE_MODE }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvActionLabel: TextView = view.findViewById(R.id.tvActionLabel)
        val btnAssignKey: Button = view.findViewById(R.id.btnAssignKey)
        val spinnerContainer: FrameLayout = view.findViewById(R.id.spinnerContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_action_binding, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = allActions[position]
        val binding = bindingMap[action]
        val context = holder.itemView.context

        holder.tvActionLabel.text = context.getString(action.labelResId)

        // Key assignment button
        val keyLabel = binding?.keyLabel
        holder.btnAssignKey.text = keyLabel ?: context.getString(R.string.remote_key_not_assigned)
        holder.btnAssignKey.setOnClickListener {
            onAssignKey(action)
        }

        // Value spinner (only for hasValue actions)
        holder.spinnerContainer.removeAllViews()
        if (action.hasValue) {
            holder.spinnerContainer.visibility = View.VISIBLE
            val isSpeed = action == RemoteAction.SPEED_UP || action == RemoteAction.SPEED_DOWN
            val spinner = TouchSpinner(context).apply {
                minValue = if (isSpeed) 0.1 else 0.5
                maxValue = if (isSpeed) 2.0 else 5.0
                step = if (isSpeed) 0.1 else 0.5
                format = TouchSpinner.Format.DECIMAL
                suffix = if (isSpeed) " ${context.getString(R.string.remote_unit_kph)}"
                         else " ${context.getString(R.string.remote_unit_percent)}"
                value = binding?.value ?: if (isSpeed) 0.5 else 0.5
                onValueChanged = { newValue ->
                    onValueChanged(action, newValue)
                }
            }
            holder.spinnerContainer.addView(spinner)
        } else {
            holder.spinnerContainer.visibility = View.GONE
        }
    }

    override fun getItemCount() = allActions.size
}
