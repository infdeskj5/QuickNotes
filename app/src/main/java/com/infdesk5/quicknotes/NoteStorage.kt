package com.infdesk5.quicknotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class NoteStorage(
    private val context: Context,
    private val prefs: NotePrefs
) {

    companion object {
        private const val MIME_TEXT = "text/plain"
    }

    fun getTreeUri(): Uri? {
        return prefs.treeUri?.let(Uri::parse)
    }

    fun hasTreePermission(): Boolean {
        val treeUri = getTreeUri() ?: return false

        return context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
    }

    fun takePersistablePermission(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getTree(): DocumentFile? {
        return getTreeUri()?.let { DocumentFile.fromTreeUri(context, it) }
    }

    suspend fun exists(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                DocumentFile.fromSingleUri(context, uri)?.exists() == true
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun findFile(tree: DocumentFile, name: String): DocumentFile? {
        return withContext(Dispatchers.IO) {
            try {
                tree.findFile(name)
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun createFile(tree: DocumentFile, name: String): DocumentFile? {
        return withContext(Dispatchers.IO) {
            try {
                tree.createFile(MIME_TEXT, name)
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun listNotes(tree: DocumentFile): List<DocumentFile> {
        return withContext(Dispatchers.IO) {
            try {
                tree.listFiles()
                    .filter { it.isFile && isTextFile(it) }
                    .sortedWith(
                        compareByDescending<DocumentFile> { it.lastModified() }
                            .thenByDescending { it.name?.lowercase(Locale.US) ?: "" }
                    )
            } catch (e: Exception) {
                emptyList<DocumentFile>()
            }
        }
    }

    fun readText(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun writeText(uri: Uri, text: String): Boolean {
        return try {
            val outputStream = try {
                context.contentResolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                context.contentResolver.openOutputStream(uri)
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

    private fun isTextFile(file: DocumentFile): Boolean {
        val name = file.name?.lowercase(Locale.US) ?: return false

        return name.endsWith(".txt") ||
                name.endsWith(".md") ||
                file.type?.startsWith("text/") == true
    }
}
