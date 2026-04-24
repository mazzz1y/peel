package wtf.mazy.peel.util

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

fun CharSequence.withMonoSpan(arg: String): SpannableString =
    applySpan(arg) { TypefaceSpan("monospace") }

fun CharSequence.withBoldSpan(arg: String): SpannableString =
    applySpan(arg) { StyleSpan(Typeface.BOLD) }

private inline fun CharSequence.applySpan(arg: String, span: () -> Any): SpannableString {
    val spannable = if (this is SpannableString) this else SpannableString(this)
    val start = spannable.indexOf(arg)
    if (start >= 0) {
        spannable.setSpan(
            span(), start, start + arg.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return spannable
}
