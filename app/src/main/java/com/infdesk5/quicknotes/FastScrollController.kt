package com.infdesk5.quicknotes

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

class FastScrollController(
    private val scrollView: ObservableScrollView,
    private val fastScroller: View,
    private val thumb: View
) {

    fun setup() {
        fastScroller.setOnTouchListener { _, event ->
            val maxScroll = scrollView.getMaxScroll()

            if (maxScroll <= 0) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    val thumbHeight = thumb.height
                    val trackHeight = fastScroller.height - thumbHeight

                    if (trackHeight <= 0) {
                        return@setOnTouchListener false
                    }

                    val y = (event.y - thumbHeight / 2f)
                        .coerceIn(0f, trackHeight.toFloat())

                    val fraction = y / trackHeight
                    val targetScroll = (fraction * maxScroll).toInt()

                    scrollView.scrollTo(0, targetScroll)
                    true
                }

                else -> false
            }
        }
    }

    fun setTopMargin(margin: Int) {
        val layoutParams = fastScroller.layoutParams as? FrameLayout.LayoutParams
        if (layoutParams != null) {
            layoutParams.topMargin = margin
            fastScroller.layoutParams = layoutParams
        }
    }

    fun update(scrollY: Int, maxScroll: Int) {
        if (maxScroll <= 0) {
            fastScroller.visibility = View.INVISIBLE
            return
        }

        val thumbHeight = thumb.height
        val trackHeight = fastScroller.height - thumbHeight

        if (trackHeight <= 0) {
            fastScroller.visibility = View.INVISIBLE
            return
        }

        fastScroller.visibility = View.VISIBLE

        val fraction = scrollY.toFloat() / maxScroll.toFloat()
        val thumbY = (fraction * trackHeight).coerceIn(0f, trackHeight.toFloat())

        thumb.y = thumbY
    }
}
