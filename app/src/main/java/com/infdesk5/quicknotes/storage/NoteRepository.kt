package com.infdesk5.quicknotes.storage

import com.infdesk5.quicknotes.model.Note

interface NoteRepository {
    suspend fun listNotes(): List<Note>
    suspend fun readNote(note: Note): String?
    suspend fun writeNote(note: Note, content: String): Boolean
    suspend fun createNote(name: String): Note?
    suspend fun deleteNote(note: Note): Boolean
    suspend fun renameNote(note: Note, newName: String): Boolean
    fun hasPermission(): Boolean
    fun requestPermission(callback: (Boolean) -> Unit)
}
