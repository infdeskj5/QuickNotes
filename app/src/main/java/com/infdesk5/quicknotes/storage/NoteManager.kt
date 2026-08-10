package com.infdesk5.quicknotes.storage

import android.content.Context
import android.content.SharedPreferences
import com.infdesk5.quicknotes.model.Note
import com.infdesk5.quicknotes.model.NoteMetadata
import com.infdesk5.quicknotes.model.NoteMetaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class StorageMode { LOCAL, EXTERNAL }

class NoteManager(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_STORAGE_MODE = "storage_mode"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_METADATA = "note_metadata"
        private const val KEY_TOP_INSET_PERCENT = "top_inset_percent"
        private const val KEY_APP_COLOR = "app_color"
        private const val KEY_KEYBOARD_ON_SELECT = "keyboard_on_select"
        private const val DEFAULT_APP_COLOR = 0xFF00FF7F.toInt() // Neon green
    }

    val localRepo = LocalNoteRepository(context)
    val externalRepo = ExternalNoteRepository(context, getTreeUri())

    var storageMode: StorageMode
        get() = StorageMode.valueOf(prefs.getString(KEY_STORAGE_MODE, StorageMode.LOCAL.name) ?: StorageMode.LOCAL.name)
        set(value) = prefs.edit().putString(KEY_STORAGE_MODE, value.name).apply()

    val activeRepository: NoteRepository
        get() = when (storageMode) {
            StorageMode.LOCAL -> localRepo
            StorageMode.EXTERNAL -> externalRepo
        }

    var metadata: NoteMetadata = loadMetadata()

    var appColor: Int
        get() = prefs.getInt(KEY_APP_COLOR, DEFAULT_APP_COLOR)
        set(value) = prefs.edit().putInt(KEY_APP_COLOR, value).apply()

    var topInsetPercent: Int
        get() = prefs.getInt(KEY_TOP_INSET_PERCENT, 45)
        set(value) = prefs.edit().putInt(KEY_TOP_INSET_PERCENT, value).apply()

    var keyboardOnSelect: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_ON_SELECT, false)
        set(value) = prefs.edit().putBoolean(KEY_KEYBOARD_ON_SELECT, value).apply()

    fun getTreeUri() = prefs.getString(KEY_TREE_URI, null)?.let { android.net.Uri.parse(it) }

    fun setTreeUri(uri: android.net.Uri?) {
        prefs.edit().putString(KEY_TREE_URI, uri?.toString()).apply()
        externalRepo.setTreeUri(uri)
    }

    fun loadMetadata(): NoteMetadata {
        val json = prefs.getString(KEY_METADATA, null)
        return if (json != null) NoteMetadata.fromJson(json) else NoteMetadata(appColor = appColor)
    }

    fun saveMetadata() {
        prefs.edit().putString(KEY_METADATA, metadata.toJson()).apply()
    }

    suspend fun listNotes(): List<Note> {
        return activeRepository.listNotes()
    }

    suspend fun readNote(note: Note): String? {
        return activeRepository.readNote(note)
    }

    suspend fun writeNote(note: Note, content: String): Boolean {
        val success = activeRepository.writeNote(note, content)
        if (success) {
            note.lastModified = System.currentTimeMillis()
        }
        return success
    }

    suspend fun createNote(name: String): Note? {
        return activeRepository.createNote(name)
    }

    suspend fun deleteNote(note: Note): Boolean {
        return activeRepository.deleteNote(note)
    }

    suspend fun renameNote(note: Note, newName: String): Boolean {
        return activeRepository.renameNote(note, newName)
    }

    suspend fun syncNotes(): SyncResult = withContext(Dispatchers.IO) {
        val localNotes = localRepo.listNotes()
        val externalNotes = externalRepo.listNotes()

        var copied = 0
        var updated = 0

        // Copy external notes that don't exist locally
        for (extNote in externalNotes) {
            val localMatch = localNotes.find { it.name == extNote.name }
            if (localMatch == null) {
                val content = externalRepo.readNote(extNote)
                if (content != null) {
                    val newNote = localRepo.createNote(extNote.name)
                    if (newNote != null) {
                        localRepo.writeNote(newNote, content)
                        copied++
                    }
                }
            } else {
                // Update the older one
                if (extNote.lastModified > localMatch.lastModified) {
                    val content = externalRepo.readNote(extNote)
                    if (content != null) {
                        localRepo.writeNote(localMatch, content)
                        updated++
                    }
                } else if (localMatch.lastModified > extNote.lastModified) {
                    val content = localRepo.readNote(localMatch)
                    if (content != null) {
                        externalRepo.writeNote(extNote, content)
                        updated++
                    }
                }
            }
        }

        // Copy local notes that don't exist externally
        for (localNote in localNotes) {
            val extMatch = externalNotes.find { it.name == localNote.name }
            if (extMatch == null) {
                val content = localRepo.readNote(localNote)
                if (content != null) {
                    val newNote = externalRepo.createNote(localNote.name)
                    if (newNote != null) {
                        externalRepo.writeNote(newNote, content)
                        copied++
                    }
                }
            }
        }

        SyncResult(copied = copied, updated = updated)
    }

    data class SyncResult(val copied: Int, val updated: Int)
}
