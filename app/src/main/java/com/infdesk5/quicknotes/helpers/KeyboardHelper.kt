package com.infdesk5.quicknotes.helpers

import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class KeyboardHelper(private val activity: ComponentActivity) {

    private val imm: InputMethodManager
        get() = activity.getSystemService(ComponentActivity.INPUT_METHOD_SERVICE) as InputMethodManager

    fun showKeyboard(view: EditText) {
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun showKeyboardForced(view: EditText) {
        imm.showSoftInput(view, InputMethodManager.SHOW_FORCED)
    }

    fun hideKeyboard(view: EditText) {
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun isKeyboardVisible(rootLayout: View): Boolean {
        return ViewCompat.getRootWindowInsets(rootLayout)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
}
