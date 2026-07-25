package wtf.mazy.peel.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import wtf.mazy.peel.util.AppPrefs
import java.util.concurrent.TimeUnit

object ExtensionUpdateScheduler {
    private const val WORK_NAME = "extension-auto-update"
    private const val INTERVAL_HOURS = 24L

    fun apply(context: Context) {
        if (AppPrefs.isExtensionAutoUpdateEnabled(context)) enable(context) else disable(context)
    }

    private fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<ExtensionUpdateWorker>(
            INTERVAL_HOURS, TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build(),
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    private fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
