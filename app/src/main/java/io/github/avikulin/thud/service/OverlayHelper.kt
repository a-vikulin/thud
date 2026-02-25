package io.github.avikulin.thud.service

import android.content.Context
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.avikulin.thud.R

/**
 * Helper utilities for creating overlay windows and dialog containers.
 * Centralizes common patterns to reduce duplication across managers.
 */
object OverlayHelper {

    /**
     * Create WindowManager.LayoutParams for an overlay.
     *
     * @param width Width in pixels, or WRAP_CONTENT/MATCH_PARENT
     * @param height Height in pixels, or WRAP_CONTENT/MATCH_PARENT
     * @param gravity Gravity for positioning (default: CENTER)
     * @param focusable Whether the overlay should receive focus (default: false)
     * @param touchModal Whether touches outside should be blocked (default: false for NOT_TOUCH_MODAL)
     * @param x X position offset (default: 0)
     * @param y Y position offset (default: 0)
     * @return Configured WindowManager.LayoutParams
     */
    fun createOverlayParams(
        width: Int,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        gravity: Int = Gravity.CENTER,
        focusable: Boolean = false,
        touchModal: Boolean = false,
        x: Int = 0,
        y: Int = 0
    ): WindowManager.LayoutParams {
        var flags = 0
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (!touchModal) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }

    /**
     * Create a styled dialog container with standard padding and background.
     *
     * @param context Context for creating views and accessing resources
     * @return Styled LinearLayout container
     */
    fun createDialogContainer(context: Context): LinearLayout {
        val resources = context.resources
        val dialogPadding = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        val bgColor = ContextCompat.getColor(context, R.color.popup_background)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dialogPadding, dialogPadding, dialogPadding, dialogPadding)
        }
    }

    /**
     * Create a styled title TextView for dialogs.
     *
     * @param context Context for creating views and accessing resources
     * @param text Title text
     * @return Styled TextView
     */
    fun createDialogTitle(context: Context, text: String): TextView {
        val resources = context.resources
        val textColor = ContextCompat.getColor(context, R.color.text_primary)
        val sectionSpacing = resources.getDimensionPixelSize(R.dimen.dialog_section_spacing)

        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.dialog_title_text_size))
            setPadding(0, 0, 0, sectionSpacing)
        }
    }

    /**
     * Create a styled message TextView for dialogs.
     *
     * @param context Context for creating views and accessing resources
     * @param text Message text
     * @return Styled TextView
     */
    fun createDialogMessage(context: Context, text: String): TextView {
        val resources = context.resources
        val textColor = ContextCompat.getColor(context, R.color.text_primary)
        val sectionSpacing = resources.getDimensionPixelSize(R.dimen.dialog_section_spacing)

        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.dialog_item_text_size))
            setPadding(0, 0, 0, sectionSpacing)
        }
    }

    /**
     * Create a horizontal button row container for dialogs.
     *
     * @param context Context for creating views
     * @return LinearLayout configured for button row
     */
    fun createDialogButtonRow(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
    }

    /**
     * Create a styled button with consistent dark theme appearance (white text, tinted
     * background with Material rounding). Callers set their own layoutParams.
     *
     * @param context Context for creating views and accessing resources
     * @param text Button label
     * @param colorResId Background tint color resource (default: button_secondary)
     * @param onClick Click handler
     * @return Styled Button
     */
    fun createStyledButton(
        context: Context,
        text: String,
        colorResId: Int = R.color.button_secondary,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            backgroundTintList = ContextCompat.getColorStateList(context, colorResId)
            setOnClickListener { onClick() }
        }
    }

    /**
     * Calculate overlay width from screen width and fraction.
     *
     * @param screenWidth Screen width in pixels
     * @param fraction Fraction of screen width (0.0 to 1.0)
     * @return Width in pixels
     */
    fun calculateWidth(screenWidth: Int, fraction: Float): Int {
        return (screenWidth * fraction).toInt()
    }

    /**
     * Calculate overlay height from screen height and fraction.
     *
     * @param screenHeight Screen height in pixels
     * @param fraction Fraction of screen height (0.0 to 1.0)
     * @return Height in pixels
     */
    fun calculateHeight(screenHeight: Int, fraction: Float): Int {
        return (screenHeight * fraction).toInt()
    }
}
