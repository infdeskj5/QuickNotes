package com.infdesk5.quicknotes.ui

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
        layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(LayoutTransition.CHANGING, 150)
        }
    }

    private var notes: List<Note> = emptyList()
    private var slotCount: Int = 5
    private var appColor: Int = 0xFF1E8E3E.toInt()
    private var onSlotClick: ((Note) -> Unit)? = null
    private var onSlotReorder: ((Int, Int) -> Unit)? = null
    private var currentNoteId: String? = null

    // Drag state
    private var isDragging = false
    private var draggedView: TextView? = null
    private var draggedIndex = -1
    private var currentDragIndex = -1
    private var initialRawX = 0f
    private var draggedViewInitialLeft = 0f
    private var longPressRunnable: Runnable? = null
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingDragIndex = -1
    private var pendingDragView: TextView? = null

    init {
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        isHorizontalScrollBarEnabled = false
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
            if (index < slotNotes.size - 1) {
                container.addView(createSpacer())
            }
        }

        for (i in slotNotes.size until slotCount) {
            container.addView(createEmptySlot(i))
            if (i < slotCount - 1) {
                container.addView(createSpacer())
            }
        }
    }

    private fun createSpacer(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), 0)
        }
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

        // Touch handling for drag
        textView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pendingDragIndex = index
                    pendingDragView = v as TextView
                    initialRawX = event.rawX
                    draggedViewInitialLeft = v.left.toFloat()

                    longPressRunnable = Runnable {
                        startDragMode()
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, 400)
                    false // Allow click to still work if no long press
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) {
                        val dx = abs(event.rawX - initialRawX)
                        if (dx > dp(10)) {
                            cancelPendingLongPress() // Renamed here
                        }
                    }
                    if (isDragging) {
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    cancelPendingLongPress() // Renamed here
                    if (isDragging) {
                        endDrag()
                        true
                    } else {
                        false // Let click listener handle it
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingLongPress() // Renamed here
                    if (isDragging) {
                        endDrag()
                    }
                    true
                }

                else -> false
            }
        }

        textView.setOnClickListener {
            if (!isDragging) {
                onSlotClick?.invoke(note)
            }
        }

        return textView
    }

    private fun createEmptySlot(index: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(36))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF222222.toInt())
                setStroke(dp(1), 0xFF444444.toInt())
            }
            tag = index
        }
    }

    private fun startDragMode() {
        val view = pendingDragView ?: return
        isDragging = true
        draggedView = view
        draggedIndex = pendingDragIndex
        currentDragIndex = pendingDragIndex
        view.elevation = 8f
        requestDisallowInterceptTouchEvent(true)
    }

    // Renamed from cancelLongPress to avoid conflict with View.cancelLongPress()
    private fun cancelPendingLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isDragging) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isDragging) {
            when (ev.action) {
                MotionEvent.ACTION_MOVE -> handleDragMove(ev)
                MotionEvent.ACTION_UP -> endDrag()
                MotionEvent.ACTION_CANCEL -> endDrag()
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun handleDragMove(event: MotionEvent) {
        val dv = draggedView ?: return
        val dx = event.rawX - initialRawX
        dv.translationX = dx

        val draggedCenterX = draggedViewInitialLeft + dx + dv.width / 2f
        val targetIdx = calculateTargetIndex(draggedCenterX)

        if (targetIdx != currentDragIndex && targetIdx >= 0) {
            shiftViews(currentDragIndex, targetIdx, dv)
            currentDragIndex = targetIdx
        }
    }

    private fun calculateTargetIndex(centerX: Float): Int {
        val slotViews = getSlotViews()
        if (slotViews.isEmpty()) return 0

        for (i in slotViews.indices) {
            val view = slotViews[i]
            if (view == draggedView) continue
            val viewCenterX = view.left + view.translationX + view.width / 2f
            if (i > currentDragIndex && centerX < viewCenterX) return i
            if (i < currentDragIndex && centerX > viewCenterX) return i
        }

        // Check boundaries
        val firstView = slotViews.firstOrNull()
        val lastView = slotViews.lastOrNull()
        if (firstView != null && centerX < firstView.left + firstView.width / 2f) return 0
        if (lastView != null && centerX > lastView.left + lastView.width / 2f) return slotViews.size - 1

        return currentDragIndex
    }

    private fun getSlotViews(): List<View> {
        val views = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView) {
                views.add(child)
            }
        }
        return views
    }

    private fun shiftViews(from: Int, to: Int, dv: TextView) {
        val slotViews = getSlotViews()
        val dvWidth = dv.width + dp(6)

        if (from < to) {
            for (i in (from + 1)..minOf(to, slotViews.size - 1)) {
                val view = slotViews[i]
                if (view != dv) {
                    view.animate()
                        .translationX(view.translationX - dvWidth)
                        .setDuration(150)
                        .start()
                }
            }
        } else if (from > to) {
            for (i in maxOf(0, to) until from) {
                val view = slotViews[i]
                if (view != dv) {
                    view.animate()
                        .translationX(view.translationX + dvWidth)
                        .setDuration(150)
                        .start()
                }
            }
        }
    }

    private fun endDrag() {
        val dv = draggedView ?: return

        val targetX = if (currentDragIndex != draggedIndex) {
            val slotViews = getSlotViews()
            val targetView = slotViews.getOrNull(currentDragIndex)
            if (targetView != null && targetView != dv) {
                (targetView.left + targetView.translationX - dv.left).toFloat()
            } else {
                dv.translationX
            }
        } else {
            0f
        }

        dv.animate()
            .translationX(targetX)
            .setDuration(150)
            .withEndAction {
                for (i in 0 until container.childCount) {
                    container.getChildAt(i)?.translationX = 0f
                }
                dv.elevation = 2f
                isDragging = false
                draggedView = null
                requestDisallowInterceptTouchEvent(false)

                if (draggedIndex != currentDragIndex) {
                    onSlotReorder?.invoke(draggedIndex, currentDragIndex)
                }
            }
            .start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
