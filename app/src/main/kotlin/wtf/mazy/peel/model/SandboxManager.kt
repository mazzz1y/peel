package wtf.mazy.peel.model

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import java.io.File
import wtf.mazy.peel.model.db.SandboxSlotDao
import wtf.mazy.peel.model.db.SandboxSlotEntity
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const

object SandboxManager {
    private const val NUM_OF_SANDBOXES = 8

    private lateinit var dao: SandboxSlotDao
    private var nextEvict = 0

    fun initialize(dao: SandboxSlotDao) {
        this.dao = dao
    }

    fun getContainerForUuid(uuid: String): Int? {
        return dao.getSlotForUuid(uuid)
    }

    fun releaseSandbox(context: Context, uuid: String) {
        val containerId = getContainerForUuid(uuid) ?: return
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        finishSandboxTasks(activityManager, uuid)
        killSandboxProcess(activityManager, containerId)
        clearSandboxUuid(containerId)
    }

    fun findOrAssignContainer(context: Context, uuid: String): Int {
        getContainerForUuid(uuid)?.let {
            return it
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        for (i in 0 until NUM_OF_SANDBOXES) {
            if (getSandboxUuid(i) == null) {
                saveSandboxUuid(i, uuid)
                return i
            }
        }

        val containerId = nextEvict
        nextEvict = (nextEvict + 1) % NUM_OF_SANDBOXES
        killSandboxProcess(activityManager, containerId)
        clearSandboxUuid(containerId)
        saveSandboxUuid(containerId, uuid)
        return containerId
    }

    fun saveSandboxUuid(sandboxId: Int, uuid: String) {
        dao.assign(SandboxSlotEntity(slotId = sandboxId, webappUuid = uuid))
    }

    fun getSandboxUuid(sandboxId: Int): String? {
        return dao.getUuid(sandboxId)
    }

    fun clearSandboxUuid(sandboxId: Int) {
        dao.clear(sandboxId)
    }

    fun getSandboxDataDir(uuid: String): File {
        return File(App.appContext.filesDir.parent, "app_webview_$uuid")
    }

    fun killSandboxProcess(activityManager: ActivityManager, containerId: Int) {
        val processName = ":sandbox_$containerId"
        activityManager.runningAppProcesses?.forEach { processInfo ->
            if (processInfo.processName.endsWith(processName)) {
                Process.killProcess(processInfo.pid)
            }
        }
    }

    fun killAllSandboxProcesses(activityManager: ActivityManager) {
        activityManager.runningAppProcesses?.forEach { processInfo ->
            if (processInfo.processName.contains(":sandbox_")) {
                Process.killProcess(processInfo.pid)
            }
        }
    }

    fun finishSandboxTasks(activityManager: ActivityManager, uuid: String? = null) {
        activityManager.appTasks?.forEach { task ->
            val taskUuid = task.taskInfo.baseIntent.getStringExtra(Const.INTENT_WEBAPP_UUID)
            if (taskUuid != null && (uuid == null || taskUuid == uuid)) {
                task.finishAndRemoveTask()
            }
        }
    }

    fun clearSandboxData(context: Context, uuid: String): Boolean {
        val containerId = getContainerForUuid(uuid)
        if (containerId != null) {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            finishSandboxTasks(activityManager, uuid)
            killSandboxProcess(activityManager, containerId)
            clearSandboxUuid(containerId)
        }

        return wipeSandboxStorage(uuid)
    }

    fun wipeSandboxStorage(uuid: String): Boolean {
        val sandboxDir = getSandboxDataDir(uuid)
        val deleted =
            if (sandboxDir.exists()) {
                sandboxDir.deleteRecursively()
            } else {
                true
            }

        val cacheDir = File(App.appContext.cacheDir.parent, "cache_$uuid")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }

        return deleted
    }

    fun clearAllSandboxData(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        finishSandboxTasks(activityManager)
        killAllSandboxProcesses(activityManager)

        dao.clearAll()

        val parentDir = App.appContext.filesDir.parentFile ?: return
        parentDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("app_webview_")) {
                file.deleteRecursively()
            }
        }
    }

    fun clearNonSandboxData(context: Context) {
        clearWebViewData(context)

        val dataDir = File(App.appContext.filesDir.parent, "app_webview")
        if (dataDir.exists()) {
            dataDir.deleteRecursively()
        }

        App.appContext.cacheDir?.deleteRecursively()
    }

    private fun clearWebViewData(context: Context) {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            @Suppress("DEPRECATION") WebViewDatabase.getInstance(context).clearFormData()
        } catch (_: Exception) {}
    }
}
