package wtf.mazy.peel.model

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import wtf.mazy.peel.model.db.SandboxSlotDao
import wtf.mazy.peel.model.db.SandboxSlotEntity
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const
import java.io.File

object SandboxManager {
    private const val NUM_SLOTS = 4
    private const val ACTIVITIES_PER_SLOT = 4
    private const val SANDBOX_CLASS_PREFIX = "wtf.mazy.peel.activities.SandboxActivity"
    private const val SANDBOX_PROCESS_SUFFIX = ":sandbox_"

    private lateinit var dao: SandboxSlotDao

    var activeSuffix: String? = null
        private set

    val currentSlotId: Int?
        get() =
            Application.getProcessName()
                .substringAfterLast(SANDBOX_PROCESS_SUFFIX, "")
                .toIntOrNull()

    fun initialize(dao: SandboxSlotDao) {
        this.dao = dao
    }

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

    fun resolveSlotId(context: Context, sandboxId: String): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return findOrAssignSlot(am, sandboxId)
    }

    fun resolveActivityClass(context: Context, sandboxId: String, webappUuid: String): Class<*>? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val slotId = findOrAssignSlot(am, sandboxId)
        val activityIndex = findOrAssignActivityInSlot(am, slotId, webappUuid)
        return try {
            Class.forName("$SANDBOX_CLASS_PREFIX$activityIndex")
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    private fun findOrAssignSlot(am: ActivityManager, sandboxId: String): Int {
        val slots = loadSlotMappings()
        val aliveSlots = aliveSlotSet(am)

        for (i in 0 until NUM_SLOTS) {
            if (slots[i] == sandboxId && i in aliveSlots) return i
        }

        for (i in 0 until NUM_SLOTS) {
            if (i !in aliveSlots) {
                writeSlotMapping(i, sandboxId)
                return i
            }
        }

        val evictSlot = findIdleSlot(am, aliveSlots) ?: 0
        finishSandboxTasks(am, slots[evictSlot])
        killSandboxProcess(am, evictSlot)
        writeSlotMapping(evictSlot, sandboxId)
        return evictSlot
    }

    private fun loadSlotMappings(): Map<Int, String> =
        dao.getAll().associate { it.slotId to it.webappUuid }

    private fun aliveSlotSet(am: ActivityManager): Set<Int> {
        val processes = am.runningAppProcesses ?: return emptySet()
        val prefix = App.appContext.packageName + SANDBOX_PROCESS_SUFFIX
        val result = mutableSetOf<Int>()
        for (info in processes) {
            if (!info.processName.startsWith(prefix)) continue
            val idx = info.processName.removePrefix(prefix).toIntOrNull()
            if (idx != null) result.add(idx)
        }
        return result
    }

    private fun findOrAssignActivityInSlot(
        am: ActivityManager,
        slotId: Int,
        webappUuid: String,
    ): Int {
        val base = slotId * ACTIVITIES_PER_SLOT
        val range = base until base + ACTIVITIES_PER_SLOT
        val activeWebapps = buildActiveWebappMap(am, range)

        activeWebapps.entries
            .firstOrNull { it.value == webappUuid }
            ?.let {
                return it.key
            }

        range
            .firstOrNull { it !in activeWebapps }
            ?.let {
                return it
            }

        // All activities occupied — swap via onNewIntent
        return base
    }

    private fun buildActiveWebappMap(am: ActivityManager, range: IntRange): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        am.appTasks?.forEach { task ->
            val className = task.taskInfo.baseActivity?.className ?: return@forEach
            val index = className.removePrefix(SANDBOX_CLASS_PREFIX).toIntOrNull() ?: return@forEach
            if (index in range) {
                val uuid = task.taskInfo.baseIntent.getStringExtra(Const.INTENT_WEBAPP_UUID)
                if (uuid != null) result[index] = uuid
            }
        }
        return result
    }

    private fun findIdleSlot(am: ActivityManager, aliveSlots: Set<Int>): Int? {
        val slotsWithTasks = mutableSetOf<Int>()
        am.appTasks?.forEach { task ->
            val className = task.taskInfo.baseActivity?.className ?: return@forEach
            val index = className.removePrefix(SANDBOX_CLASS_PREFIX).toIntOrNull() ?: return@forEach
            slotsWithTasks.add(index / ACTIVITIES_PER_SLOT)
        }
        return (0 until NUM_SLOTS).firstOrNull { it !in slotsWithTasks && it in aliveSlots }
    }

    fun releaseSandbox(context: Context, uuid: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        finishSandboxTasks(am, uuid)
        for (i in 0 until NUM_SLOTS) {
            if (readSlotMapping(i) == uuid) {
                killSandboxProcess(am, i)
                return
            }
        }
    }

    fun clearSandboxData(
        context: Context,
        uuid: String,
        alsoFinishUuids: List<String> = emptyList(),
    ): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        finishSandboxTasks(am, uuid)
        alsoFinishUuids.forEach { finishSandboxTasks(am, it) }
        for (i in 0 until NUM_SLOTS) {
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

    fun clearNonSandboxData(context: Context) {
        clearWebViewData(context)

        val dataDir = File(App.appContext.filesDir.parent, "app_webview")
        if (dataDir.exists()) dataDir.deleteRecursively()

        App.appContext.cacheDir?.deleteRecursively()
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

    fun finishSandboxTasks(am: ActivityManager, uuid: String? = null) {
        am.appTasks?.forEach { task ->
            val taskUuid = task.taskInfo.baseIntent.getStringExtra(Const.INTENT_WEBAPP_UUID)
            if (taskUuid != null && (uuid == null || taskUuid == uuid)) {
                task.finishAndRemoveTask()
            }
        }
    }

    private fun killSandboxProcess(am: ActivityManager, slotId: Int) {
        val expected = App.appContext.packageName + SANDBOX_PROCESS_SUFFIX + slotId
        am.runningAppProcesses?.forEach { info ->
            if (info.processName == expected) Process.killProcess(info.pid)
        }
    }

    private fun killAllSandboxProcesses(am: ActivityManager) {
        val prefix = App.appContext.packageName + SANDBOX_PROCESS_SUFFIX
        am.runningAppProcesses?.forEach { info ->
            if (info.processName.startsWith(prefix)) Process.killProcess(info.pid)
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
        } catch (_: Exception) {
        }
    }
}
