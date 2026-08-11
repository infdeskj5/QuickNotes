package com.infdesk5.quicknotes.ui

import android.content.ClipData
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    private var appColor: Int = 0xFF1E8E3E.toInt()
    private var onSlotClick: ((Note) -> Unit)? = null
    private var onSlotReorder: ((Int, Int) -> Unit)? = null
    private var currentNoteId: String? = null

    private var draggedIndex = -1

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

    fun setOnSlotClickListener(listener: (Note) -> Unit) { onSlotClick = listener }
    fun setOnSlotReorderListener(listener: (Int, Int) -> Unit) { onSlotReorder = listener }

    private fun rebuildSlots() {
        container.removeAllViews()
        val slotNotes = notes.take(slotCount)

        for ((index, note) in slotNotes.withIndex()) {
            val slotView = createSlotView(note, index)
            container.addView(slotView)
            if (index < slotNotes.size - 1) container.addView(createSpacer())
        }

        for (i in slotNotes.size until slotCount) {
            container.addView(createEmptySlot(i))
            if (i < slotCount - 1) container.addView(createSpacer())
        }
    }

    private fun createSpacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(6), 0)
    }

    private fun createSlotView(note: Note, index: Int): TextView {
        val textView = TextView(context).apply {
            text = note.displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(140)

            val bgColor = when {
                note.id == currentNoteId -> appColor
                note.slotColor != 0 -> note.slotColor
                else -> 0xFF333333.toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(bgColor)
            }
            elevation = 2f
        }

        textView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
        )
        textView.tag = index

        textView.setOnClickListener { onSlotClick?.invoke(note) }

        textView.setOnLongClickListener {
            draggedIndex = index
            val data = ClipData.newPlainText("index", index.toString())
            val shadow = DragShadowBuilder(textView)
            textView.startDragAndDrop(data, shadow, textView, 0)
            textView.visibility = View.INVISIBLE
            true
        }

        return textView
    }

    private fun createEmptySlot(index: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(60), dp(36))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xFF222222.toInt())
            setStroke(dp(1), 0xFF444444.toInt())
        }
        tag = index
    }

    private fun setupDragListener() {
        container.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENDED -> {
                    // Make sure all views are visible again
                    for (i in 0 until container.childCount) {
                        container.getChildAt(i)?.visibility = View.VISIBLE
                    }
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val draggedView = event.localState as? View
                    val dropIndex = getDropIndex(event.x)

                    if (draggedView != null && draggedIndex != -1 && dropIndex != -1 && draggedIndex != dropIndex) {
                        onSlotReorder?.invoke(draggedIndex, dropIndex)
                    }

                    draggedView?.visibility = View.VISIBLE
                    draggedIndex = -1
                    true
                }
                else -> true
            }
        }
    }

    private fun getDropIndex(x: Float): Int {
        val slotViews = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView) {
                slotViews.add(child)
            }
        }

        for (i in slotViews.indices) {
            val view = slotViews[i]
            val centerX = view.left + view.width / 2f
            if (x < centerX) {
                return i
            }
        }
        return slotViews.size - 1
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
