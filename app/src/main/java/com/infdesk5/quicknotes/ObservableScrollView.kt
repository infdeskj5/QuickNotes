package com.infdesk5.quicknotes

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class ObservableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var onScrollChangedListener: ((scrollY: Int, maxScroll: Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        notifyScrollChanged()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        notifyScrollChanged()
    }

    fun getMaxScroll(): Int {
        val child = getChildAt(0) ?: return 0
        return maxOf(0, child.height - height)
    }

    private fun notifyScrollChanged() {
        onScrollChangedListener?.invoke(scrollY, getMaxScroll())
    }
}
