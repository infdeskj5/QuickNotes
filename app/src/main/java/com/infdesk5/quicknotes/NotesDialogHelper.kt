package com.infdesk5.quicknotes

import android.app.AlertDialog
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesDialogHelper(
    private val activity: ComponentActivity,
    private val storage: NoteStorage,
    private val openNote: (Uri) -> Unit,
    private val saveCurrentNote: suspend () -> Unit,
    private val onNoteRenamed: (oldUri: Uri, newUri: Uri) -> Unit,
    private val toast: (String) -> Unit
) {

    private var dialog: AlertDialog? = null
    private var adapter: NotesAdapter? = null
    private var listView: MaxHeightListView? = null

    private var isLoading = false
    private val cachedNotes = mutableListOf<DocumentFile>()

    fun show() {
        val tree = storage.getTree() ?: return

        showNotesDialog()
        refresh(tree)
    }

    fun clearCache() {
        cachedNotes.clear()
        adapter?.notifyDataSetChanged()
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun showNotesDialog() {
        if (dialog?.isShowing == true) {
            return
        }

        val listView = MaxHeightListView(activity)

        val density = activity.resources.displayMetrics.density
        listView.maxHeight = (56 * density * 3).toInt()
        listView.itemsCanFocus = false
        listView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val adapter = NotesAdapter(
            notes = cachedNotes,
            onOpen = { doc ->
                this.dialog?.dismiss()
                openNote(doc.uri)
            },
            onRename = { doc, listAdapter ->
                renameNote(doc, listAdapter)
            }
        )

        listView.adapter = adapter

        this.listView = listView
        this.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.notes))
            .setView(listView)
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .create()

        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.setOnDismissListener {
            this.dialog = null
            this.adapter = null
            this.listView = null
        }

        this.dialog = dialog
        dialog.show()

        listView.post {
            limitListHeightToThreeRows(listView)
        }
    }

    private fun refresh(tree: DocumentFile) {
        if (isLoading) {
            return
        }

        isLoading = true

        activity.lifecycleScope.launch {
            val docs = storage.listNotes(tree)

            cachedNotes.clear()
            cachedNotes.addAll(docs)

            adapter?.notifyDataSetChanged()

            listView?.let {
                limitListHeightToThreeRows(it)
            }

            isLoading = false

            if (docs.isEmpty()) {
                toast(activity.getString(R.string.no_notes_found))
            }
        }
    }

    private fun limitListHeightToThreeRows(listView: MaxHeightListView) {
        val adapter = listView.adapter ?: return
        val count = minOf(3, adapter.count)

        if (count == 0) {
            return
        }

        val width = activity.resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)

        var totalHeight = 0

        for (i in 0 until count) {
            val item = adapter.getView(i, null, listView)
            item.measure(widthSpec, View.MeasureSpec.UNSPECED)

            totalHeight += item.measuredHeight

            if (i < count - 1) {
                totalHeight += listView.dividerHeight
            }
        }

        if (totalHeight > 0) {
            listView.maxHeight = totalHeight
            listView.requestLayout()
        }
    }

    private fun renameNote(doc: DocumentFile, adapter: BaseAdapter) {
        val input = EditText(activity)

        val currentName = doc.name ?: ""
        val nameWithoutExtension = currentName.substringBeforeLast('.')

        input.setText(nameWithoutExtension)
        input.setSelection(input.text.length)

        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.rename_note))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.rename)) { _, _ ->
                val raw = input.text.toString().trim()

                if (raw.isEmpty()) {
                    toast(activity.getString(R.string.name_cannot_be_empty))
                    return@setPositiveButton
                }

                val newName = NoteUtils.buildNewName(raw, currentName)

                activity.lifecycleScope.launch {
                    saveCurrentNote()

                    val oldUri = doc.uri

                    val renamed = withContext(Dispatchers.IO) {
                        try {
                            doc.renameTo(newName)
                        } catch (e: Exception) {
                            false
                        }
                    }

                    if (renamed) {
                        onNoteRenamed(oldUri, doc.uri)
                        adapter.notifyDataSetChanged()
                        toast(activity.getString(R.string.renamed))
                    } else {
                        toast(activity.getString(R.string.rename_failed))
                    }
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private inner class NotesAdapter(
        private val notes: List<DocumentFile>,
        private val onOpen: (DocumentFile) -> Unit,
        private val onRename: (DocumentFile, BaseAdapter) -> Unit
    ) : BaseAdapter() {

        override fun getCount(): Int = notes.size

        override fun getItem(position: Int): Any = notes[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: activity.layoutInflater.inflate(
                R.layout.note_list_item,
                parent,
                false
            )

            val nameView = view.findViewById<TextView>(R.id.note_name)
            val renameButton = view.findViewById<ImageButton>(R.id.rename_button)

            val document = notes[position]

            nameView.text = document.name ?: activity.getString(R.string.note)

            view.setOnClickListener {
                onOpen(document)
            }

            renameButton.setOnClickListener {
                onRename(document, this@NotesAdapter)
            }

            return view
        }
    }
}
