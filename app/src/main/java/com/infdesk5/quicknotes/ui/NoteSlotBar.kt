package com.infdesk5.quicknotes.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
    private var startRawX = 0f
    
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

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

    private fun getSlotViews(): List<TextView> {
        val views = mutableListOf<TextView>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView) views.add(child)
        }
        return views
    }

    private fun findSlotViewAt(localX: Float, localY: Float): TextView? {
        val touchX = localX + scrollX - container.paddingLeft
        val touchY = localY - container.paddingTop
        
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView) {
                if (touchX >= child.left && touchX <= child.right &&
                    touchY >= child.top && touchY <= child.bottom) {
                    return child
                }
            }
        }
        return null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = ev.rawX
                val slotView = findSlotViewAt(ev.x, ev.y)
                if (slotView != null) {
                    draggedView = slotView
                    draggedIndex = slotView.tag as Int
                    initialDragIndex = draggedIndex
                    
                    longPressRunnable = Runnable {
                        isDragging = true
                        draggedView?.elevation = 12f
                        draggedView?.bringToFront()
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.rawX - startRawX)
                if (dx > touchSlop) {
                    cancelPendingLongPress()
                    isDragging = false
                    draggedView = null
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                if (!isDragging) {
                    draggedView = null
                }
            }
        }
        
        if (isDragging) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isDragging) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    draggedView?.translationX = dx
                    
                    autoScrollIfNeeded(ev.rawX)
                    checkAndShiftViews()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag()
                    return true
                }
            }
            return true
        }
        
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val slotView = findSlotViewAt(ev.x, ev.y)
            if (slotView != null) {
                val noteIndex = slotView.tag as Int
                if (noteIndex < notes.size) {
                    onSlotClick?.invoke(notes[noteIndex])
                }
            }
        }
        
        return super.onTouchEvent(ev)
    }

    private fun autoScrollIfNeeded(rawX: Float) {
        val scrollEdgeThreshold = dp(40)
        val screenLocation = IntArray(2)
        this.getLocationOnScreen(screenLocation)
        val viewLeft = screenLocation[0]
        val viewRight = viewLeft + this.width
        
        if (rawX < viewLeft + scrollEdgeThreshold) {
            this.smoothScrollBy(-dp(15), 0)
        } else if (rawX > viewRight - scrollEdgeThreshold) {
            this.smoothScrollBy(dp(15), 0)
        }
    }

    private fun checkAndShiftViews() {
        val dv = draggedView ?: return
        val slotViews = getSlotViews()
        val draggedCenter = dv.left + dv.translationX + dv.width / 2f
        
        var newIndex = 0
        for (i in slotViews.indices) {
            val other = slotViews[i]
            val restingCenter = other.left + other.width / 2f
            if (draggedCenter > restingCenter) {
                newIndex = i
            } else {
                break
            }
        }
        
        if (newIndex != draggedIndex) {
            draggedIndex = newIndex
            updateShifts()
        }
    }

    private fun updateShifts() {
        val slotViews = getSlotViews()
        val slotWidth = draggedView?.width ?: 0
        val spacerWidth = dp(6)
        val shiftAmount = slotWidth + spacerWidth

        for (i in slotViews.indices) {
            val view = slotViews[i]
            if (view == draggedView) continue
            
            val origIdx = view.tag as Int
            var targetTranslation = 0f
            
            if (initialDragIndex < draggedIndex) {
                if (origIdx in (initialDragIndex + 1)..draggedIndex) {
                    targetTranslation = -shiftAmount.toFloat()
                }
            } else if (initialDragIndex > draggedIndex) {
                if (origIdx in draggedIndex until initialDragIndex) {
                    targetTranslation = shiftAmount.toFloat()
                }
            }
            
            if (view.translationX != targetTranslation) {
                view.animate().translationX(targetTranslation).setDuration(150).start()
            }
        }
    }

    private fun endDrag() {
        val dv = draggedView ?: return
        isDragging = false
        dv.elevation = 2f
        
        dv.animate()
            .translationX(0f)
            .setDuration(150)
            .withEndAction {
                for (child in getSlotViews()) {
                    child.translationX = 0f
                }
                if (initialDragIndex != draggedIndex) {
                    onSlotReorder?.invoke(initialDragIndex, draggedIndex)
                }
                draggedView = null
            }
            .start()
            
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
