package com.infdesk5.quicknotes.helpers

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.infdesk5.quicknotes.R
import com.infdesk5.quicknotes.storage.NoteManager
import com.infdesk5.quicknotes.storage.StorageMode
import kotlinx.coroutines.launch

class SettingsHelper(
    private val activity: ComponentActivity,
    private val noteManager: NoteManager,
    private val applyTopInset: () -> Unit,
    private val applyScrollerSize: () -> Unit,
    private val applyScrollerVisibility: () -> Unit,
    private val applyAppColor: () -> Unit,
    private val updateSlotBar: () -> Unit,
    private val toggleStorageMode: () -> Unit,
    private val syncNotes: () -> Unit,
    private val exportBackup: () -> Unit,
    private val launchImportPicker: () -> Unit,
    private val launchFolderPicker: () -> Unit,
    private val toast: (String) -> Unit
) {
    companion object {
        private const val MAX_TOP_INSET_PERCENT = 90
    }

    fun show() {
        val items = arrayOf(
            activity.getString(R.string.top_height),
            activity.getString(R.string.scroller_size),
            activity.getString(R.string.show_scroller),
            activity.getString(R.string.max_slots),
            activity.getString(R.string.app_color),
            activity.getString(R.string.search_highlight_color),
            activity.getString(R.string.current_search_color),
            activity.getString(R.string.keyboard_on_select),
            activity.getString(R.string.show_keyboard_on_open),
            activity.getString(R.string.storage_mode,
                if (noteManager.storageMode == StorageMode.LOCAL)
                    activity.getString(R.string.storage_local)
                else
                    activity.getString(R.string.storage_external)),
            activity.getString(R.string.sync_notes),
            activity.getString(R.string.import_backup),
            activity.getString(R.string.export_backup),
            activity.getString(R.string.choose_folder)
        )

        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }

        val scrollView = ScrollView(activity)
        val listView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val outValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        items.forEachIndexed { index, title ->
            val itemView = TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener { handleClick(index) }
            }
            listView.addView(itemView)
        }

        scrollView.addView(listView)

        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.settings))
            .setView(scrollView)
            .setNegativeButton(activity.getString(R.string.cancel), null)

        val dialog = builder.create()
        dialog.show()
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.40).toInt()
        scrollView.post {
            if (scrollView.height > maxHeight) {
                scrollView.layoutParams = scrollView.layoutParams.apply { height = maxHeight }
                scrollView.requestLayout()
            }
        }
    }

    private fun handleClick(index: Int) {
        when (index) {
            0 -> showTopHeightDialog()
            1 -> showScrollerSizeDialog()
            2 -> {
                noteManager.showScroller = !noteManager.showScroller
                applyScrollerVisibility()
            }
            3 -> showSlotCountDialog()
            4 -> showColorPicker()
            5 -> showSearchColorPicker(false)
            6 -> showSearchColorPicker(true)
            7 -> {
                noteManager.keyboardOnSelect = !noteManager.keyboardOnSelect
                toast(if (noteManager.keyboardOnSelect) "Enabled" else "Disabled")
            }
            8 -> {
                noteManager.showKeyboardOnOpenNote = !noteManager.showKeyboardOnOpenNote
                toast(if (noteManager.showKeyboardOnOpenNote) "Enabled" else "Disabled")
            }
            9 -> toggleStorageMode()
            10 -> syncNotes()
            11 -> launchImportPicker()
            12 -> exportBackup()
            13 -> launchFolderPicker()
        }
    }

    private fun showTopHeightDialog() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_top_height, null)
        val valueText = view.findViewById<TextView>(R.id.top_height_value)
        val seekBar = view.findViewById<SeekBar>(R.id.top_height_seek)
        seekBar.max = MAX_TOP_INSET_PERCENT
        seekBar.progress = noteManager.topInsetPercent.coerceIn(0, MAX_TOP_INSET_PERCENT)
        valueText.text = activity.getString(R.string.top_height_value, seekBar.progress)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valueText.text = activity.getString(R.string.top_height_value, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        BottomDialogHelper.show(activity, activity.getString(R.string.top_height), view, activity.getString(R.string.save)) {
            noteManager.topInsetPercent = seekBar.progress
            applyTopInset()
        }
    }

    private fun showScrollerSizeDialog() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_top_height, null)
        val valueText = view.findViewById<TextView>(R.id.top_height_value)
        val seekBar = view.findViewById<SeekBar>(R.id.top_height_seek)
        seekBar.max = 150
        seekBar.progress = noteManager.scrollerSizePercent - 50
        valueText.text = activity.getString(R.string.scroller_size_value, noteManager.scrollerSizePercent)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valueText.text = activity.getString(R.string.scroller_size_value, p + 50)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        BottomDialogHelper.show(activity, activity.getString(R.string.scroller_size), view, activity.getString(R.string.save)) {
            noteManager.scrollerSizePercent = seekBar.progress + 50
            applyScrollerSize()
        }
    }

    private fun showSlotCountDialog() {
        val counts = arrayOf("1", "2", "3", "4", "5", "6", "7", "8")
        BottomDialogHelper.showSimple(activity, activity.getString(R.string.max_slots), counts) { which ->
            noteManager.metadata = noteManager.metadata.copy(slotCount = which + 1)
            noteManager.saveMetadata()
            updateSlotBar()
        }
    }

    private fun showColorPicker() {
        val input = EditText(activity)
        input.hint = "#RRGGBB"
        input.setText(String.format("#%06X", 0xFFFFFF and noteManager.appColor))
        input.setSelection(input.text.length)

        BottomDialogHelper.show(
            activity, activity.getString(R.string.app_color),
            BottomDialogHelper.wrapInPadding(activity, input),
            activity.getString(R.string.save)
        ) {
            try {
                noteManager.appColor = Color.parseColor(input.text.toString().trim())
                applyAppColor()
                updateSlotBar()
            } catch (e: Exception) {
                toast("Invalid color format")
            }
        }
    }

    private fun showSearchColorPicker(isCurrent: Boolean) {
        val input = EditText(activity)
        input.hint = "#AARRGGBB or #RRGGBB"
        val currentColor = if (isCurrent) noteManager.searchCurrentHighlightColor else noteManager.searchHighlightColor
        input.setText(String.format("#%08X", currentColor))
        input.setSelection(input.text.length)

        val title = activity.getString(
            if (isCurrent) R.string.current_search_color else R.string.search_highlight_color
        )

        BottomDialogHelper.show(
            activity, title,
            BottomDialogHelper.wrapInPadding(activity, input),
            activity.getString(R.string.save)
        ) {
            try {
                val color = Color.parseColor(input.text.toString().trim())
                if (isCurrent) noteManager.searchCurrentHighlightColor = color
                else noteManager.searchHighlightColor = color
            } catch (e: Exception) {
                toast("Invalid color format")
            }
        }
    }
}
