package com.infdesk5.quicknotes

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteUtils {

    fun newNoteName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "note-$stamp.txt"
    }

    fun buildNewName(raw: String, originalName: String): String {
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
}
