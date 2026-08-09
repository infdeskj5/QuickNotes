package com.infdesk5.quicknotes

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ListView

class MaxHeightListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ListView(context, attrs, defStyleAttr) {

    var maxHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limitedHeightMeasureSpec = if (maxHeight > 0) {
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }

        super.onMeasure(widthMeasureSpec, limitedHeightMeasureSpec)
    }
}
