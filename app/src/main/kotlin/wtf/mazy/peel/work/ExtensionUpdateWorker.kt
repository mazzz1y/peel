package wtf.mazy.peel.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager

class ExtensionUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DataManager.instance.awaitReady()
        return runCatching {
            GeckoRuntimeProvider.updateAllExtensions(applicationContext)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
