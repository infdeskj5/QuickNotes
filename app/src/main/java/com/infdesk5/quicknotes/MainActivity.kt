package com.infdesk5.quicknotes

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_LAST_NOTE_URI = "last_note_uri"
        private const val KEY_CURRENT_NOTE_URI = "current_note_uri"
        private const val KEY_EDIT_TEXT = "edit_text"
        private const val KEY_LAST_SAVED_TEXT = "last_saved_text"

        private const val DEFAULT_NOTE_NAME = "quicknote.txt"
        private const val MIME_TEXT = "text/plain"
        private const val AUTOSAVE_DELAY_MS = 700L

        private const val MAX_UNDO_STEPS = 80
        private const val UNDO_GROUP_MS = 500L

        private const val TOP_INSET_RATIO = 0.45f
        private const val SCROLL_TO_END_TIMEOUT_MS = 800L
    }

    private lateinit var rootLayout: View
    private lateinit var editText: EditText
    private lateinit var noteScroll: ObservableScrollView
    private lateinit var fastScroller: View
    private lateinit var scrollThumb: View

    private var currentNoteUri: Uri? = null
    private var saveJob: Job? = null
    private var isLoading = false
    private var isProgrammaticTextChange = false
    private var lastSavedText = ""

    private var isTextSelectionActionMode = false

    private var scrollToEndUntil = 0L
    private var scrollToEndWhenKeyboardVisible = false

    private var lastHistoryPushTime = 0L
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scrollToEndIfRequested()
        updateFastScroller(noteScroll.scrollY, noteScroll.getMaxScroll())
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (!isProgrammaticTextChange && !isLoading) {
                if (redoStack.isNotEmpty()) {
                    redoStack.clear()
                    invalidateOptionsMenu()
                }

                pushUndoSnapshot(s?.toString() ?: "", count, after)
            }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            // No action needed.
        }

        override fun afterTextChanged(s: Editable?) {
            if (!isProgrammaticTextChange && !isLoading) {
                scheduleSave()
            }
        }
    }

    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                toast(getString(R.string.folder_needed))
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                toast("Permission was not persisted. Choose the folder again.")
                return@registerForActivityResult
            }

            val oldTreeUri = getTreeUri()?.toString()

            prefs.edit()
                .putString(KEY_TREE_URI, uri.toString())
                .apply()

            if (oldTreeUri != uri.toString()) {
                prefs.edit()
                    .remove(KEY_LAST_NOTE_URI)
                    .apply()
            }

            if (currentNoteUri == null && editText.text.toString().isNotEmpty()) {
                createNewNoteWithCurrentText()
            } else {
                openLastOrCreateNote()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setActionBar(findViewById<Toolbar>(R.id.toolbar))
        actionBar?.setDisplayShowTitleEnabled(false)

        rootLayout = findViewById(R.id.root_layout)
        noteScroll = findViewById(R.id.note_scroll)
        fastScroller = findViewById(R.id.fast_scroller)
        scrollThumb = findViewById(R.id.scroll_thumb)
        editText = findViewById(R.id.note_edit)

        noteScroll.setSmoothScrollingEnabled(false)
        editText.setShowSoftInputOnFocus(false)

        setupEditorTopPadding()
        setupFastScroller()
        setupClickToFocus()

        noteScroll.onScrollChangedListener = { scrollY, maxScroll ->
            updateFastScroller(scrollY, maxScroll)
        }

        rootLayout.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime()) && scrollToEndWhenKeyboardVisible) {
                noteScroll.post {
                    scrollToEnd()
                }
                scrollToEndWhenKeyboardVisible = false
            }

            ViewCompat.onApplyWindowInsets(view, insets)
        }

        editText.addTextChangedListener(textWatcher)

        if (savedInstanceState != null) {
            currentNoteUri = savedInstanceState.getString(KEY_CURRENT_NOTE_URI)?.let(Uri::parse)

            val text = savedInstanceState.getString(KEY_EDIT_TEXT) ?: ""
            lastSavedText = savedInstanceState.getString(KEY_LAST_SAVED_TEXT) ?: text

            setTextWithoutWatcher(text)
            clearHistory()
            setCursorEndAndShowKeyboard()

            if (!hasTreePermission()) {
                pickFolder(false)
            } else if (currentNoteUri == null) {
                if (text.isNotEmpty()) {
                    createNewNoteWithCurrentText()
                } else {
                    openLastOrCreateNote()
                }
            } else if (text != lastSavedText) {
                scheduleSave()
            }
        } else {
            if (hasTreePermission()) {
                openLastOrCreateNote()
            } else {
                pickFolder(true)
            }
        }

        fastScroller.post {
            updateFastScroller(noteScroll.scrollY, noteScroll.getMaxScroll())
        }
    }

    override fun onDestroy() {
        rootLayout.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_NOTE_URI, currentNoteUri?.toString())
        outState.putString(KEY_EDIT_TEXT, editText.text.toString())
        outState.putString(KEY_LAST_SAVED_TEXT, lastSavedText)
    }

    override fun onPause() {
        saveJob?.cancel()
        saveCurrentNoteBlocking()
        super.onPause()
    }

    override fun onStop() {
        saveCurrentNoteBlocking()
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            scrollToEndUntil = 0L
            scrollToEndWhenKeyboardVisible = false
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onActionModeStarted(mode: ActionMode?) {
        super.onActionModeStarted(mode)
        isTextSelectionActionMode = true
        hideKeyboard()
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        super.onActionModeFinished(mode)
        isTextSelectionActionMode = false
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        menu.findItem(R.id.action_undo)?.isEnabled = undoStack.isNotEmpty()
        menu.findItem(R.id.action_redo)?.isEnabled = redoStack.isNotEmpty()

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new -> {
                createNewNote()
                true
            }

            R.id.action_undo -> {
                undo()
                true
            }

            R.id.action_redo -> {
                redo()
                true
            }

            R.id.action_notes -> {
                showNotes()
                true
            }

            R.id.action_save -> {
                lifecycleScope.launch {
                    saveCurrentNoteNow()
                    toast(getString(R.string.saved))
                }
                true
            }

            R.id.action_folder -> {
                lifecycleScope.launch {
                    saveCurrentNoteNow()
                    pickFolder(false)
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupEditorTopPadding() {
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        val topInset = (resources.displayMetrics.heightPixels * TOP_INSET_RATIO).toInt()

        editText.setPadding(
            basePadding,
            topInset,
            basePadding,
            basePadding
        )

        val layoutParams = fastScroller.layoutParams as? FrameLayout.LayoutParams
        if (layoutParams != null) {
            layoutParams.topMargin = topInset
            fastScroller.layoutParams = layoutParams
        }
    }

    private fun setupClickToFocus() {
        val noteContent = findViewById<View>(R.id.note_content)

        noteContent.setOnClickListener {
            if (!isTextSelectionActionMode) {
                editText.requestFocus()

                val length = editText.text.length
                try {
                    editText.setSelection(length)
                } catch (_: Exception) {
                    // Ignore cursor positioning errors.
                }

                showKeyboard()
            }
        }

        editText.setOnClickListener {
            if (!isTextSelectionActionMode) {
                showKeyboard()
            }
        }
    }

    private fun setupFastScroller() {
        fastScroller.setOnTouchListener { _, event ->
            val maxScroll = noteScroll.getMaxScroll()

            if (maxScroll <= 0) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    val thumbHeight = scrollThumb.height
                    val trackHeight = fastScroller.height - thumbHeight

                    if (trackHeight <= 0) {
                        return@setOnTouchListener false
                    }

                    val y = (event.y - thumbHeight / 2f)
                        .coerceIn(0f, trackHeight.toFloat())

                    val fraction = y / trackHeight
                    val targetScroll = (fraction * maxScroll).toInt()

                    noteScroll.scrollTo(0, targetScroll)
                    true
                }

                else -> false
            }
        }
    }

    private fun updateFastScroller(scrollY: Int, maxScroll: Int) {
        if (maxScroll <= 0) {
            fastScroller.visibility = View.INVISIBLE
            return
        }

        fastScroller.visibility = View.VISIBLE

        val thumbHeight = scrollThumb.height
        val trackHeight = fastScroller.height - thumbHeight

        if (trackHeight <= 0) {
            fastScroller.visibility = View.INVISIBLE
            return
        }

        val fraction = scrollY.toFloat() / maxScroll.toFloat()
        val thumbY = (fraction * trackHeight).coerceIn(0f, trackHeight.toFloat())

        scrollThumb.y = thumbY
    }

    private fun requestScrollToEnd() {
        scrollToEndUntil = System.currentTimeMillis() + SCROLL_TO_END_TIMEOUT_MS
        scrollToEndWhenKeyboardVisible = true
        scrollToEndIfRequested()
    }

    private fun scrollToEndIfRequested() {
        if (System.currentTimeMillis() < scrollToEndUntil) {
            scrollToEnd()
        }
    }

    private fun scrollToEnd() {
        val maxScroll = noteScroll.getMaxScroll()
        if (maxScroll > 0) {
            noteScroll.scrollTo(0, maxScroll)
        }
    }

    private fun hasTreePermission(): Boolean {
        val treeUri = getTreeUri() ?: return false

        return contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
    }

    private fun getTreeUri(): Uri? {
        return prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
    }

    private fun getTree(): DocumentFile? {
        return getTreeUri()?.let { DocumentFile.fromTreeUri(this, it) }
    }

    private fun saveLastNoteUri(uri: Uri) {
        prefs.edit()
            .putString(KEY_LAST_NOTE_URI, uri.toString())
            .apply()
    }

    private fun pickFolder(showMessage: Boolean) {
        if (showMessage) {
            toast(getString(R.string.folder_needed))
        }

        pickFolderLauncher.launch(null)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            saveCurrentNoteNow()
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        lifecycleScope.launch {
            saveCurrentNoteNow()
        }
    }

    private suspend fun saveCurrentNoteNow() {
        val uri = currentNoteUri ?: return

        val text = withContext(Dispatchers.Main) {
            editText.text.toString()
        }

        if (text == lastSavedText) {
            return
        }

        val success = withContext(Dispatchers.IO) {
            writeText(uri, text)
        }

        if (success) {
            lastSavedText = text
        } else {
            withContext(Dispatchers.Main) {
                toast(getString(R.string.save_failed_choose_folder))
            }
        }
    }

    private fun saveCurrentNoteBlocking() {
        val uri = currentNoteUri ?: return
        val text = editText.text.toString()

        if (text == lastSavedText) {
            return
        }

        val success = runBlocking(Dispatchers.IO) {
            writeText(uri, text)
        }

        if (success) {
            lastSavedText = text
        }
    }

    private fun openLastOrCreateNote() {
        lifecycleScope.launch {
            if (!hasTreePermission()) {
                pickFolder(true)
                return@launch
            }

            val tree = getTree() ?: return@launch

            val lastUri = prefs.getString(KEY_LAST_NOTE_URI, null)?.let(Uri::parse)

            if (lastUri != null) {
                val exists = withContext(Dispatchers.IO) {
                    DocumentFile.fromSingleUri(this@MainActivity, lastUri)?.exists() == true
                }

                if (exists && openNoteInternal(lastUri, saveCurrentFirst = false)) {
                    return@launch
                }
            }

            val quickNote = withContext(Dispatchers.IO) {
                tree.findFile(DEFAULT_NOTE_NAME)
            }

            if (quickNote != null && quickNote.isFile) {
                if (openNoteInternal(quickNote.uri, saveCurrentFirst = false)) {
                    return@launch
                }
            }

            val created = createFileInTree(tree, DEFAULT_NOTE_NAME)

            if (created == null) {
                toast(getString(R.string.could_not_create_note))
                return@launch
            }

            currentNoteUri = created.uri
            saveLastNoteUri(created.uri)

            setTextWithoutWatcher("")
            clearHistory()
            lastSavedText = ""

            setCursorEndAndShowKeyboard()
        }
    }

    private suspend fun openNoteInternal(uri: Uri, saveCurrentFirst: Boolean = true): Boolean {
        if (saveCurrentFirst) {
            saveCurrentNoteNow()
        }

        val text = withContext(Dispatchers.IO) {
            readText(uri)
        }

        if (text == null) {
            return false
        }

        currentNoteUri = uri
        saveLastNoteUri(uri)

        setTextWithoutWatcher(text)
        clearHistory()

        lastSavedText = text

        setCursorEndAndShowKeyboard()

        return true
    }

    private fun openNote(uri: Uri) {
        lifecycleScope.launch {
            if (!openNoteInternal(uri)) {
                toast(getString(R.string.could_not_open_note))
            }
        }
    }

    private fun createNewNote() {
        lifecycleScope.launch {
            saveCurrentNoteNow()

            if (!hasTreePermission()) {
                pickFolder(true)
                return@launch
            }

            val tree = getTree() ?: return@launch

            val doc = createFileInTree(tree, newNoteName())

            if (doc == null) {
                toast(getString(R.string.could_not_create_note))
                return@launch
            }

            currentNoteUri = doc.uri
            saveLastNoteUri(doc.uri)

            setTextWithoutWatcher("")
            clearHistory()

            lastSavedText = ""

            setCursorEndAndShowKeyboard()
        }
    }

    private fun createNewNoteWithCurrentText() {
        lifecycleScope.launch {
            if (!hasTreePermission()) {
                pickFolder(true)
                return@launch
            }

            val tree = getTree() ?: return@launch

            val doc = createFileInTree(tree, newNoteName())

            if (doc == null) {
                toast(getString(R.string.could_not_create_note))
                return@launch
            }

            currentNoteUri = doc.uri
            saveLastNoteUri(doc.uri)
            clearHistory()

            val text = editText.text.toString()

            val success = withContext(Dispatchers.IO) {
                writeText(doc.uri, text)
            }

            if (success) {
                lastSavedText = text
            } else {
                toast(getString(R.string.save_failed_choose_folder))
            }

            setCursorEndAndShowKeyboard()
        }
    }

    private fun showNotes() {
        lifecycleScope.launch {
            saveCurrentNoteNow()

            if (!hasTreePermission()) {
                pickFolder(true)
                return@launch
            }

            val tree = getTree() ?: return@launch

            val docs = withContext(Dispatchers.IO) {
                try {
                    tree.listFiles()
                        .filter { it.isFile && isTextFile(it) }
                        .sortedWith(
                            compareByDescending<DocumentFile> { it.lastModified() }
                                .thenByDescending { it.name?.lowercase(Locale.US) ?: "" }
                        )
                        .toMutableList()
                } catch (e: Exception) {
                    mutableListOf<DocumentFile>()
                }
            }

            if (docs.isEmpty()) {
                toast(getString(R.string.no_notes_found))
                return@launch
            }

            val listView = ListView(this@MainActivity)
            listView.itemsCanFocus = false
            listView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            var dialog: AlertDialog? = null

            val adapter = NotesAdapter(
                notes = docs,
                onOpen = { doc ->
                    dialog?.dismiss()
                    openNote(doc.uri)
                },
                onRename = { doc, listAdapter ->
                    renameNote(doc, listAdapter)
                }
            )

            listView.adapter = adapter

            val builder = AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.notes))
                .setView(listView)
                .setNegativeButton(getString(R.string.cancel), null)

            dialog = builder.create()
            dialog.window?.setGravity(Gravity.BOTTOM)
            dialog.show()
        }
    }

    private fun renameNote(doc: DocumentFile, adapter: BaseAdapter) {
        val input = EditText(this)

        val currentName = doc.name ?: ""
        val nameWithoutExtension = currentName.substringBeforeLast('.')

        input.setText(nameWithoutExtension)
        input.setSelection(input.text.length)

        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_note))
            .setView(input)
            .setPositiveButton(getString(R.string.rename)) { _, _ ->
                val raw = input.text.toString().trim()

                if (raw.isEmpty()) {
                    toast(getString(R.string.name_cannot_be_empty))
                    return@setPositiveButton
                }

                val newName = buildNewName(raw, currentName)

                lifecycleScope.launch {
                    saveCurrentNoteNow()

                    val oldUri = doc.uri

                    val renamed = withContext(Dispatchers.IO) {
                        try {
                            doc.renameTo(newName)
                        } catch (e: Exception) {
                            false
                        }
                    }

                    if (renamed) {
                        if (currentNoteUri == oldUri) {
                            currentNoteUri = doc.uri
                            saveLastNoteUri(doc.uri)
                        }

                        val lastUriString = prefs.getString(KEY_LAST_NOTE_URI, null)
                        if (lastUriString == oldUri.toString()) {
                            saveLastNoteUri(doc.uri)
                        }

                        adapter.notifyDataSetChanged()
                        toast(getString(R.string.renamed))
                    } else {
                        toast(getString(R.string.rename_failed))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private suspend fun createFileInTree(tree: DocumentFile, name: String): DocumentFile? {
        return withContext(Dispatchers.IO) {
            try {
                tree.createFile(MIME_TEXT, name)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun isTextFile(file: DocumentFile): Boolean {
        val name = file.name?.lowercase(Locale.US) ?: return false

        return name.endsWith(".txt") ||
                name.endsWith(".md") ||
                file.type?.startsWith("text/") == true
    }

    private fun newNoteName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "note-$stamp.txt"
    }

    private fun buildNewName(raw: String, originalName: String): String {
        var name = raw.trim().replace("/", "-")

        if (name.isEmpty()) {
            return name
        }

        if (name.contains('.')) {
            return name
        }

        val extension = originalName.substringAfterLast('.', "")

        return if (extension.isNotEmpty()) {
            "$name.$extension"
        } else {
            "$name.txt"
        }
    }

    private fun readText(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeText(uri: Uri, text: String): Boolean {
        return try {
            val outputStream = try {
                contentResolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                contentResolver.openOutputStream(uri)
            }

            outputStream?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: return false

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun pushUndoSnapshot(oldText: String, count: Int, after: Int) {
        val now = System.currentTimeMillis()
        val isLargeChange = count > 1 || after > 1
        val last = undoStack.lastOrNull()

        if (last == oldText && !isLargeChange) {
            return
        }

        if (undoStack.isEmpty() || isLargeChange || now - lastHistoryPushTime > UNDO_GROUP_MS) {
            addToUndoStack(oldText)
            lastHistoryPushTime = now
        }
    }

    private fun addToUndoStack(text: String) {
        if (undoStack.isNotEmpty() && undoStack.last() == text) {
            return
        }

        undoStack.addLast(text)

        while (undoStack.size > MAX_UNDO_STEPS) {
            undoStack.removeFirst()
        }

        invalidateOptionsMenu()
    }

    private fun addToRedoStack(text: String) {
        if (redoStack.isNotEmpty() && redoStack.last() == text) {
            return
        }

        redoStack.addLast(text)

        while (redoStack.size > MAX_UNDO_STEPS) {
            redoStack.removeFirst()
        }

        invalidateOptionsMenu()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        lastHistoryPushTime = 0L
        invalidateOptionsMenu()
    }

    private fun undo() {
        if (undoStack.isEmpty()) {
            return
        }

        val current = editText.text.toString()
        val previous = undoStack.removeLast()

        addToRedoStack(current)

        val selection = editText.selectionStart.coerceAtLeast(0)

        setTextWithoutWatcher(previous)

        val safeSelection = selection.coerceIn(0, previous.length)
        editText.setSelection(safeSelection)
        editText.bringPointIntoView(safeSelection)

        lastHistoryPushTime = 0L

        showKeyboard()
        scheduleSave()
        invalidateOptionsMenu()
    }

    private fun redo() {
        if (redoStack.isEmpty()) {
            return
        }

        val current = editText.text.toString()
        val next = redoStack.removeLast()

        addToUndoStack(current)

        val selection = editText.selectionStart.coerceAtLeast(0)

        setTextWithoutWatcher(next)

        val safeSelection = selection.coerceIn(0, next.length)
        editText.setSelection(safeSelection)
        editText.bringPointIntoView(safeSelection)

        lastHistoryPushTime = 0L

        showKeyboard()
        scheduleSave()
        invalidateOptionsMenu()
    }

    private fun setTextWithoutWatcher(text: String) {
        isLoading = true
        isProgrammaticTextChange = true

        editText.removeTextChangedListener(textWatcher)
        editText.setText(text)
        editText.addTextChangedListener(textWatcher)

        isLoading = false
        isProgrammaticTextChange = false
    }

    private fun setCursorEndAndShowKeyboard() {
        editText.post {
            val length = editText.text.length

            try {
                editText.setSelection(length)
            } catch (_: Exception) {
                // Ignore cursor positioning errors.
            }

            if (length == 0) {
                noteScroll.scrollTo(0, 0)
            } else {
                requestScrollToEnd()
            }

            editText.requestFocus()
            showKeyboard()
        }
    }

    private fun showKeyboard() {
        if (isTextSelectionActionMode) {
            return
        }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }

        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
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
            val view = convertView ?: layoutInflater.inflate(
                R.layout.note_list_item,
                parent,
                false
            )

            val nameView = view.findViewById<TextView>(R.id.note_name)
            val renameButton = view.findViewById<ImageButton>(R.id.rename_button)

            val document = notes[position]

            nameView.text = document.name ?: getString(R.string.note)

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
