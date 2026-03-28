package wtf.mazy.peel.util

import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan

fun String.withMonoSpan(arg: String): SpannableString {
    val spannable = SpannableString(this)
    val start = indexOf(arg)
    if (start >= 0) {
        spannable.setSpan(
            TypefaceSpan("monospace"),
            start, start + arg.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return spannable
}
