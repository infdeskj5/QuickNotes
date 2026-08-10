package com.infdesk5.quicknotes

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
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
        private const val SCROLL_TO_END_TIMEOUT_MS = 1200L
        private const val MAX_TOP_INSET_PERCENT = 90
        private const val EXTRA_NOTE_ID = "extra_note_id"
    }

    private lateinit var noteManager: NoteManager

    private lateinit var rootLayout: View
    private lateinit var editText: EditText
    private lateinit var noteScroll: ObservableScrollView
    private lateinit var noteSlotBar: NoteSlotBar
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText

    private var currentNote: Note? = null
    private var allNotes: List<Note> = emptyList()
    private var saveJob: Job? = null
    private var isLoading = false
    private var isProgrammaticTextChange = false
    private var lastSavedText = ""

    private var isTextSelectionActionMode = false
    private var scrollToEndUntil = 0L
    private var scrollToEndWhenKeyboardVisible = false

    // In-note search state
    private var searchMatches = mutableListOf<Int>()
    private var currentSearchIndex = 0

    private val undoRedo = UndoRedoManager()

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scrollToEndIfRequested()
        updateFastScroller()
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (!isProgrammaticTextChange && !isLoading) {
                undoRedo.beforeUserTextChanged(s?.toString() ?: "", count, after)
            }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

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

            val success = noteManager.externalRepo.handlePermissionResult(uri)
            if (success) {
                noteManager.setTreeUri(uri)
                lifecycleScope.launch { refreshNotes() }
            } else {
                toast("Permission was not persisted.")
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
        noteSlotBar = findViewById(R.id.note_slot_bar)
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)

        noteManager = NoteManager(this, getPreferences(MODE_PRIVATE))

        setupFastScroller()
        setupClickToFocus()
        setupSearchBar()
        setupNoteSlotBar()

        undoRedo.onAvailabilityChanged = { invalidateOptionsMenu() }

        noteScroll.setSmoothScrollingEnabled(false)
        editText.setShowSoftInputOnFocus(false)
        applyTopInset()
        applyAppColor()

        noteScroll.onScrollChangedListener = { _, _ -> updateFastScroller() }
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime()) && scrollToEndWhenKeyboardVisible) {
                noteScroll.post { scrollToEnd() }
                scrollToEndWhenKeyboardVisible = false
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }

        editText.addTextChangedListener(textWatcher)

        // Handle shortcut intent
        val shortcutNoteId = intent.getStringExtra(EXTRA_NOTE_ID)

        if (savedInstanceState != null) {
            val noteId = savedInstanceState.getString(KEY_CURRENT_NOTE_ID)
            val text = savedInstanceState.getString(KEY_EDIT_TEXT) ?: ""
            lastSavedText = savedInstanceState.getString(KEY_LAST_SAVED_TEXT) ?: text

            setTextWithoutWatcher(text)
            undoRedo.clear()

            lifecycleScope.launch {
                refreshNotes()
                if (noteId != null) {
                    openNoteById(noteId)
                }
            }
        } else {
            lifecycleScope.launch {
                refreshNotes()
                if (shortcutNoteId != null) {
                    openNoteById(shortcutNoteId)
                } else {
                    openLastNote()
                }
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
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        if (noteId != null) {
            lifecycleScope.launch { openNoteById(noteId) }
        }
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
        if (!noteManager.keyboardOnSelect && !isKeyboardVisible()) {
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

        menu.findItem(R.id.action_storage_mode)?.title = getString(
            R.string.storage_mode,
            if (noteManager.storageMode == StorageMode.LOCAL)
                getString(R.string.storage_local)
            else
                getString(R.string.storage_external)
        )

        menu.findItem(R.id.action_keyboard_on_select)?.isChecked = noteManager.keyboardOnSelect

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new -> { createNewNote(); true }
            R.id.action_search_note -> { showInNoteSearch(); true }
            R.id.action_notes -> { showNotesMenu(); true }
            R.id.action_undo -> { undo(); true }
            R.id.action_redo -> { redo(); true }
            R.id.action_save -> {
                lifecycleScope.launch {
                    saveCurrentNoteNow()
                    toast(getString(R.string.saved))
                }
                true
            }
            R.id.action_top_height -> { showTopHeightDialog(); true }
            R.id.action_storage_mode -> { toggleStorageMode(); true }
            R.id.action_sync_notes -> { syncNotes(); true }
            R.id.action_import_backup -> { importBackup(); true }
            R.id.action_export_backup -> { exportBackup(); true }
            R.id.action_app_color -> { showColorPicker(); true }
            R.id.action_keyboard_on_select -> {
                noteManager.keyboardOnSelect = !noteManager.keyboardOnSelect
                invalidateOptionsMenu()
                true
            }
            R.id.action_folder -> {
                lifecycleScope.launch {
                    saveCurrentNoteNow()
                    pickFolderLauncher.launch(null)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ==================== NOTE MANAGEMENT ====================

    private suspend fun refreshNotes() {
        allNotes = noteManager.listNotes()
        updateSlotBar()
    }

    private fun updateSlotBar() {
        noteSlotBar.setNotes(
            notes = allNotes,
            slotCount = noteManager.metadata.slotCount,
            appColor = noteManager.appColor,
            currentNoteId = currentNote?.id
        )
    }

    private fun setupNoteSlotBar() {
        noteSlotBar.setOnSlotClickListener { note ->
            lifecycleScope.launch { openNote(note) }
        }

        noteSlotBar.setOnSlotLongClickListener { note, index ->
            showSlotConfigDialog(note, index)
        }

        noteSlotBar.setOnSlotReorderListener { from, to ->
            lifecycleScope.launch {
                val mutableNotes = allNotes.toMutableList()
                if (from < mutableNotes.size && to < mutableNotes.size) {
                    val item = mutableNotes.removeAt(from)
                    mutableNotes.add(to, item)
                    allNotes = mutableNotes
                    updateSlotBar()
                    // Save slot order to metadata
                    noteManager.metadata.notes.clear()
                    allNotes.forEachIndexed { i, n ->
                        noteManager.metadata.notes.add(
                            com.infdesk5.quicknotes.model.NoteMetaEntry(n.id, n.name, i, n.slotColor)
                        )
                    }
                    noteManager.saveMetadata()
                }
            }
        }
    }

    private suspend fun openNote(note: Note) {
        saveCurrentNoteNow()

        val text = withContext(Dispatchers.IO) { noteManager.readNote(note) }
        if (text == null) {
            toast(getString(R.string.could_not_open_note))
            return
        }

        currentNote = note
        setTextWithoutWatcher(text)
        undoRedo.clear()
        lastSavedText = text

        setCursorEndAndShowKeyboard()
        updateSlotBar()
    }

    private suspend fun openNoteById(noteId: String) {
        val note = allNotes.find { it.id == noteId }
        if (note != null) {
            openNote(note)
        } else {
            refreshNotes()
            val found = allNotes.find { it.id == noteId }
            if (found != null) openNote(found)
            else openLastNote()
        }
    }

    private suspend fun openLastNote() {
        if (allNotes.isEmpty()) {
            refreshNotes()
        }

        if (allNotes.isNotEmpty()) {
            openNote(allNotes.first())
        } else {
            createNewNote()
        }
    }

    private fun createNewNote() {
        lifecycleScope.launch {
            saveCurrentNoteNow()

            val name = NoteUtils.newNoteName()
            val note = noteManager.createNote(name)

            if (note == null) {
                toast(getString(R.string.could_not_create_note))
                return@launch
            }

            currentNote = note
            setTextWithoutWatcher("")
            undoRedo.clear()
            lastSavedText = ""

            setCursorEndAndShowKeyboard()
            refreshNotes()
        }
    }

    private fun showNotesMenu() {
        lifecycleScope.launch {
            saveCurrentNoteNow()
            val notes = noteManager.listNotes()

            if (notes.isEmpty()) {
                toast(getString(R.string.no_notes_found))
                return@launch
            }

            val names = notes.map { it.displayName }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.notes))
                .setItems(names) { _, which ->
                    lifecycleScope.launch { openNote(notes[which]) }
                }
                .setNeutralButton(getString(R.string.search_all_notes)) { _, _ ->
                    showCrossNoteSearch()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
                .apply {
                    // Long press to delete or create shortcut
                    getListView().setOnItemLongClickListener { _, _, position, _ ->
                        showNoteOptionsDialog(notes[position])
                        true
                    }
                }
        }
    }

    private fun showNoteOptionsDialog(note: Note) {
        val options = arrayOf(
            getString(R.string.delete),
            getString(R.string.rename),
            getString(R.string.create_shortcut)
        )

        AlertDialog.Builder(this)
            .setTitle(note.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> deleteNote(note)
                    1 -> renameNote(note)
                    2 -> createNoteShortcut(note)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteNote(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.delete_note_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    val success = noteManager.deleteNote(note)
                    if (success) {
                        toast(getString(R.string.note_deleted))
                        if (currentNote?.id == note.id) {
                            currentNote = null
                            openLastNote()
                        }
                        refreshNotes()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun renameNote(note: Note) {
        val input = EditText(this)
        input.setText(note.displayName)
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_note))
            .setView(input)
            .setPositiveButton(getString(R.string.rename)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    toast(getString(R.string.name_cannot_be_empty))
                    return@setPositiveButton
                }
                val fullName = if (newName.contains('.')) newName else "$newName.txt"
                lifecycleScope.launch {
                    noteManager.renameNote(note, fullName)
                    refreshNotes()
                    updateSlotBar()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun createNoteShortcut(note: Note) {
        try {
            val manager = getSystemService(ShortcutManager::class.java)
            if (manager.isRequestPinShortcutSupported) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(EXTRA_NOTE_ID, note.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val shortcut = ShortcutInfo.Builder(this, note.id)
                    .setShortLabel(note.displayName)
                    .setLongLabel(note.displayName)
                    .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_edit))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)
                toast(getString(R.string.shortcut_created))
            }
        } catch (e: Exception) {
            toast("Shortcut creation failed")
        }
    }

    // ==================== SEARCH ====================

    private fun setupSearchBar() {
        val searchPrev = findViewById<ImageButton>(R.id.search_prev)
        val searchNext = findViewById<ImageButton>(R.id.search_next)
        val searchClose = findViewById<ImageButton>(R.id.search_close)

        searchPrev.setOnClickListener { navigateSearch(-1) }
        searchNext.setOnClickListener { navigateSearch(1) }
        searchClose.setOnClickListener { hideSearch() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performInNoteSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun showInNoteSearch() {
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        showKeyboardFor(searchInput)
    }

    private fun hideSearch() {
        searchBar.visibility = View.GONE
        searchInput.text.clear()
        clearSearchHighlights()
        hideKeyboard()
        editText.requestFocus()
    }

    private fun performInNoteSearch(query: String) {
        clearSearchHighlights()
        searchMatches.clear()
        currentSearchIndex = 0

        if (query.length < 2) return

        val text = editText.text.toString()
        var index = text.indexOf(query, 0, true)

        while (index >= 0) {
            searchMatches.add(index)
            index = text.indexOf(query, index + query.length, true)
        }

        highlightSearchMatches(query)

        if (searchMatches.isNotEmpty()) {
            navigateSearch(0)
        }
    }

    private fun highlightSearchMatches(query: String) {
        val spannable = editText.text as? Spannable ?: return
        val highlightColor = noteManager.appColor and 0x80FFFFFF.toInt() // Semi-transparent

        for (matchIndex in searchMatches) {
            spannable.setSpan(
                BackgroundColorSpan(highlightColor),
                matchIndex,
                matchIndex + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun clearSearchHighlights() {
        val spannable = editText.text as? Spannable ?: return
        val spans = spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java)
        spans.forEach { spannable.removeSpan(it) }
    }

    private fun navigateSearch(direction: Int) {
        if (searchMatches.isEmpty()) return

        currentSearchIndex = if (direction > 0) {
            (currentSearchIndex + 1) % searchMatches.size
        } else if (direction < 0) {
            if (currentSearchIndex <= 0) searchMatches.size - 1 else currentSearchIndex - 1
        } else {
            currentSearchIndex
        }

        val position = searchMatches[currentSearchIndex]
        editText.setSelection(position)
        editText.bringPointIntoView(position)
    }

    private fun showCrossNoteSearch() {
        val input = EditText(this)
        input.hint = getString(R.string.search_all_notes)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_all_notes))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    performCrossNoteSearch(query)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performCrossNoteSearch(query: String) {
        lifecycleScope.launch {
            val results = mutableListOf<Pair<Note, String>>()

            for (note in allNotes) {
                val content = noteManager.readNote(note)
                if (content != null && content.contains(query, true)) {
                    // Get the line containing the match
                    val line = content.lines().find { it.contains(query, true) } ?: ""
                    results.add(note to line.trim())
                }
            }

            if (results.isEmpty()) {
                toast(getString(R.string.no_search_results))
                return@launch
            }

            val displayText = results.joinToString("\n\n") { (note, line) ->
                "📝 ${note.displayName}\n   $line"
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.search_results, results.size, results.size))
                .setMessage(displayText)
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }
    }

    // ==================== STORAGE ====================

    private fun toggleStorageMode() {
        val newMode = when (noteManager.storageMode) {
            StorageMode.LOCAL -> StorageMode.EXTERNAL
            StorageMode.EXTERNAL -> StorageMode.LOCAL
        }

        if (newMode == StorageMode.EXTERNAL && !noteManager.externalRepo.hasPermission()) {
            pickFolderLauncher.launch(null)
            return
        }

        noteManager.storageMode = newMode
        lifecycleScope.launch {
            refreshNotes()
            openLastNote()
        }
        invalidateOptionsMenu()
    }

    private fun syncNotes() {
        if (!noteManager.externalRepo.hasPermission()) {
            toast(getString(R.string.folder_needed))
            pickFolderLauncher.launch(null)
            return
        }

        lifecycleScope.launch {
            toast("Syncing...")
            val result = noteManager.syncNotes()
            toast(getString(R.string.sync_complete, result.copied, result.updated))
            refreshNotes()
        }
    }

    private fun exportBackup() {
        lifecycleScope.launch {
            val path = noteManager.localRepo.exportBackup()
            if (path != null) {
                toast("${getString(R.string.backup_exported)}: $path")
            } else {
                toast(getString(R.string.backup_failed))
            }
        }
    }

    private fun importBackup() {
        // Simple: look for latest backup
        lifecycleScope.launch {
            val backupDir = java.io.File(filesDir, "backup")
            val backups = backupDir.listFiles()?.sortedByDescending { it.lastModified() }
            if (backups.isNullOrEmpty()) {
                toast(getString(R.string.backup_failed))
                return@launch
            }

            val success = noteManager.localRepo.importBackup(backups.first().absolutePath)
            if (success) {
                toast(getString(R.string.backup_imported))
                refreshNotes()
            } else {
                toast(getString(R.string.backup_failed))
            }
        }
    }

    // ==================== SLOT CONFIGURATION ====================

    private fun showSlotConfigDialog(note: Note, slotIndex: Int) {
        val options = arrayOf(
            getString(R.string.slot_color),
            getString(R.string.assign_to_slot)
        )

        AlertDialog.Builder(this)
            .setTitle(note.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSlotColorPicker(note)
                    1 -> showSlotCountDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSlotColorPicker(note: Note) {
        val colors = intArrayOf(
            0xFF00FF7F.toInt(), // Neon green
            0xFFFF4444.toInt(), // Red
            0xFF4488FF.toInt(), // Blue
            0xFFFFAA00.toInt(), // Orange
            0xFFAA44FF.toInt(), // Purple
            0xFF00FFFF.toInt(), // Cyan
            0xFFFF44AA.toInt(), // Pink
            0xFFFFFFFF.toInt(), // White
            0xFF333333.toInt()  // Dark gray
        )

        val colorNames = arrayOf(
            "Neon Green", "Red", "Blue", "Orange",
            "Purple", "Cyan", "Pink", "White", "Dark Gray"
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.slot_color))
            .setItems(colorNames) { _, which ->
                note.slotColor = colors[which]
                updateSlotBar()
                noteManager.saveMetadata()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSlotCountDialog() {
        val counts = arrayOf("1", "2", "3", "4", "5", "6", "7", "8")

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.slot_count))
            .setItems(counts) { _, which ->
                noteManager.metadata = noteManager.metadata.copy(slotCount = which + 1)
                noteManager.saveMetadata()
                updateSlotBar()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ==================== COLOR & THEME ====================

    private fun showColorPicker() {
        val colors = intArrayOf(
            0xFF00FF7F.toInt(), // Neon green (default)
            0xFFFF4444.toInt(), // Red
            0xFF4488FF.toInt(), // Blue
            0xFFFFAA00.toInt(), // Orange
            0xFFAA44FF.toInt(), // Purple
            0xFF00FFFF.toInt(), // Cyan
            0xFFFF44AA.toInt(), // Pink
            0xFFFFFFFF.toInt()  // White
        )

        val colorNames = arrayOf(
            "Neon Green", "Red", "Blue", "Orange",
            "Purple", "Cyan", "Pink", "White"
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_color))
            .setItems(colorNames) { _, which ->
                noteManager.appColor = colors[which]
                applyAppColor()
                updateSlotBar()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun applyAppColor() {
        val color = noteManager.appColor
        // Apply to fast scroller thumb
        findViewById<View>(R.id.scroll_thumb)?.background?.setTint(color)
        // Apply to search bar accents
        findViewById<ImageButton>(R.id.search_next)?.colorFilter =
            android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    // ==================== TOP HEIGHT ====================

    private fun showTopHeightDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_top_height, null)
        val valueText = view.findViewById<TextView>(R.id.top_height_value)
        val seekBar = view.findViewById<SeekBar>(R.id.top_height_seek)

        seekBar.max = MAX_TOP_INSET_PERCENT
        val current = noteManager.topInsetPercent.coerceIn(0, MAX_TOP_INSET_PERCENT)
        seekBar.progress = current
        valueText.text = getString(R.string.top_height_value, current)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = getString(R.string.top_height_value, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.top_height))
            .setView(view)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                noteManager.topInsetPercent = seekBar.progress
                applyTopInset()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun applyTopInset() {
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        val percent = noteManager.topInsetPercent.coerceIn(0, MAX_TOP_INSET_PERCENT)
        val topInset = (resources.displayMetrics.heightPixels * percent / 100f).toInt()

        editText.setPadding(basePadding, topInset, basePadding, basePadding)
    }

    // ==================== SAVE / LOAD ====================

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            saveCurrentNoteNow()
        }
    }

    private suspend fun saveCurrentNoteNow() {
        val note = currentNote ?: return

        val text = withContext(Dispatchers.Main) { editText.text.toString() }
        if (text == lastSavedText) return

        val success = withContext(Dispatchers.IO) { noteManager.writeNote(note, text) }
        if (success) {
            lastSavedText = text
        } else {
            withContext(Dispatchers.Main) {
                toast(getString(R.string.save_failed_choose_folder))
            }
        }
    }

    private fun saveCurrentNoteBlocking() {
        val note = currentNote ?: return
        val text = editText.text.toString()
        if (text == lastSavedText) return

        val success = runBlocking(Dispatchers.IO) { noteManager.writeNote(note, text) }
        if (success) {
            lastSavedText = text
        }
    }

    // ==================== UNDO / REDO ====================

    private fun undo() {
        val current = editText.text.toString()
        val previous = undoRedo.undo(current) ?: return

        setTextWithoutWatcher(previous)
        val safeSelection = editText.selectionStart.coerceAtLeast(0).coerceIn(0, previous.length)
        editText.setSelection(safeSelection)
        editText.bringPointIntoView(safeSelection)
        scheduleSave()
        invalidateOptionsMenu()
    }

    private fun redo() {
        val current = editText.text.toString()
        val next = undoRedo.redo(current) ?: return

        setTextWithoutWatcher(next)
        val safeSelection = editText.selectionStart.coerceAtLeast(0).coerceIn(0, next.length)
        editText.setSelection(safeSelection)
        editText.bringPointIntoView(safeSelection)
        scheduleSave()
        invalidateOptionsMenu()
    }

    // ==================== SCROLL & KEYBOARD ====================

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
        if (isFinishing || isDestroyed) return
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

    private fun setupFastScroller() {
        val fastScroller = findViewById<View>(R.id.fast_scroller)
        val scrollThumb = findViewById<View>(R.id.scroll_thumb)

        val controller = FastScrollController(noteScroll, fastScroller, scrollThumb)
        controller.setup()
        controller.setTopMargin(defaultTopInsetPx())
    }

    private fun updateFastScroller() {
        val fastScroller = findViewById<View>(R.id.fast_scroller)
        val scrollThumb = findViewById<View>(R.id.scroll_thumb)
        val controller = FastScrollController(noteScroll, fastScroller, scrollThumb)
        controller.update(noteScroll.scrollY, noteScroll.getMaxScroll())
    }

    private fun defaultTopInsetPx(): Int {
        return (resources.displayMetrics.heightPixels * 45 / 100f).toInt()
    }

    private fun setupClickToFocus() {
        val noteContent = findViewById<View>(R.id.note_content)

        noteContent.setOnClickListener {
            if (!isTextSelectionActionMode) {
                editText.requestFocus()
                val length = editText.text.length
                try { editText.setSelection(length) } catch (_: Exception) {}
                showKeyboard()
            }
        }

        editText.setOnClickListener {
            if (!isTextSelectionActionMode) {
                showKeyboard()
            }
        }
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
            try { editText.setSelection(length) } catch (_: Exception) {}

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
        if (isTextSelectionActionMode && !noteManager.keyboardOnSelect) return

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showKeyboardFor(view: EditText) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
