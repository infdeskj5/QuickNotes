package com.infdesk5.quicknotes.storage

import android.content.Context
import com.infdesk5.quicknotes.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalNoteRepository(private val context: Context) : NoteRepository {

    private val notesDir: File
        get() = File(context.filesDir, "quicknotes").apply { mkdirs() }

    override suspend fun listNotes(): List<Note> = withContext(Dispatchers.IO) {
        notesDir.listFiles()
            ?.filter { it.isFile && (it.extension == "txt" || it.extension == "md") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                Note(
                    id = file.nameWithoutExtension,
                    name = file.name,
                    uri = null,
                    localPath = file.absolutePath,
                    lastModified = file.lastModified()
                )
            } ?: emptyList()
    }

    override suspend fun readNote(note: Note): String? = withContext(Dispatchers.IO) {
        try {
            note.localPath?.let { File(it).readText() }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun writeNote(note: Note, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = note.localPath?.let { File(it) } ?: return@withContext false
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun createNote(name: String): Note? = withContext(Dispatchers.IO) {
        try {
            val file = File(notesDir, name)
            if (file.exists()) return@withContext null
            file.createNewFile()
            Note(
                id = file.nameWithoutExtension,
                name = file.name,
                uri = null,
                localPath = file.absolutePath,
                lastModified = file.lastModified()
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun deleteNote(note: Note): Boolean = withContext(Dispatchers.IO) {
        try {
            note.localPath?.let { File(it).delete() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun renameNote(note: Note, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val oldFile = note.localPath?.let { File(it) } ?: return@withContext false
            val newFile = File(notesDir, newName)
            if (newFile.exists()) return@withContext false
            val success = oldFile.renameTo(newFile)
            if (success) {
                note.localPath = newFile.absolutePath
                note.name = newName
                note.id = newFile.nameWithoutExtension
            }
            success
        } catch (e: Exception) {
            false
        }
    }

    override fun hasPermission(): Boolean = true // Local storage always has permission

    override fun requestPermission(callback: (Boolean) -> Unit) {
        callback(true)
    }

    suspend fun exportBackup(): String? = withContext(Dispatchers.IO) {
        try {
            val backupDir = File(context.filesDir, "backup")
            backupDir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val backupFile = File(backupDir, "quicknotes_backup_$timestamp.zip")
            // Create zip of all notes
            java.util.zip.ZipOutputStream(backupFile.outputStream()).use { zip ->
                notesDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            backupFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    suspend fun importBackup(backupPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            java.util.zip.ZipInputStream(File(backupPath).inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val file = File(notesDir, entry.name)
                    file.outputStream().use { zip.copyTo(it) }
                    entry = zip.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
