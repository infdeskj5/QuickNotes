package com.infdesk5.quicknotes

import android.content.SharedPreferences

class NotePrefs(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_LAST_NOTE_URI = "last_note_uri"
        private const val KEY_TOP_INSET_PERCENT = "top_inset_percent"

        const val DEFAULT_TOP_INSET_PERCENT = 45
    }

    var treeUri: String?
        get() = prefs.getString(KEY_TREE_URI, null)
        set(value) {
            prefs.edit().putString(KEY_TREE_URI, value).apply()
        }

    var lastNoteUri: String?
        get() = prefs.getString(KEY_LAST_NOTE_URI, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_NOTE_URI, value).apply()
        }

    var topInsetPercent: Int
        get() = prefs.getInt(KEY_TOP_INSET_PERCENT, DEFAULT_TOP_INSET_PERCENT)
        set(value) {
            prefs.edit().putInt(KEY_TOP_INSET_PERCENT, value).apply()
        }

    fun clearLastNote() {
        prefs.edit().remove(KEY_LAST_NOTE_URI).apply()
    }
}
