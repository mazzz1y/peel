package wtf.mazy.peel.ui.dialog

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import wtf.mazy.peel.R

class DialogChoiceRow(container: LinearLayout, @StringRes labelRes: Int, @DimenRes topMargin: Int) {
    val view: View = LayoutInflater.from(container.context)
        .inflate(R.layout.view_dialog_choice_row, container, false)
    val button: MaterialButton = view.requireViewById(R.id.choiceButton)

    init {
        view.requireViewById<TextView>(R.id.choiceLabel).setText(labelRes)
        (view.layoutParams as LinearLayout.LayoutParams).topMargin =
            container.resources.getDimensionPixelSize(topMargin)
        container.addView(view)
    }
}
