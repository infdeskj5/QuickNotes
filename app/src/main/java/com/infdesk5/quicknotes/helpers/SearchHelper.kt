package com.infdesk5.quicknotes.helpers

import android.text.Spannable
import android.text.TextWatcher
import android.text.Editable
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import com.infdesk5.quicknotes.ObservableScrollView
import com.infdesk5.quicknotes.R
import com.infdesk5.quicknotes.model.Note
import com.infdesk5.quicknotes.storage.NoteManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope

class SearchHelper(
    private val activity: ComponentActivity,
    private val editText: EditText,
    private val noteScroll: ObservableScrollView,
    private val searchBar: LinearLayout,
    private val searchInput: EditText,
    private val noteSlotBar: View,
    private val noteManager: NoteManager,
    private val keyboardHelper: KeyboardHelper,
    private val getNotes: suspend () -> List<Note>,
    private val openNote: suspend (Note) -> Unit,
    private val toast: (String) -> Unit
) {
    var searchMatches = mutableListOf<Int>()
    var currentSearchIndex = 0
    var currentSearchQuery = ""

    val isSearchActive: Boolean
        get() = searchBar.visibility == View.VISIBLE

    fun setup() {
        activity.findViewById<ImageButton>(R.id.search_prev).setOnClickListener { navigate(-1) }
        activity.findViewById<ImageButton>(R.id.search_next).setOnClickListener { navigate(1) }
        activity.findViewById<ImageButton>(R.id.search_close).setOnClickListener { hide() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performInNoteSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    fun show() {
        searchBar.visibility = View.VISIBLE
        noteSlotBar.visibility = View.GONE
        searchInput.requestFocus()
        keyboardHelper.showKeyboard(searchInput)
    }

    fun hide() {
        searchBar.visibility = View.GONE
        noteSlotBar.visibility = View.VISIBLE
        searchInput.text.clear()
        clearHighlights()
        keyboardHelper.hideKeyboard(searchInput)
        editText.requestFocus()
    }

    fun performInNoteSearch(query: String) {
        clearHighlights()
        searchMatches.clear()
        currentSearchIndex = 0
        currentSearchQuery = query

        if (query.length < 2) return

        val text = editText.text.toString()
        var index = text.indexOf(query, 0, true)
        while (index >= 0) {
            searchMatches.add(index)
            index = text.indexOf(query, index + query.length, true)
        }

        highlightAll(query)
        if (searchMatches.isNotEmpty()) navigate(0)
    }

    private fun highlightAll(query: String) {
        val spannable = editText.text as? Spannable ?: return
        val normalColor = noteManager.searchHighlightColor
        for (matchIndex in searchMatches) {
            spannable.setSpan(
                BackgroundColorSpan(normalColor),
                matchIndex, matchIndex + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun highlightCurrent() {
        val spannable = editText.text as? Spannable ?: return
        if (searchMatches.isEmpty() || currentSearchIndex >= searchMatches.size) return

        val currentColor = noteManager.searchCurrentHighlightColor
        val normalColor = noteManager.searchHighlightColor

        for ((i, matchIndex) in searchMatches.withIndex()) {
            val color = if (i == currentSearchIndex) currentColor else normalColor
            spannable.setSpan(
                BackgroundColorSpan(color),
                matchIndex, matchIndex + currentSearchQuery.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    fun clearHighlights() {
        val spannable = editText.text as? Spannable ?: return
        spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java)
            .forEach { spannable.removeSpan(it) }
    }

    private fun navigate(direction: Int) {
        if (searchMatches.isEmpty()) return

        currentSearchIndex = when {
            direction > 0 -> (currentSearchIndex + 1) % searchMatches.size
            direction < 0 -> if (currentSearchIndex <= 0) searchMatches.size - 1 else currentSearchIndex - 1
            else -> currentSearchIndex
        }

        highlightCurrent()

        val position = searchMatches[currentSearchIndex]
        editText.setSelection(position, position + currentSearchQuery.length)

        editText.post {
            editText.bringPointIntoView(position)
            val layout = editText.layout ?: return@post
            val line = layout.getLineForOffset(position)
            val lineTop = layout.getLineTop(line)
            val editTextTop = editText.top
            val searchBarHeight = if (searchBar.visibility == View.VISIBLE) searchBar.height else 0
            val visibleHeight = noteScroll.height - searchBarHeight
            val targetY = editTextTop + lineTop - (visibleHeight / 3)
            noteScroll.smoothScrollTo(0, targetY.coerceAtLeast(0))
        }
    }

    fun showCrossNoteSearch() {
        val input = EditText(activity)
        input.hint = activity.getString(R.string.search_all_notes)

        BottomDialogHelper.show(
            activity,
            activity.getString(R.string.search_all_notes),
            BottomDialogHelper.wrapInPadding(activity, input),
            activity.getString(R.string.ok)
        ) {
            val query = input.text.toString().trim()
            if (query.isNotEmpty()) performCrossNoteSearch(query)
        }

        input.post {
            input.requestFocus()
            keyboardHelper.showKeyboard(input)
        }
    }

    private fun performCrossNoteSearch(query: String) {
        activity.lifecycleScope.launch {
            val notes = getNotes()
            val results = mutableListOf<Triple<Note, String, Int>>()

            for (note in notes) {
                val content = noteManager.readNote(note) ?: continue
                val lowerContent = content.lowercase()
                val lowerQuery = query.lowercase()
                var index = lowerContent.indexOf(lowerQuery)
                while (index >= 0) {
                    val start = maxOf(0, index - 30)
                    val end = minOf(content.length, index + query.length + 30)
                    val snippet = (if (start > 0) "…" else "") +
                            content.substring(start, end) +
                            (if (end < content.length) "…" else "")
                    results.add(Triple(note, snippet, index))
                    index = lowerContent.indexOf(lowerQuery, index + query.length)
                }
            }

            if (results.isEmpty()) {
                toast(activity.getString(R.string.no_search_results))
                return@launch
            }

            val displayTexts = results.map { "📝 ${it.first.displayName}\n   ${it.second}" }.toTypedArray()

            BottomDialogHelper.showSimple(
                activity,
                activity.getString(R.string.search_results, results.size, results.map { it.first }.distinct().size),
                displayTexts
            ) { which ->
                val (note, _, matchIndex) = results[which]
                activity.lifecycleScope.launch {
                    openNote(note)
                    delay(200)
                    show()
                    searchInput.setText(query)
                    delay(200)
                    val targetIdx = searchMatches.indexOfFirst { it >= matchIndex }
                    if (targetIdx != -1) {
                        currentSearchIndex = targetIdx
                        highlightCurrent()
                        val pos = searchMatches[currentSearchIndex]
                        editText.setSelection(pos, pos + query.length)
                        editText.post {
                            editText.bringPointIntoView(pos)
                            val layout = editText.layout ?: return@post
                            val line = layout.getLineForOffset(pos)
                            val lineTop = layout.getLineTop(line)
                            val editTextTop = editText.top
                            val searchBarH = if (searchBar.visibility == View.VISIBLE) searchBar.height else 0
                            val visibleH = noteScroll.height - searchBarH
                            val targetY = editTextTop + lineTop - (visibleH / 3)
                            noteScroll.smoothScrollTo(0, targetY.coerceAtLeast(0))
                        }
                    }
                }
            }
        }
    }
}
