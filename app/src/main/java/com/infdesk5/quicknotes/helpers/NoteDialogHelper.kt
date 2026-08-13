package com.infdesk5.quicknotes.helpers

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.infdesk5.quicknotes.R
import com.infdesk5.quicknotes.model.Note
import com.infdesk5.quicknotes.storage.NoteManager
import kotlinx.coroutines.launch

class NoteDialogHelper(
    private val activity: ComponentActivity,
    private val noteManager: NoteManager,
    private val getNotes: suspend () -> List<Note>,
    private val openNote: suspend (Note) -> Unit,
    private val saveCurrentNote: suspend () -> Unit,
    private val refreshAndOpenLast: suspend () -> Unit,
    private val updateSlotBar: () -> Unit,
    private val saveNoteOrder: () -> Unit,
    private val toast: (String) -> Unit
) {
    companion object {
        private const val EXTRA_NOTE_ID = "extra_note_id"
    }

    fun showNotesMenu() {
        activity.lifecycleScope.launch {
            saveCurrentNote()
            val notes = getNotes()
            if (notes.isEmpty()) {
                toast(activity.getString(R.string.no_notes_found))
                return@launch
            }

            val names = notes.map { it.displayName }.toTypedArray()
            val builder = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.notes))
                .setItems(names) { _, which ->
                    activity.lifecycleScope.launch { openNote(notes[which]) }
                }
                .setNeutralButton(activity.getString(R.string.search_all_notes)) { _, _ ->
                    // Handled by caller via callback
                }
                .setNegativeButton(activity.getString(R.string.cancel), null)

            val dialog = builder.create()
            dialog.show()
            dialog.window?.setGravity(Gravity.BOTTOM)
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                dialog.dismiss()
                showNoteOptions(notes[position])
                true
            }
        }
    }

    fun showNoteOptions(note: Note) {
        val options = arrayOf(
            activity.getString(R.string.assign_to_slot),
            activity.getString(R.string.delete),
            activity.getString(R.string.rename),
            activity.getString(R.string.create_shortcut)
        )

        BottomDialogHelper.showSimple(activity, note.displayName, options) { which ->
            when (which) {
                0 -> assignToSlot(note)
                1 -> deleteNote(note)
                2 -> renameNote(note)
                3 -> createShortcut(note)
            }
        }
    }

    private fun assignToSlot(note: Note) {
        val maxSlots = noteManager.metadata.slotCount
        val options = (1..maxSlots).map { "Slot $it" }.toTypedArray()

        BottomDialogHelper.showSimple(activity, activity.getString(R.string.assign_to_slot), options) { which ->
            activity.lifecycleScope.launch {
                val notes = getNotes().toMutableList()
                val currentIndex = notes.indexOfFirst { it.id == note.id }
                if (currentIndex != -1 && currentIndex != which) {
                    notes.removeAt(currentIndex)
                    notes.add(which, note)
                    // Caller handles updating allNotes and slot bar
                    updateSlotBar()
                    saveNoteOrder()
                }
            }
        }
    }

    private fun deleteNote(note: Note) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.delete))
            .setMessage(activity.getString(R.string.delete_note_confirm))
            .setPositiveButton(activity.getString(R.string.delete)) { _, _ ->
                activity.lifecycleScope.launch {
                    if (noteManager.deleteNote(note)) {
                        toast(activity.getString(R.string.note_deleted))
                        refreshAndOpenLast()
                    }
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)

        val dialog = builder.create()
        dialog.show()
        dialog.window?.setGravity(Gravity.BOTTOM)
    }

    private fun renameNote(note: Note) {
        val input = EditText(activity)
        input.setText(note.displayName)
        input.selectAll()

        BottomDialogHelper.show(
            activity,
            activity.getString(R.string.rename_note),
            BottomDialogHelper.wrapInPadding(activity, input),
            activity.getString(R.string.rename)
        ) {
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) {
                toast(activity.getString(R.string.name_cannot_be_empty))
                return@show
            }
            val fullName = if (newName.contains('.')) newName else "$newName.txt"
            activity.lifecycleScope.launch {
                noteManager.renameNote(note, fullName)
                updateSlotBar()
            }
        }

        input.postDelayed({
            input.requestFocus()
            input.selectAll()
        }, 300)
    }

    private fun createShortcut(note: Note) {
        try {
            val manager = activity.getSystemService(ShortcutManager::class.java)
            if (manager.isRequestPinShortcutSupported) {
                val intent = Intent(activity, activity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(EXTRA_NOTE_ID, note.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                }

                val size = (48 * activity.resources.displayMetrics.density).toInt()
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val bgPaint = Paint().apply { color = Color.BLACK; isAntiAlias = true }
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

                val pencil = ContextCompat.getDrawable(activity, android.R.drawable.ic_menu_edit)
                pencil?.setTint(0xFF9C27B0.toInt())
                pencil?.setBounds(size / 4, size / 4, size * 3 / 4, size * 3 / 4)
                pencil?.draw(canvas)

                val shortcut = ShortcutInfo.Builder(activity, note.id)
                    .setShortLabel(note.displayName)
                    .setIcon(android.graphics.drawable.Icon.createWithBitmap(bitmap))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)
                toast(activity.getString(R.string.shortcut_created))
            }
        } catch (e: Exception) {
            toast("Shortcut creation failed")
        }
    }
}
