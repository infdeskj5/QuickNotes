package com.infdesk5.quicknotes

import android.content.Intent
import android.graphics.Rect
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
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.infdesk5.quicknotes.helpers.BottomDialogHelper
import com.infdesk5.quicknotes.helpers.KeyboardHelper
import com.infdesk5.quicknotes.helpers.NoteDialogHelper
import com.infdesk5.quicknotes.helpers.SearchHelper
import com.infdesk5.quicknotes.helpers.SettingsHelper
import com.infdesk5.quicknotes.model.Note
import com.infdesk5.quicknotes.storage.NoteManager
import com.infdesk5.quicknotes.storage.StorageMode
import com.infdesk5.quicknotes.ui.NoteSlotBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val KEY_CURRENT_NOTE_ID = "current_note_id"
        private const val KEY_EDIT_TEXT = "edit_text"
        private const val KEY_LAST_SAVED_TEXT = "last_saved_text"
        private const val AUTOSAVE_DELAY_MS = 700L
        private const val MAX_TOP_INSET_PERCENT = 90
        private const val EXTRA_NOTE_ID = "extra_note_id"
        private const val CURSOR_BOTTOM_MARGIN_DP = 24
    }

    // === Views ===
    private lateinit var rootLayout: View
    private lateinit var editText: EditText
    private lateinit var noteScroll: ObservableScrollView
    private lateinit var noteSlotBar: NoteSlotBar
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var fastScroller: View

    // === Core State ===
    private lateinit var noteManager: NoteManager
    private var currentNote: Note? = null
    private var allNotes: List<Note> = emptyList()
    private var saveJob: Job? = null
    private var isLoading = false
    private var isProgrammaticTextChange = false
    private var lastSavedText = ""
    private var lastKnownWindowHeight = 0

    // === Keyboard & Selection State ===
    private var isTextSelectionActionMode = false
    private var scrollToEndWhenKeyboardVisible = false
    private var cursorScrollSpacer = 0 // Extra bottom room so any line can reach the target spot

    // === Helpers ===
    private lateinit var keyboardHelper: KeyboardHelper
    private lateinit var searchHelper: SearchHelper
    private lateinit var settingsHelper: SettingsHelper
    private lateinit var noteDialogHelper: NoteDialogHelper

    // === Undo/Redo ===
    private val undoRedo = UndoRedoManager()

    // === Text Watcher ===
    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (!isProgrammaticTextChange && !isLoading) {
                undoRedo.beforeUserTextChanged(s?.toString() ?: "", count, after)
.            }
        }
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!isProgrammaticTextChange && !isLoading) scheduleSave()
        }
    }

    // === Global Layout Listener ===
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updateFastScroller()
        val currentHeight = rootLayout.height
        if (currentHeight > 0 && currentHeight != lastKnownWindowHeight) {
            lastKnownWindowHeight = currentHeight
            applyTopInset()
            applyScrollerSize()
        }
    }

    // === Activity Result Launchers ===
    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                if (noteManager.importBackupFromUri(uri)) {
                    toast(getString(R.string.backup_imported))
                    refreshNotes()
                } else {
                    toast(getString(R.string.backup_failed))
                }
            }
        }

    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                toast(getString(R.string.folder_needed))
                return@registerForActivityResult
            }
            if (noteManager.externalRepo.handlePermissionResult(uri)) {
                noteManager.setTreeUri(uri)
                lifecycleScope.launch { refreshNotes() }
            } else {
                toast("Permission was not persisted.")
            }
        }

    // ===========================
    // LIFECYCLE
    // ===========================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setActionBar(findViewById<Toolbar>(R.id.toolbar))
        actionBar?.setDisplayShowTitleEnabled(false)

        rootLayout = findViewById(R.id.root_layout)
        noteScroll = findViewById(R.id.note_scroll)
        editText = findViewById(R.id.note_edit)
        noteSlotBar = findViewById(R.id.note_slot_bar)
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)
        fastScroller = findViewById(R.id.fast_scroller)

        noteManager = NoteManager(this, getPreferences(MODE_PRIVATE))
        keyboardHelper = KeyboardHelper(this)

        searchHelper = SearchHelper(
            activity = this,
            editText = editText,
            noteScroll = noteScroll,
            searchBar = searchBar,
            searchInput = searchInput,
            noteSlotBar = noteSlotBar,
            noteManager = noteManager,
            keyboardHelper = keyboardHelper,
            getNotes = { allNotes },
            openNote = { openNote(it) },
            toast = { toast(it) }
        )

        settingsHelper = SettingsHelper(
            activity = this,
            noteManager = noteManager,
            applyTopInset = { applyTopInset() },
            applyScrollerSize = { applyScrollerSize() },
            applyScrollerVisibility = { applyScrollerVisibility() },
            applyAppColor = { applyAppColor() },
            updateSlotBar = { updateSlotBar() },
            toggleStorageMode = { toggleStorageMode() },
            syncNotes = { syncNotes() },
            exportBackup = { exportBackup() },
            launchImportPicker = { importBackupLauncher.launch(arrayOf("application/zip")) },
            launchFolderPicker = { lifecycleScope.launch { saveCurrentNoteNow(); pickFolderLauncher.launch(null) } },
            toast = { toast(it) }
        )

        noteDialogHelper = NoteDialogHelper(
            activity = this,
            noteManager = noteManager,
            getNotes = { allNotes },
            openNote = { openNote(it) },
            saveCurrentNote = { saveCurrentNoteNow() },
            refreshAndOpenLast = { refreshNotes(); openLastNote() },
            updateSlotBar = { updateSlotBar() },
            saveNoteOrder = { saveNoteOrder() },
            toast = { toast(it) }
        )

        setupFastScroller()
        setupClickToFocus()
        searchHelper.setup()
        setupNoteSlotBar()

        undoRedo.onAvailabilityChanged = { invalidateOptionsMenu() }
        noteScroll.setSmoothScrollingEnabled(false)

        applyTopInset()
        applyAppColor()
        applyScrollerSize()
        applyScrollerVisibility()

        noteScroll.onScrollChangedListener = { _, _ -> updateFastScroller() }
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        // Keyboard insets listener: definitively handles scrolling when keyboard appears/disappears
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (isImeVisible) {
                if (scrollToEndWhenKeyboardVisible) {
                    noteScroll.post { scrollToEnd() }
                    scrollToEndWhenKeyboardVisible = false
                } else if (editText.hasFocus() && !isTextSelectionActionMode) {
                    noteScroll.post { scrollToCursorAboveSlotBar() }
                }
            } else {
                setCursorScrollSpacer(false) // Remove spacer when keyboard is gone
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }

        editText.addTextChangedListener(textWatcher)

        val shortcutNoteId = intent.getStringExtra(EXTRA_NOTE_ID)
        if (savedInstanceState != null) {
            val noteId = savedInstanceState.getString(KEY_CURRENT_NOTE_ID)
            val text = savedInstanceState.getString(KEY_EDIT_TEXT) ?: ""
            lastSavedText = savedInstanceState.getString(KEY_LAST_SAVED_TEXT) ?: text
            setTextWithoutWatcher(text)
            undoRedo.clear()
            lifecycleScope.launch {
                refreshNotes()
                if (noteId != null) openNoteById(noteId)
            }
        } else {
            lifecycleScope.launch {
                refreshNotes()
                if (shortcutNoteId != null) openNoteById(shortcutNoteId) else openLastNote()
            }
        }
    }

    override fun onDestroy() {
        rootLayout.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_NOTE_ID, currentNote?.id)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_NOTE_ID)?.let { id ->
            lifecycleScope.launch { openNoteById(id) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchHelper.isSearchActive) {
            searchHelper.hide()
        } else {
            super.onBackPressed()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            scrollToEndWhenKeyboardVisible = false
        }
        return super.dispatchTouchEvent(ev)
    }

    // ===========================
    // TEXT SELECTION / ACTION MODE (ULTIMATE FIX)
    // ===========================

    override fun onActionModeStarted(mode: ActionMode?) {
        super.onActionModeStarted(mode)
        isTextSelectionActionMode = true

        if (noteManager.keyboardOnSelect) {
            if (!isKeyboardVisible()) {
                val selStart = editText.selectionStart
                val selEnd = editText.selectionEnd

                // Show keyboard
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

                // Restore selection and invalidate ActionMode after a short delay.
                // This prevents the keyboard resize from destroying the copy/paste menu.
                editText.postDelayed({
                    if (editText.selectionStart == editText.selectionEnd) {
                        try {
                            editText.setSelection(
                                selStart.coerceIn(0, editText.text.length),
                                selEnd.coizeIn(0, editText.text.length)
                            )
                        } catch (_: Exception) {}
                    }
                    mode?.invalidate() // Forces the floating toolbar to redraw
                }, 150)
            } else {
                // Keyboard already visible, just ensure ActionMode is valid
                mode?.invalidate()
            }
        } else {
            hideKeyboard()
        }
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        super.onActionModeFinished(mode)
        isTextSelectionActionMode = false
    }

    // ===========================
    // MENU
    // ===========================

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
            R.id.action_new -> { createNewNote(); true }
            R.id.action_search_note -> { searchHelper.show(); true }
            R.id.action_notes -> { noteDialogHelper.showNotesMenu(); true }
            R.id.action_undo -> { undo(); true }
            R.id.action_redo -> { redo(); true }
            R.id.action_save -> {
                lifecycleScope.launch { saveCurrentNoteNow(); toast(getString(R.string.saved)) }
                true
            }
            R.id.action_settings -> { settingsHelper.show(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ===========================
    // NOTE MANAGEMENT
    // ===========================

    private suspend fun refreshNotes() {
        allNotes = noteManager.listNotes()
        updateSlotBar()
    }

    private fun updateSlotBar() {
        noteSlotBar.setNotes(allNotes, noteManager.metadata.slotCount, noteManager.appColor, currentNote?.id)
    }

    private fun setupNoteSlotBar() {
        noteSlotBar.setOnSlotClickListener { note ->
            lifecycleScope.launch { openNote(note) }
        }
        noteSlotBar.setOnSlotReorderListener { from, to ->
            lifecycleScope.launch {
                val mutableNotes = allNotes.toMutableList()
                if (from < mutableNotes.size && to < mutableNotes.size) {
                    val item = mutableNotes.removeAt(from)
                    mutableNotes.add(to, item)
                    allNotes = mutableNotes
                    updateSlotBar()
                    saveNoteOrder()
                }
            }
        }
    }

    private fun saveNoteOrder() {
        noteManager.metadata.notes.clear()
        allNotes.forEachIndexed { i, n ->
            noteManager.metadata.notes.add(
                com.infdesk5.quicknotes.model.NoteMetaEntry(n.id, n.name, i, n.slotColor)
            )
        }
        noteManager.saveMetadata()
    }

    private suspend fun openNote(note: Note) {
        saveCurrentNoteNow()
        val text = withContext(Dispatchers.IO) { noteManager.readNote(note) }
        if (text == null) {
            toast(getString(R.string.could_not_open_note))
            return
        }

        currentNote = note
        editText.visibility = View.INVISIBLE
        setTextWithoutWatcher(text)
        undoRedo.clear()
        lastSavedText = text

        editText.post {
            try { editText.setSelection(text.length) } catch (_: Exception) {}
            noteScroll.scrollTo(0, noteScroll.getMaxScroll())
            editText.visibility = View.VISIBLE

            if (noteManager.showKeyboardOnOpenNote) {
                editText.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                scrollToEndWhenKeyboardVisible = true
            } else {
                scrollToEnd()
            }
        }
        updateSlotBar()
    }

    private suspend fun openNoteById(noteId: String) {
        val note = allNotes.find { it.id == noteId }
        if (note != null) openNote(note)
        else {
            refreshNotes()
            allNotes.find { it.id == noteId }?.let { openNote(it) } ?: openLastNote()
        }
    }

    private suspend fun openLastNote() {
        if (allNotes.isEmpty()) refreshNotes()
        if (allNotes.isNotEmpty()) openNote(allNotes.first()) else createNewNote()
    }

    private fun createNewNote() {
        val input = EditText(this)
        val defaultName = NoteUtils.newNoteName()
        val displayName = defaultName.removeSuffix(".txt").removeSuffix(".md")
        input.setText(displayName)
        input.selectAll()

        BottomDialogHelper.show(this, getString(R.string.new_note), BottomDialogHelper.wrapInPadding(this, input), getString(R.string.create)) {
            val name = input.text.toString().trim()
            if (name.isEmpty()) { toast(getString(R.string.name_cannot_be_empty)); return@show }
            val fullName = if (name.contains('.')) name else "$name.txt"
            lifecycleScope.launch {
                saveCurrentNoteNow()
                val note = noteManager.createNote(fullName)
                if (note == null) { toast(getString(R.string.could_not_create_note)); return@launch }
                currentNote = note
                setTextWithoutWatcher("")
                undoRedo.clear()
                lastSavedText = ""
                setCursorEndAndShowKeyboard()
                refreshNotes()
            }
        }

        input.postDelayed({
            input.requestFocus()
            input.selectAll()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    // ===========================
    // CLICK TO FOCUS (ULTIMATE FIX)
    // ===========================

    private fun setupClickToFocus() {
        findViewById<View>(R.id.note_content).setOnClickListener {
            if (!isTextSelectionActionMode) {
                val wasKeyboardVisible = isKeyboardVisible()
                editText.requestFocus()
                editText.setSelection(editText.text.length)
                
                if (!wasKeyboardVisible) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                }
                
                // If keyboard was already visible, the insets listener won't fire.
                // We must scroll manually. If it's newly appearing, let the insets listener handle it.
                if (wasKeyboardVisible) {
                    editText.postDelayed({
                        if (!isTextSelectionActionMode) scrollToCursorAboveSlotBar()
                    }, 100)
                }
            }
        }

        // Intentionally NOT an OnClickListener: returning false ensures EditText's native 
        // double-tap word selection and long-press handling run completely untouched.
        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                editText.post {
                    val isSelecting = isTextSelectionActionMode || editText.selectionStart != editText.selectionEnd
                    if (!isSelecting) {
                        val wasKeyboardVisible = isKeyboardVisible()
                        if (!editText.hasFocus()) {
                            editText.requestFocus()
                        }
                        if (!wasKeyboardVisible) {
                            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                        }
                        if (wasKeyboardVisible) {
                            editText.postDelayed({
                                if (!isTextSelectionActionMode) scrollToCursorAboveSlotBar()
                            }, 100)
                        }
                    }
                }
            }
            false // Crucial: let the system handle the touch natively
        }
    }

    // ===========================
    // SETTINGS ACTIONS
    // ===========================

    private fun toggleStorageMode() {
        val newMode = if (noteManager.storageMode == StorageMode.LOCAL) StorageMode.EXTERNAL else StorageMode.LOCAL
        if (newMode == StorageMode.EXTERNAL && !noteManager.externalRepo.hasPermission()) {
            pickFolderLauncher.launch(null)
            return
        }
        noteManager.storageMode = newMode
        lifecycleScope.launch { refreshNotes(); openLastNote() }
        invalidateOptionsMenu()
    }

    private fun syncNotes() {
        if (!noteManager.externalRepo.hasPermission()) {
            toast(getString(R.string.folder_needed))
            pickFolderLauncher.launch(null)
            return
        }
        lifecycleScope.launch {
            toast("Syncing…")
            val result = noteManager.syncNotes()
            toast(getString(R.string.sync_complete, result.copied, result.updated))
            refreshNotes()
        }
    }

    private fun exportBackup() {
        lifecycleScope.launch {
            val path = noteManager.exportBackup()
            if (path != null) toast(getString(R.string.backup_exported, path))
            else toast(getString(R.string.backup_failed))
        }
    }

    // ===========================
    // APPLY SETTINGS & SCROLLING (ULTIMATE FIX)
    // ===========================

    private fun applyTopInset() {
        val basePadding = dp(16)
        val percent = noteManager.topInsetPercent.coerceIn(0, MAX_TOP_INSET_PERCENT)
        val availableHeight = if (lastKnownWindowHeight > 0) lastKnownWindowHeight else resources.displayMetrics.heightPixels
        val topInset = (availableHeight * percent / 100f).toInt()
        // Add cursorScrollSpacer to the bottom padding so the last line can always reach the target zone
        editText.setPadding(basePadding, topInset, basePadding, basePadding + cursorScrollSpacer)
    }

    private fun setCursorScrollSpacer(active: Boolean) {
        val target = if (active) noteScroll.height else 0
        if (target != cursorScrollSpacer) {
            cursorScrollSpacer = target
            applyTopInset()
        }
    }

    private fun applyScrollerSize() {
        val percent = noteManager.scrollerSizePercent
        val density = resources.displayMetrics.density
        val scrollerWidth = (24 * density * percent / 100f).toInt()
        val thumbWidth = (8 * density * percent / 100f).toInt()
        val thumbHeight = (56 * density * percent / 100f).toInt()
        fastScroller.layoutParams = fastScroller.layoutParams.apply { width = scrollerWidth }
        findViewById<View>(R.id.scroll_thumb).layoutParams =
            findViewById<View>(R.id.scroll_thumb).layoutParams.apply { width = thumbWidth; height = thumbHeight }
        fastScroller.requestLayout()
    }

    private fun applyScrollerVisibility() {
        fastScroller.visibility = if (noteManager.showScroller) View.INVISIBLE else View.GONE
    }

    private fun applyAppColor() {
        findViewById<View>(R.id.scroll_thumb)?.background?.setTint(noteManager.appColor)
    }

    // ===========================
    // CORE LOGIC
    // ===========================

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch { delay(AUTOSAVE_DELAY_MS); saveCurrentNoteNow() }
    }

    private suspend fun saveCurrentNoteNow() {
        val note = currentNote ?: return
        val text = withContext(Dispatchers.Main) { editText.text.toString() }
        if (text == lastSavedText) return
        if (withContext(Dispatchers.IO) { noteManager.writeNote(note, text) }) lastSavedText = text
        else withContext(Dispatchers.Main) { toast(getString(R.string.save_failed_choose_folder)) }
    }

    private fun saveCurrentNoteBlocking() {
        val note = currentNote ?: return
        val text = editText.text.toString()
        if (text == lastSavedText) return
        if (runBlocking(Dispatchers.IO) { noteManager.writeNote(note, text) }) lastSavedText = text
    }

    private fun undo() {
        val scrollY = noteScroll.scrollY
        val selStart = editText.selectionStart
        val selEnd = editText.selectionEnd
        val previous = undoRedo.undo(editText.text.toString()) ?: return
        setTextWithoutWatcher(previous)
        try {
            val newLen = editText.text.length
            editText.setSelection(selStart.coerceAtMost(newLen), selEnd.coerceAtMost(newLen))
        } catch (_: Exception) {}
        noteScroll.post {
            noteScroll.scrollTo(0, scrollY.coerceAtMost(noteScroll.getMaxScroll()))
        }
        scheduleSave()
        invalidateOptionsMenu()
    }

    private fun redo() {
        val scrollY = noteScroll.scrollY
        val selStart = editText.selectionStart
        val selEnd = editText.selectionEnd
        val next = undoRedo.redo(editText.text.toString()) ?: return
        setTextWithoutWatcher(next)
        try {
            val newLen = editText.text.length
            editText.setSelection(selStart.coerceAtMost(newLen), selEnd.coerceAtMost(newLen))
        } catch (_: Exception) {}
        noteScroll.post {
            noteScroll.scrollTo(0, scrollY.coerceAtMost(noteScroll.getMaxScroll()))
        }
        scheduleSave()
        invalidateOptionsMenu()
    }

    private fun scrollToEnd() {
        val maxScroll = noteScroll.getMaxScroll()
        if (maxScroll > 0) noteScroll.scrollTo(0, maxScroll)
    }

    private fun setupFastScroller() {
        val scrollThumb = findViewById<View>(R.id.scroll_thumb)
        val controller = FastScrollController(noteScroll, fastScroller, scrollThumb)
        controller.setup()
        val availableHeight = if (lastKnownWindowHeight > 0) lastKnownWindowHeight else resources.displayMetrics.heightPixels
        controller.setTopMargin((availableHeight * 45 / 100f).toInt())
    }

    private fun updateFastScroller() {
        if (!noteManager.showScroller) return
        val scrollThumb = findViewById<View>(R.id.scroll_thumb)
        FastScrollController(noteScroll, fastScroller, scrollThumb)
            .update(noteScroll.scrollY, noteScroll.getMaxScroll())
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
            try { editText.setSelection(editText.text.length) } catch (_: Exception) {}
            if (editText.text.length == 0) {
                noteScroll.scrollTo(0, 0)
            } else {
                scrollToEnd()
            }
            if (noteManager.showKeyboardOnOpenNote) {
                editText.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                scrollToEndWhenKeyboardVisible = true
            }
        }
    }

    private fun isKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(rootLayout)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editText.windowToken, 0)
    }

    private fun toast(message: String) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ===========================
    // ULTIMATE CURSOR POSITIONING FIX
    // ===========================

    private fun scrollToCursorAboveSlotBar() {
        setCursorScrollSpacer(true)
        editText.post {
            val layout = editText.layout ?: return@post
            val cursorPos = editText.selectionEnd.coerceIn(0, editText.text.length)
            val line = layout.getLineForOffset(cursorPos)
            
            // Ask Android for the line's real on-screen bounds. 
            // This natively accounts for all padding/insets the EditText applies internally.
            val lineBounds = Rect()
            editText.getLineBounds(line, lineBounds)
            
            // Absolute Y of the cursor line bottom within the ScrollView's content
            val cursorLineBottom = editText.top + editText.paddingTop + lineBounds.bottom
            
            val visibleHeight = noteScroll.height
            if (visibleHeight <= 0) return@post
            
            // We want the cursor line to sit at roughly 70% down the visible area.
            // This leaves ~30% at the bottom for the slot bar, toolbar, and keyboard.
            val targetYOnScreen = (visibleHeight * 0.70).toInt()
            
            // The scroll Y needed to put the cursor line at targetYOnScreen
            val targetScrollY = cursorLineBottom - targetYOnScreen
            
            noteScroll.smoothScrollTo(0, targetScrollY.coerceIn(0, noteScroll.getMaxScroll()))
        }
    }
}
