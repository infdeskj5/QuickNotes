package com.infdesk5.quicknotes

class UndoRedoManager(
    private val maxSteps: Int = 80,
    private val groupMs: Long = 500L
) {

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    private var lastPushTime = 0L

    var onAvailabilityChanged: (() -> Unit)? = null

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun beforeUserTextChanged(oldText: String, count: Int, after: Int) {
        if (redoStack.isNotEmpty()) {
            redoStack.clear()
            notifyChanged()
        }

        pushSnapshot(oldText, count, after)
    }

    fun undo(currentText: String): String? {
        if (!canUndo) {
            return null
        }

        val previous = undoStack.removeLast()
        addRedo(currentText)

        lastPushTime = 0L
        notifyChanged()

        return previous
    }

    fun redo(currentText: String): String? {
        if (!canRedo) {
            return null
        }

        val next = redoStack.removeLast()
        addUndo(next)

        lastPushTime = 0L
        notifyChanged()

        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastPushTime = 0L
        notifyChanged()
    }

    private fun pushSnapshot(oldText: String, count: Int, after: Int) {
        val now = System.currentTimeMillis()
        val isLargeChange = count > 1 || after > 1
        val last = undoStack.lastOrNull()

        if (last == oldText && !isLargeChange) {
            return
        }

        if (undoStack.isEmpty() || isLargeChange || now - lastPushTime > groupMs) {
            addUndo(oldText)
            lastPushTime = now
        }
    }

    private fun addUndo(text: String) {
        if (undoStack.isNotEmpty() && undoStack.last() == text) {
            return
        }

        undoStack.addLast(text)

        while (undoStack.size > maxSteps) {
            undoStack.removeFirst()
        }

        notifyChanged()
    }

    private fun addRedo(text: String) {
        if (redoStack.isNotEmpty() && redoStack.last() == text) {
            return
        }

        redoStack.addLast(text)

        while (redoStack.size > maxSteps) {
            redoStack.removeFirst()
        }

        notifyChanged()
    }

    private fun notifyChanged() {
        onAvailabilityChanged?.invoke()
    }
}
