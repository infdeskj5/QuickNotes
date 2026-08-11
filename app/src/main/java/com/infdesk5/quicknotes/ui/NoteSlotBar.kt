package com.infdesk5.quicknotes.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.infdesk5.quicknotes.model.Note
import kotlin.math.abs

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

    private var isDragging = false
    private var draggedView: TextView? = null
    private var draggedIndex = -1
    private var initialDragIndex = -1
    private var startX = 0f
    private var touchSlop = 0
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    init {
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        isHorizontalScrollBarEnabled = false
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
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

    private fun createSpacer(): View = View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 0) }

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
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(bgColor) }
            elevation = 2f
        }

        textView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
        textView.tag = index

        textView.setOnTouchListener { v, event ->
            val view = v as TextView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    isDragging = false
                    longPressRunnable = Runnable {
                        isDragging = true
                        draggedView = view
                        draggedIndex = view.tag as Int
                        initialDragIndex = draggedIndex
                        view.elevation = 12f
                        view.bringToFront()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    if (!isDragging) {
                        if (abs(dx) > touchSlop) {
                            cancelPendingLongPress()
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener false
                        }
                    } else {
                        view.translationX = dx
                        val slotViews = getSlotViews()
                        val draggedCenter = view.left + view.translationX + view.width / 2f
                        
                        for (i in slotViews.indices) {
                            val other = slotViews[i]
                            if (other == view) continue
                            val otherCenter = other.left + other.translationX + other.width / 2f
                            
                            if (draggedIndex < i && draggedCenter > otherCenter) {
                                shiftViews(draggedIndex, i)
                                draggedIndex = i
                                break
                            } else if (draggedIndex > i && draggedCenter < otherCenter) {
                                shiftViews(i, draggedIndex)
                                draggedIndex = i
                                break
                            }
                        }
                        return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPendingLongPress()
                    if (isDragging) {
                        isDragging = false
                        view.elevation = 2f
                        view.animate().translationX(0f).setDuration(150).withEndAction {
                            for (child in getSlotViews()) child.translationX = 0f
                            if (initialDragIndex != draggedIndex) {
                                onSlotReorder?.invoke(initialDragIndex, draggedIndex)
                            }
                        }.start()
                        true
                    } else {
                        view.performClick()
                        true
                    }
                }
                else -> false
            }
        }

        textView.setOnClickListener { if (!isDragging) onSlotClick?.invoke(note) }
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

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun getSlotViews(): List<View> {
        val views = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView) views.add(child)
        }
        return views
    }

    private fun shiftViews(from: Int, to: Int) {
        val slotViews = getSlotViews()
        val slotWidth = draggedView?.width ?: 0
        val spacerWidth = dp(6)
        val shiftAmount = slotWidth + spacerWidth

        if (from < to) {
            for (i in from + 1..to) {
                val view = slotViews[i]
                if (view != draggedView) view.animate().translationX(view.translationX - shiftAmount).setDuration(150).start()
            }
        } else if (from > to) {
            for (i in to until from) {
                val view = slotViews[i]
                if (view != draggedView) view.animate().translationX(view.translationX + shiftAmount).setDuration(150).start()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
