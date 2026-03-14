package wtf.mazy.peel.ui.common

import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun <T> runWithLoader(
    activity: AppCompatActivity,
    loader: LoadingDialogController,
    showLoader: Boolean,
    @StringRes loadingRes: Int,
    ioTask: suspend () -> T,
    onResult: (T) -> Unit,
) {
    if (showLoader) loader.show(loadingRes)
    activity.lifecycleScope.launch {
        val result = try {
            withContext(Dispatchers.IO) { ioTask() }
        } catch (e: Exception) {
            Log.e("LoaderWork", "IO task failed", e)
            if (showLoader) loader.dismiss()
            return@launch
        }
        if (showLoader) loader.dismiss()
        onResult(result)
    }
}
