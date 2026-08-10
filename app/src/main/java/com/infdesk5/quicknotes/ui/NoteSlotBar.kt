package com.infdesk5.quicknotes.ui

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.infdesk5.quicknotes.R
import com.infdesk5.quicknotes.model.Note

class NoteSlotBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private var notes: List<Note> = emptyList()
    private var slotCount: Int = 5
    private var appColor: Int = 0xFF00FF7F.toInt()
    private var onSlotClick: ((Note) -> Unit)? = null
    private var onSlotLongClick: ((Note, Int) -> Unit)? = null
    private var onSlotReorder: ((Int, Int) -> Unit)? = null
    private var currentNoteId: String? = null

    init {
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        isHorizontalScrollBarEnabled = false
        setupDragListener()
    }

    fun setNotes(notes: List<Note>, slotCount: Int, appColor: Int, currentNoteId: String?) {
        this.notes = notes
        this.slotCount = slotCount
        this.appColor = appColor
        this.currentNoteId = currentNoteId
        rebuildSlots()
    }

    fun setOnSlotClickListener(listener: (Note) -> Unit) {
        onSlotClick = listener
    }

    fun setOnSlotLongClickListener(listener: (Note, Int) -> Unit) {
        onSlotLongClick = listener
    }

    fun setOnSlotReorderListener(listener: (Int, Int) -> Unit) {
        onSlotReorder = listener
    }

    private fun rebuildSlots() {
        container.removeAllViews()

        val slotNotes = notes.take(slotCount)

        for ((index, note) in slotNotes.withIndex()) {
            val slotView = createSlotView(note, index)
            container.addView(slotView)

            if (index < slotNotes.size - 1) {
                val spacer = View(context)
                container.addView(spacer, LinearLayout.LayoutParams(dp(6), 0))
            }
        }

        // Add empty slots if fewer notes than slotCount
        for (i in slotNotes.size until slotCount) {
            val emptySlot = createEmptySlot(i)
            container.addView(emptySlot)
            if (i < slotCount - 1) {
                val spacer = View(context)
                container.addView(spacer, LinearLayout.LayoutParams(dp(6), 0))
            }
        }
    }

    private fun createSlotView(note: Note, index: Int): View {
        val textView = TextView(context).apply {
            text = note.displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(140)

            val bgColor = if (note.id == currentNoteId) {
                appColor
            } else if (note.slotColor != 0) {
                note.slotColor
            } else {
                0xFF333333.toInt()
            }

            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(bgColor)
            }

            elevation = if (note.id == currentNoteId) 4f else 2f
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        )
        textView.layoutParams = params

        textView.setOnClickListener { onSlotClick?.invoke(note) }
        textView.setOnLongClickListener {
            onSlotLongClick?.invoke(note, index)
            startDrag(note, index, textView)
            true
        }

        textView.tag = index
        return textView
    }

    private fun createEmptySlot(index: Int): View {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(dp(60), dp(36))
        view.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xFF222222.toInt())
            setStroke(dp(1), 0xFF444444.toInt())
        }
        view.tag = index
        return view
    }

    private fun startDrag(note: Note, index: Int, view: View) {
        val data = ClipData.newPlainText("slot_index", index.toString())
        val shadowBuilder = DragShadowBuilder(view)
        view.startDragAndDrop(data, shadowBuilder, index, 0)
    }

    private fun setupDragListener() {
        container.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DROP -> {
                    val fromIndex = event.localState as? Int ?: return@setOnDragListener true
                    val toIndex = getSlotIndexAtPosition(event.x)
                    if (fromIndex != toIndex && toIndex >= 0) {
                        onSlotReorder?.invoke(fromIndex, toIndex)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun getSlotIndexAtPosition(x: Float): Int {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView && x >= child.left && x <= child.right) {
                return child.tag as? Int ?: -1
            }
        }
        return -1
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
