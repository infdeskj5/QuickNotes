package com.infdesk5.quicknotes.helpers

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

object BottomDialogHelper {

    fun show(
        context: Context,
        title: String,
        view: View,
        positiveText: String? = null,
        onPositive: (() -> Unit)? = null
    ) {
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Cancel", null)

        if (positiveText != null && onPositive != null) {
            builder.setPositiveButton(positiveText) { _, _ -> onPositive() }
        }

        val dialog = builder.create()
        dialog.show()
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun showSimple(
        context: Context,
        title: String,
        items: Array<String>,
        onItemClick: (Int) -> Unit
    ) {
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which -> onItemClick(which) }
            .setNegativeButton("Cancel", null)

        val dialog = builder.create()
        dialog.show()
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun wrapInPadding(context: Context, view: View): View {
        val dp = { value: Int -> (value * context.resources.displayMetrics.density).toInt() }
        return LinearLayout(context).apply {
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }
}
