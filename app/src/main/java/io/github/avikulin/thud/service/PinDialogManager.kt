package io.github.avikulin.thud.service

import android.content.Context
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R

/**
 * Reusable PIN entry overlay dialog.
 *
 * Shows a title, profile name label (read-only), PIN input field (digits-only,
 * masked), and OK/Cancel buttons. Validates PIN format (4–8 digits) before
 * invoking the onPinEntered callback — the caller is responsible for hash
 * verification and showing error toasts if the PIN is wrong.
 *
 * Used by:
 * - HUD user dropdown (Phase 3): switching to a PIN-protected profile
 * - Settings "Set Pin" / "Change Pin" / "Remove Pin" flows (Phase 3)
 */
class PinDialogManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "PinDialogManager"
    }

    private var dialogView: View? = null

    /**
     * Show a PIN entry dialog as a system overlay.
     *
     * @param profileName Display name shown as a read-only label (e.g., "Alex")
     * @param title Dialog title (e.g., "Enter PIN", "Enter current PIN")
     * @param onPinEntered Called with the entered PIN string. Caller verifies correctness.
     * @param onCancelled Called when user taps Cancel.
     */
    fun showPinDialog(
        profileName: String,
        title: String,
        onPinEntered: (pin: String) -> Unit,
        onCancelled: () -> Unit
    ) {
        dismissDialog()

        val resources = context.resources
        val sectionSpacing = resources.getDimensionPixelSize(R.dimen.dialog_section_spacing)
        val itemSpacing = resources.getDimensionPixelSize(R.dimen.dialog_item_spacing)
        val inputHeight = resources.getDimensionPixelSize(R.dimen.pin_dialog_input_height)
        val itemTextSize = resources.getDimension(R.dimen.dialog_item_text_size)
        val textColor = ContextCompat.getColor(context, R.color.text_primary)
        val secondaryColor = ContextCompat.getColor(context, R.color.text_secondary)
        val inputBgColor = ContextCompat.getColor(context, R.color.editor_input_background)
        val screenWidth = windowManager.currentWindowMetrics.bounds.width()

        // Container — nearly opaque since this is a modal blocking all interaction
        val container = OverlayHelper.createDialogContainer(context)
        container.setBackgroundColor(ContextCompat.getColor(context, R.color.pin_dialog_background))

        // Title
        container.addView(OverlayHelper.createDialogTitle(context, title))

        // User label (read-only)
        val userLabel = TextView(context).apply {
            text = context.getString(R.string.pin_dialog_user_label, profileName)
            setTextColor(secondaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            setPadding(0, 0, 0, sectionSpacing)
        }
        container.addView(userLabel)

        // PIN label
        val pinLabel = TextView(context).apply {
            text = context.getString(R.string.pin_dialog_pin_label)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            setPadding(0, 0, 0, itemSpacing)
        }
        container.addView(pinLabel)

        // PIN input field (digits-only keyboard, masked display)
        val pinInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            setBackgroundColor(inputBgColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                inputHeight
            ).apply {
                bottomMargin = sectionSpacing
            }
            val inputPadding = resources.getDimensionPixelSize(R.dimen.dialog_input_padding)
            setPadding(inputPadding, inputPadding, inputPadding, inputPadding)
        }
        container.addView(pinInput)

        // Button row
        val buttonRow = OverlayHelper.createDialogButtonRow(context)

        // Cancel button
        val cancelButton = OverlayHelper.createStyledButton(context, context.getString(R.string.btn_cancel)) {
            dismissDialog()
            onCancelled()
        }
        buttonRow.addView(cancelButton)

        // Spacer between buttons
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(itemSpacing, 1)
        }
        buttonRow.addView(spacer)

        // OK button
        val okButton = OverlayHelper.createStyledButton(context, context.getString(R.string.btn_ok)) {
            val pin = pinInput.text.toString()
            if (!ProfileManager.isValidPinFormat(pin)) {
                Toast.makeText(context, R.string.toast_pin_invalid_length, Toast.LENGTH_SHORT).show()
                pinInput.text.clear()
                return@createStyledButton
            }
            dismissDialog()
            onPinEntered(pin)
        }
        buttonRow.addView(okButton)

        container.addView(buttonRow)

        // Create overlay params — focusable so the EditText keyboard works, touchModal to block background
        val dialogWidth = OverlayHelper.calculateWidth(
            screenWidth,
            resources.getFloat(R.dimen.pin_dialog_width_fraction)
        )
        val params = OverlayHelper.createOverlayParams(
            width = dialogWidth,
            focusable = true,
            touchModal = true
        )

        try {
            windowManager.addView(container, params)
            dialogView = container

            // Auto-focus the PIN field and show keyboard
            pinInput.requestFocus()

            Log.d(TAG, "PIN dialog shown for profile: $profileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show PIN dialog: ${e.message}", e)
        }
    }

    /**
     * Dismiss the currently shown PIN dialog, if any.
     */
    fun dismissDialog() {
        dialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing PIN dialog: ${e.message}")
            }
            dialogView = null
        }
    }

    /**
     * Whether a PIN dialog is currently showing.
     */
    val isShowing: Boolean get() = dialogView != null
}
