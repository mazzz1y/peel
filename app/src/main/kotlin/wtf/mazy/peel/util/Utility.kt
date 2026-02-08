package wtf.mazy.peel.util

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import androidx.core.view.size
import java.net.URLDecoder
import java.util.regex.Pattern

object Utility {
    fun setViewAndChildrenEnabled(view: View, enabled: Boolean) {
        view.isClickable = enabled
        if (enabled) {
            view.alpha = 1.0f
        } else {
            view.alpha = 0.75f
        }

        if (view is ViewGroup) {
            for (i in 0..<view.size) {
                val child = view.getChildAt(i)
                setViewAndChildrenEnabled(child, enabled)
            }
        }
    }

    @JvmStatic
    fun assert(condition: Boolean, message: String?) {
        if (!condition) {
            Log.e("Utility", "Assertion failed: $message")
        }
    }

    @JvmStatic
    fun getFileNameFromDownload(
        url: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): String? {
        var fileName: String? = null
        if (contentDisposition != null && contentDisposition != "") {
            var pattern = Pattern.compile("filename\\*=UTF-8''([^;\\s]+)", Pattern.CASE_INSENSITIVE)
            var m = pattern.matcher(contentDisposition)
            if (m.find()) {
                fileName =
                    try {
                        URLDecoder.decode(m.group(1), "UTF-8")
                    } catch (_: Exception) {
                        m.group(1)
                    }
            } else {
                pattern = Pattern.compile("filename=\"?([^\";\r\n]+)\"?", Pattern.CASE_INSENSITIVE)
                m = pattern.matcher(contentDisposition)
                if (m.find()) {
                    fileName = m.group(1)?.trim { it <= ' ' }
                }
            }
        }
        if (fileName == null) {
            fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        }

        return fileName
    }
}
