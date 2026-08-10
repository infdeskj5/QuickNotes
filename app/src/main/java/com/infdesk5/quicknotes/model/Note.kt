package com.infdesk5.quicknotes.model

import android.net.Uri

data class Note(
    val id: String,
    var name: String,
    var uri: Uri?,
    var localPath: String? = null,
    var content: String = "",
    var lastModified: Long = System.currentTimeMillis(),
    var slotIndex: Int = -1,
    var slotColor: Int = 0
) {
    val displayName: String
        get() = name.removeSuffix(".txt").removeSuffix(".md")

    companion object {
        fun generateId(): String = System.currentTimeMillis().toString(36) + 
            (0..5).map { ('a'..'z').random() }.joinToString("")
    }
}
