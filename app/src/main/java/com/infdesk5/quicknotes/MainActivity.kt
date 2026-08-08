package com.infdesk5.quicknotes

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    }

    private lateinit var editText: EditText

    private var currentNoteUri: Uri? = null
    private var saveJob: Job? = null
    private var isLoading = false
    private var lastSavedText = ""

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            // No action needed.
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            // No action needed.
        }

        override fun afterTextChanged(s: Editable?) {
            if (!isLoading) {
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
        actionBar?.title = getString(R.string.app_name)

        editText = findViewById(R.id.note_edit)
        editText.addTextChangedListener(textWatcher)

        if (savedInstanceState != null) {
            currentNoteUri = savedInstanceState.getString(KEY_CURRENT_NOTE_URI)?.let(Uri::parse)

            val text = savedInstanceState.getString(KEY_EDIT_TEXT) ?: ""
            lastSavedText = savedInstanceState.getString(KEY_LAST_SAVED_TEXT) ?: text

            setTextWithoutWatcher(text)
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_NOTE_URI, currentNoteUri?.toString())
        outState.putString(KEY_EDIT_TEXT, editText.text.toString())
        outState.putString(KEY_LAST_SAVED_TEXT, lastSavedText)
    }

    override fun onPause() {
        super.onPause()
        saveNow()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new -> {
                createNewNote()
                true
            }

            R.id.action_notes -> {
                showNotes()
                true
            }

            R.id.action_save -> {
                lifecycleScope.launch {
                    saveCurrentNoteNow()
                    toast("Saved")
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
                toast("Save failed. Choose the notes folder again.")
            }
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
                toast("Could not create a note in the selected folder.")
                return@launch
            }

            currentNoteUri = created.uri
            saveLastNoteUri(created.uri)

            setTextWithoutWatcher("")
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
        lastSavedText = text

        setCursorEndAndShowKeyboard()

        return true
    }

    private fun openNote(uri: Uri) {
        lifecycleScope.launch {
            if (!openNoteInternal(uri)) {
                toast("Could not open note.")
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
                toast("Could not create a new note.")
                return@launch
            }

            currentNoteUri = doc.uri
            saveLastNoteUri(doc.uri)

            setTextWithoutWatcher("")
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
                toast("Could not create a new note.")
                return@launch
            }

            currentNoteUri = doc.uri
            saveLastNoteUri(doc.uri)

            val text = editText.text.toString()

            val success = withContext(Dispatchers.IO) {
                writeText(doc.uri, text)
            }

            if (success) {
                lastSavedText = text
            } else {
                toast("Save failed. Choose the notes folder again.")
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
                        .sortedByDescending { it.lastModified() }
                } catch (e: Exception) {
                    emptyList<DocumentFile>()
                }
            }

            if (docs.isEmpty()) {
                toast("No notes found in this folder.")
                return@launch
            }

            val names = docs.map { it.name ?: "Note" }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Notes")
                .setItems(names) { _, which ->
                    openNote(docs[which].uri)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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

    private fun setTextWithoutWatcher(text: String) {
        isLoading = true

        editText.removeTextChangedListener(textWatcher)
        editText.setText(text)
        editText.addTextChangedListener(textWatcher)

        isLoading = false
    }

    private fun setCursorEndAndShowKeyboard() {
        editText.post {
            try {
                editText.setSelection(editText.text.length)
            } catch (_: Exception) {
                // Ignore cursor positioning errors in unusual states.
            }

            editText.requestFocus()
            showKeyboard()
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
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
