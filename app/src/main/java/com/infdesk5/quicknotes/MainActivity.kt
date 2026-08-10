package com.infdesk5.quicknotes

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val KEY_CURRENT_NOTE_URI = "current_note_uri"
        private const val KEY_EDIT_TEXT = "edit_text"
        private const val KEY_LAST_SAVED_TEXT = "last_saved_text"

        private const val DEFAULT_NOTE_NAME = "quicknote.txt"
        private const val AUTOSAVE_DELAY_MS = 700L

        private const val SCROLL_TO_END_TIMEOUT_MS = 1200L
        private const val MAX_TOP_INSET_PERCENT = 90
    }

    private lateinit var prefs: NotePrefs
    private lateinit var storage: NoteStorage
    private lateinit var undoRedo: UndoRedoManager
    private lateinit var fastScrollController: FastScrollController
    private lateinit var notesDialogHelper: NotesDialogHelper

    private lateinit var rootLayout: View
    private lateinit var editText: EditText
    private lateinit var noteScroll: ObservableScrollView

    private var currentNoteUri: Uri? = null
    private var saveJob: Job? = null
    private var isLoading = false
    private var isProgrammaticTextChange = false
    private var lastSavedText = ""

    private var isTextSelectionActionMode = false

    private var scrollToEndUntil = 0L
    private var scrollToEndWhenKeyboardVisible = false

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scrollToEndIfRequested()
        fastScrollController.update(noteScroll.scrollY, noteScroll.getMaxScroll())
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (!isProgrammaticTextChange && !isLoading) {
                undoRedo.beforeUserTextChanged(s?.toString() ?: "", count, after)
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

            if (!storage.takePersistablePermission(uri)) {
                toast("Permission was not persisted. Choose the folder again.")
                return@registerForActivityResult
            }

            val oldTreeUri = prefs.treeUri

            prefs.treeUri = uri.toString()

            if (oldTreeUri != uri.toString()) {
                prefs.clearLastNote()
                notesDialogHelper.clearCache()
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
        editText = findViewById(R.id.note_edit)

        prefs = NotePrefs(getPreferences(MODE_PRIVATE))
        storage = NoteStorage(this, prefs)

        undoRedo = UndoRedoManager()
        undoRedo.onAvailabilityChanged = { invalidateOptionsMenu() }

        val fastScroller = findViewById<View>(R.id.fast_scroller)
        val scrollThumb = findViewById<View>(R.id.scroll_thumb)

        fastScrollController = FastScrollController(noteScroll, fastScroller, scrollThumb)
        fastScrollController.setup()
        fastScrollController.setTopMargin(defaultTopInsetPx())

        notesDialogHelper = NotesDialogHelper(
            activity = this,
            storage = storage,
            openNote = { uri -> openNote(uri) },
            saveCurrentNote = { saveCurrentNoteNow() },
            onNoteRenamed = { oldUri, newUri -> onNoteRenamed(oldUri, newUri) },
            toast = { message -> toast(message) }
        )

        noteScroll.setSmoothScrollingEnabled(false)
        editText.setShowSoftInputOnFocus(false)

        applyTopInset()
        setupClickToFocus()

        noteScroll.onScrollChangedListener = { scrollY, maxScroll ->
            fastScrollController.update(scrollY, maxScroll)
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
            undoRedo.clear()
            setCursorEndAndShowKeyboard()

            if (!storage.hasTreePermission()) {
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
            if (storage.hasTreePermission()) {
                openLastOrCreateNote()
            } else {
                pickFolder(true)
            }
        }

        fastScroller.post {
            fastScrollController.update(noteScroll.scrollY, noteScroll.getMaxScroll())
        }
    }

    override fun onDestroy() {
        notesDialogHelper.dismiss()
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

        if (!isKeyboardVisible()) {
            hideKeyboard()
        }
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

        menu.findItem(R.id.action_undo)?.isEnabled = undoRedo.canUndo
        menu.findItem(R.id.action_redo)?.isEnabled = undoRedo.canRedo

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
                if (!storage.hasTreePermission()) {
                    pickFolder(true)
                } else {
                    notesDialogHelper.show()
                }
                true
            }

            R.id.action_top_height -> {
                showTopHeightDialog()
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

    private fun applyTopInset() {
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        val percent = prefs.topInsetPercent.coerceIn(0, MAX_TOP_INSET_PERCENT)
        val topInset = (resources.displayMetrics.heightPixels * percent / 100f).toInt()

        editText.setPadding(
            basePadding,
            topInset,
            basePadding,
            basePadding
        )
    }

    private fun defaultTopInsetPx(): Int {
        return (resources.displayMetrics.heightPixels *
                NotePrefs.DEFAULT_TOP_INSET_PERCENT / 100f).toInt()
    }

    private fun showTopHeightDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_top_height, null)

        val valueText = view.findViewById<TextView>(R.id.top_height_value)
        val seekBar = view.findViewById<SeekBar>(R.id.top_height_seek)

        seekBar.max = MAX_TOP_INSET_PERCENT

        val current = prefs.topInsetPercent.coerceIn(0, MAX_TOP_INSET_PERCENT)
        seekBar.progress = current
        valueText.text = getString(R.string.top_height_value, current)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = getString(R.string.top_height_value, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // No action needed.
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // No action needed.
            }
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.top_height))
            .setView(view)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                prefs.topInsetPercent = seekBar.progress
                applyTopInset()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

    private fun requestScrollToEnd() {
        scrollToEndUntil = System.currentTimeMillis() + SCROLL_TO_END_TIMEOUT_MS
        scrollToEndWhenKeyboardVisible = true

        scrollToEndIfRequested()

        noteScroll.postDelayed({ scrollToEndIfRequested() }, 50)
        noteScroll.postDelayed({ scrollToEndIfRequested() }, 150)
        noteScroll.postDelayed({ scrollToEndIfRequested() }, 300)
        noteScroll.postDelayed({ scrollToEndIfRequested() }, 600)
        noteScroll.postDelayed({ scrollToEndIfRequested() }, 900)
    }

    private fun scrollToEndIfRequested() {
        if (isFinishing || isDestroyed) {
            return
        }

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

    private suspend fun saveCurrentNoteNow() {
        val uri = currentNoteUri ?: return

        val text = withContext(Dispatchers.Main) {
            editText.text.toString()
        }

        if (text == lastSavedText) {
            return
        }

        val success = withContext(Dispatchers.IO) {
            storage.writeText(uri, text)
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
            storage.writeText(uri, text)
        }

        if (success) {
            lastSavedText = text
        }
    }

    private fun openLastOrCreateNote() {
        lifecycleScope.launch {
            if (!storage.hasTreePermission()) {
                pickFolder(true)
                return@launch
            }

            val tree = storage.getTree() ?: return@launch

            val lastUri = prefs.lastNoteUri?.let(Uri::parse)

            if (lastUri != null) {
                if (storage.exists(lastUri) && openNoteInternal(lastUri, saveCurrentFirst = false)) {
                    return@launch
                }
            }

            val quickNote = storage.findFile(tree, DEFAULT_NOTE_NAME)

            if (quickNote != null && quickNote.isFile) {
                if (openNoteInternal(quickNote.uri, saveCurrentFirst = false)) {
                    return@launch
                }
            }

            val created = storage.createFile(tree, DEFAULT_NOTE_NAME)

            if (created == null) {
                toast(getString(R.string.could_not_create_note))
                return@launch
            }

            currentNoteUri = created.uri
            prefs.lastNoteUri = created.uri.toString()

            setTextWithoutWatcher("")
            undoRedo.clear()
            lastSavedText = ""

            setCursorEndAndShowKeyboard()
        }
    }

    private suspend fun openNoteInternal(uri: Uri, saveCurrentFirst: Boolean = true): Boolean {
        if (saveCurrentFirst) {
            saveCurrentNoteNow()
        }

        val text = withContext(Dispatchers.IO) {
            storage.readText(uri)
        }

        if (text == null) {
            return false
        }

        currentNoteUri = uri
        prefs.lastNoteUri = uri.toString()

        setTextWithoutWatcher(text)
        undoRedo.clear()

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

            if (!storage.hasTreePermission()) {
                pickFolder(true)
                return@launch
            }

            val tree = storage.getTree() ?: return@launch

            val doc = storage.createFile(tree, NoteUtils.newNoteName())

            if (doc == null) {
                toast(getString(R.string.could_not_create_note))
                return@launch
            }

            currentNoteUri = doc.uri
            prefs.lastNoteUri = doc.uri.toString()

            setTextWithoutWatcher("")
            undoRedo.clear()

            lastSavedText = ""

            setCursorEndAndShowKeyboard()
        }
    }

    private fun createNewNoteWithCurrentText() {
        lifecycleScope.launch {
            if (!storage.hasTreePermission()) {
                pickFolder(true)
                return@launch
            }

            val tree = storage.getTree() ?: return@launch

            val doc = storage.createFile(tree, NoteUtils.newNoteName())

            if (doc == null) {
                toast(getString(R.string.could_not_create_note))
                return@launch
            }

            currentNoteUri = doc.uri
            prefs.lastNoteUri = doc.uri.toString()
            undoRedo.clear()

            val text = editText.text.toString()

            val success = withContext(Dispatchers.IO) {
                storage.writeText(doc.uri, text)
            }

            if (success) {
                lastSavedText = text
            } else {
                toast(getString(R.string.save_failed_choose_folder))
            }

            setCursorEndAndShowKeyboard()
        }
    }

    private fun onNoteRenamed(oldUri: Uri, newUri: Uri) {
        if (currentNoteUri == oldUri) {
            currentNoteUri = newUri
            prefs.lastNoteUri = newUri.toString()
        }

        if (prefs.lastNoteUri == oldUri.toString()) {
            prefs.lastNoteUri = newUri.toString()
        }
    }

    private fun undo() {
        val current = editText.text.toString()
        val previous = undoRedo.undo(current) ?: return

        setTextWithoutWatcher(previous)

        val safeSelection = editText.selectionStart
            .coerceAtLeast(0)
            .coerceIn(0, previous.length)

        editText.setSelection(safeSelection)
        editText.bringPointIntoView(safeSelection)

        showKeyboard()
        scheduleSave()
        invalidateOptionsMenu()
    }

    private fun redo() {
        val current = editText.text.toString()
        val next = undoRedo.redo(current) ?: return

        setTextWithoutWatcher(next)

        val safeSelection = editText.selectionStart
            .coerceAtLeast(0)
            .coerceIn(0, next.length)

        editText.setSelection(safeSelection)
        editText.bringPointIntoView(safeSelection)

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
                scrollToEndUntil = 0L
                scrollToEndWhenKeyboardVisible = false
                noteScroll.scrollTo(0, 0)
            } else {
                requestScrollToEnd()
            }

            editText.requestFocus()
            showKeyboard()
        }
    }

    private fun isKeyboardVisible(): Boolean {
        return ViewCompat.getRootWindowInsets(rootLayout)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
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
}
