package wtf.mazy.peel.model

import android.app.ActivityManager
import android.app.Application
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

    var activeSuffix: String? = null
        private set

    fun initialize(dao: SandboxSlotDao) {
        this.dao = dao
    }

    val isInSandboxProcess: Boolean
        get() = Application.getProcessName() != App.appContext.packageName

    val currentSlotId: Int?
        get() = Application.getProcessName().substringAfterLast(":sandbox_", "").toIntOrNull()

    fun initDataDirectorySuffix(uuid: String): Boolean {
        return try {
            android.webkit.WebView.setDataDirectorySuffix(uuid)
            activeSuffix = uuid
            currentSlotId?.let { writeSlotMapping(it, uuid) }
            true
        } catch (_: IllegalStateException) {
            activeSuffix == uuid
        }
    }

    fun findOrAssignContainer(context: Context, uuid: String): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        for (i in 0 until NUM_OF_SANDBOXES) {
            if (readSlotMapping(i) == uuid && isSlotProcessAlive(am, i)) return i
        }

        for (i in 0 until NUM_OF_SANDBOXES) {
            if (!isSlotProcessAlive(am, i)) {
                writeSlotMapping(i, uuid)
                return i
            }
        }

        val evictSlot = findIdleSlot(am) ?: 0
        finishSandboxTasks(am, readSlotMapping(evictSlot))
        killSandboxProcess(am, evictSlot)
        writeSlotMapping(evictSlot, uuid)
        return evictSlot
    }

    private fun findIdleSlot(am: ActivityManager): Int? {
        val taskSlots = mutableSetOf<Int>()
        am.appTasks?.forEach { task ->
            val uuid =
                task.taskInfo.baseIntent.getStringExtra(Const.INTENT_WEBAPP_UUID) ?: return@forEach
            for (i in 0 until NUM_OF_SANDBOXES) {
                if (readSlotMapping(i) == uuid) {
                    taskSlots.add(i)
                    break
                }
            }
        }
        for (i in 0 until NUM_OF_SANDBOXES) {
            if (i !in taskSlots && isSlotProcessAlive(am, i)) return i
        }
        return null
    }

    fun releaseSandbox(context: Context, uuid: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        finishSandboxTasks(am, uuid)
        for (i in 0 until NUM_OF_SANDBOXES) {
            if (readSlotMapping(i) == uuid) {
                killSandboxProcess(am, i)
                return
            }
        }
    }

    fun clearSandboxData(context: Context, uuid: String): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        finishSandboxTasks(am, uuid)
        for (i in 0 until NUM_OF_SANDBOXES) {
            if (readSlotMapping(i) == uuid) {
                killSandboxProcess(am, i)
                clearSlotMapping(i)
                break
            }
        }
        return wipeSandboxStorage(uuid)
    }

    fun clearAllSandboxData(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        finishSandboxTasks(am)
        killAllSandboxProcesses(am)
        dao.clearAll()

        val parentDir = App.appContext.filesDir.parentFile ?: return
        parentDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("app_webview_")) {
                file.deleteRecursively()
            }
        }
    }

    fun wipeSandboxStorage(uuid: String): Boolean {
        val sandboxDir = getSandboxDataDir(uuid)
        val deleted = if (sandboxDir.exists()) sandboxDir.deleteRecursively() else true

        val cacheDir = File(App.appContext.cacheDir.parent, "cache_$uuid")
        if (cacheDir.exists()) cacheDir.deleteRecursively()

        return deleted
    }

    fun getSandboxDataDir(uuid: String): File {
        return File(App.appContext.filesDir.parent, "app_webview_$uuid")
    }

    fun clearNonSandboxData(context: Context) {
        clearWebViewData(context)

        val dataDir = File(App.appContext.filesDir.parent, "app_webview")
        if (dataDir.exists()) dataDir.deleteRecursively()

        App.appContext.cacheDir?.deleteRecursively()
    }

    fun finishSandboxTasks(am: ActivityManager, uuid: String? = null) {
        am.appTasks?.forEach { task ->
            val taskUuid = task.taskInfo.baseIntent.getStringExtra(Const.INTENT_WEBAPP_UUID)
            if (taskUuid != null && (uuid == null || taskUuid == uuid)) {
                task.finishAndRemoveTask()
            }
        }
    }

    private fun isSlotProcessAlive(am: ActivityManager, slotId: Int): Boolean {
        val suffix = ":sandbox_$slotId"
        return am.runningAppProcesses?.any { it.processName.endsWith(suffix) } == true
    }

    private fun killSandboxProcess(am: ActivityManager, slotId: Int) {
        val suffix = ":sandbox_$slotId"
        am.runningAppProcesses?.forEach { info ->
            if (info.processName.endsWith(suffix)) Process.killProcess(info.pid)
        }
    }

    private fun killAllSandboxProcesses(am: ActivityManager) {
        am.runningAppProcesses?.forEach { info ->
            if (info.processName.contains(":sandbox_")) Process.killProcess(info.pid)
        }
    }

    private fun writeSlotMapping(slotId: Int, uuid: String) {
        dao.assign(SandboxSlotEntity(slotId = slotId, webappUuid = uuid))
    }

    private fun readSlotMapping(slotId: Int): String? {
        return dao.getUuid(slotId)
    }

    private fun clearSlotMapping(slotId: Int) {
        dao.clear(slotId)
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
