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
    private var slotMaxChars: Int = 0

    var onScrollChangedListener: ((Int) -> Unit)? = null

    // Drag state
    private var draggedIndex = -1
    private var draggedTag = -1
    private var isDragging = false
    private var dropHandled = false

    // Live visual reorder state
    private val slotViews = mutableMapOf<Int, View>()
    private val visualOrder = mutableListOf<Int>()
    private val desiredLeftByTag = mutableMapOf<Int, Int>()

    // Edge auto-scroll state
    private var lastDragViewportX = 0f
    private var edgeScrollDirection = 0
    private var edgeScrollSpeed = 0
    private var edgeScrollRunnable: Runnable? = null

    private val edgeSize = dp(48)
    private val maxEdgeScrollSpeed = dp(12)
    private val animationDuration = 120L

    init {
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        isHorizontalScrollBarEnabled = false
        setupDragListener()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedListener?.invoke(l)
    }

    fun setNotes(
        notes: List<Note>,
        slotCount: Int,
        appColor: Int,
        currentNoteId: String?,
        slotMaxChars: Int = 0
    ) {
        stopEdgeScroll()
        resetDragStateInternal()

        this.notes = notes
        this.slotCount = slotCount
        this.appColor = appColor
        this.currentNoteId = currentNoteId
        this.slotMaxChars = slotMaxChars

        rebuildSlots()
    }

    fun setOnSlotClickListener(listener: (Note) -> Unit) {
        onSlotClick = listener
    }

    fun setOnSlotReorderListener(listener: (Int, Int) -> Unit) {
        onSlotReorder = listener
    }

    private fun rebuildSlots() {
        container.removeAllViews()

        slotViews.clear()
        visualOrder.clear()
        desiredLeftByTag.clear()

        val slotNotes = notes.take(slotCount)

        for (i in 0 until slotCount) {
            val view = if (i < slotNotes.size) {
                createSlotView(slotNotes[i], i)
            } else {
                createEmptySlot(i)
            }

            slotViews[i] = view
            visualOrder.add(i)

            container.addView(view)

            if (i < slotCount - 1) {
                container.addView(createSpacer())
            }
        }

        post {
            applyVisualOrder(false)
        }
    }

    private fun createSpacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(6), 0)
    }

    private fun createSlotView(note: Note, index: Int): TextView {
        val textView = TextView(context).apply {
            text = formatSlotName(note.displayName)

            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))

            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(140)

            translationX = 0f

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
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        )

        textView.tag = index

        textView.setOnClickListener {
            onSlotClick?.invoke(note)
        }

        textView.setOnLongClickListener {
            if (isDragging) return@setOnLongClickListener false

            draggedIndex = index
            draggedTag = index
            isDragging = true
            dropHandled = false

            val data = ClipData.newPlainText("index", index.toString())
            val shadow = DragShadowBuilder(textView)

            val started = textView.startDragAndDrop(data, shadow, textView, 0)

            if (started) {
                textView.visibility = View.INVISIBLE
            } else {
                draggedIndex = -1
                draggedTag = -1
                isDragging = false
                dropHandled = false
            }

            true
        }

        return textView
    }

    private fun createEmptySlot(index: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(60), dp(36))

        translationX = 0f

        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xFF222222.toInt())
            setStroke(dp(1), 0xFF444444.toInt())
        }

        tag = index
    }

    private fun setupDragListener() {
        setOnDragListener { _, event ->
            handleDragEvent(event)
        }
    }

    private fun handleDragEvent(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                return isDragging || draggedIndex != -1
            }

            DragEvent.ACTION_DRAG_LOCATION -> {
                if (!isDragging) return false

                handleDragLocation(event.x)
                return true
            }

            DragEvent.ACTION_DROP -> {
                if (!isDragging) return false

                stopEdgeScroll()

                dropHandled = true

                val finalTarget = visualOrder.indexOf(draggedTag)

                if (
                    draggedIndex != -1 &&
                    finalTarget != -1 &&
                    finalTarget != draggedIndex
                ) {
                    onSlotReorder?.invoke(draggedIndex, finalTarget)
                } else {
                    resetVisualState()
                    dropHandled = true
                }

                isDragging = false

                return true
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                stopEdgeScroll()

                if (!dropHandled) {
                    resetVisualState()
                } else {
                    draggedIndex = -1
                    draggedTag = -1
                    isDragging = false
                    dropHandled = false
                }

                return true
            }

            else -> return true
        }
    }

    private fun handleDragLocation(viewportX: Float) {
        if (!isDragging || draggedTag == -1 || width <= 0) return

        lastDragViewportX = viewportX.coerceIn(0f, width.toFloat())

        val contentX = lastDragViewportX + scrollX

        val targetTag = getClosestSlotTag(contentX)

        if (targetTag != -1 && targetTag != draggedTag) {
            moveVisualSlot(draggedTag, targetTag)
        }

        updateEdgeScroll(lastDragViewportX)
    }

    private fun getClosestSlotTag(contentX: Float): Int {
        var closest = -1
        var minDistance = Float.MAX_VALUE

        for (tag in visualOrder) {
            if (tag == draggedTag) continue

            val view = slotViews[tag] ?: continue

            val left = desiredLeftByTag[tag] ?: view.left
            val centerX = left + view.width / 2f

            val distance = abs(contentX - centerX)

            if (distance < minDistance) {
                minDistance = distance
                closest = tag
            }
        }

        return closest
    }

    private fun moveVisualSlot(dragged: Int, target: Int) {
        val from = visualOrder.indexOf(dragged)
        val to = visualOrder.indexOf(target)

        if (from == -1 || to == -1 || from == to) return

        visualOrder.removeAt(from)
        visualOrder.add(to, dragged)

        applyVisualOrder(true)
    }

    private fun applyVisualOrder(animate: Boolean) {
        if (visualOrder.isEmpty()) return

        // If views are not laid out yet, do not calculate translations.
        if (slotViews.values.any { it.width == 0 }) return

        var cursor = container.paddingLeft
        val spacerWidth = dp(6)

        for ((position, tag) in visualOrder.withIndex()) {
            val view = slotViews[tag] ?: continue

            desiredLeftByTag[tag] = cursor

            val translation = (cursor - view.left).toFloat()

            if (animate) {
                view.animate().cancel()
                view.animate()
                    .translationX(translation)
                    .setDuration(animationDuration)
                    .start()
            } else {
                view.translationX = translation
            }

            cursor += view.width

            if (position < visualOrder.size - 1) {
                cursor += spacerWidth
            }
        }
    }

    private fun updateEdgeScroll(viewportX: Float) {
        if (!isDragging || width <= 0) {
            stopEdgeScroll()
            return
        }

        val maxScrollX = maxOf(0, container.width - width)
        val edge = edgeSize

        if (viewportX < edge && scrollX > 0) {
            val distance = viewportX.coerceIn(0f, edge.toFloat())
            val fraction = 1f - (distance / edge)

            edgeScrollDirection = -1
            edgeScrollSpeed = (maxEdgeScrollSpeed * fraction).toInt().coerceAtLeast(2)
        } else if (viewportX > width - edge && scrollX < maxScrollX) {
            val distance = (width - viewportX).coerceIn(0f, edge.toFloat())
            val fraction = 1f - (distance / edge)

            edgeScrollDirection = 1
            edgeScrollSpeed = (maxEdgeScrollSpeed * fraction).toInt().coerceAtLeast(2)
        } else {
            stopEdgeScroll()
            return
        }

        startEdgeScrollIfNeeded()
    }

    private fun startEdgeScrollIfNeeded() {
        if (edgeScrollRunnable != null) return

        val runnable = object : Runnable {
            override fun run() {
                if (!isDragging || edgeScrollDirection == 0) {
                    edgeScrollRunnable = null
                    return
                }

                scrollBy(edgeScrollDirection * edgeScrollSpeed, 0)

                handleDragLocation(lastDragViewportX)

                if (isDragging && edgeScrollDirection != 0) {
                    postDelayed(this, 16)
                } else {
                    edgeScrollRunnable = null
                }
            }
        }

        edgeScrollRunnable = runnable
        post(runnable)
    }

    private fun stopEdgeScroll() {
        edgeScrollDirection = 0
        edgeScrollSpeed = 0

        edgeScrollRunnable?.let {
            removeCallbacks(it)
        }

        edgeScrollRunnable = null
    }

    private fun resetVisualState() {
        stopEdgeScroll()

        visualOrder.clear()
        visualOrder.addAll(0 until slotCount)

        desiredLeftByTag.clear()

        slotViews[draggedTag]?.visibility = View.VISIBLE

        applyVisualOrder(true)

        draggedIndex = -1
        draggedTag = -1
        isDragging = false
        dropHandled = false
    }

    private fun resetDragStateInternal() {
        stopEdgeScroll()

        draggedIndex = -1
        draggedTag = -1
        isDragging = false
        dropHandled = false
    }

    private fun formatSlotName(name: String): String {
        if (slotMaxChars <= 0) return name

        // Short names are not affected.
        if (name.length <= 4) return name

        // No need to truncate if it already fits.
        if (name.length <= slotMaxChars) return name

        return name.take(slotMaxChars) + "…"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
