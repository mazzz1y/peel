package wtf.mazy.peel.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object EntityCloner {

    private fun WebApp.cloneInto(groupUuid: String?, order: Int): WebApp = copy(
        uuid = UUID.randomUUID().toString(),
        groupUuid = groupUuid,
        order = order,
        settings = settings.deepCopy(),
    )

    suspend fun cloneWebApp(webApp: WebApp) {
        addWithIcon(webApp.cloneInto(webApp.groupUuid, order = 0), webApp, appendOrder = true)
    }

    private suspend fun addWithIcon(clone: WebApp, source: WebApp, appendOrder: Boolean) {
        withContext(Dispatchers.IO) { clone.copyIconFrom(source) }
        if (!DataManager.addWebApp(clone, appendOrder)) clone.deleteIcon()
    }

    suspend fun deepCloneGroup(group: WebAppGroup) {
        val newGroup = group.copy(
            uuid = UUID.randomUUID().toString(),
            settings = group.settings.deepCopy(),
        )
        withContext(Dispatchers.IO) { newGroup.copyIconFrom(group) }
        if (!DataManager.addGroup(newGroup, appendOrder = true)) {
            newGroup.deleteIcon()
            return
        }

        DataManager.webAppsInGroup(group.uuid).forEach { webApp ->
            addWithIcon(webApp.cloneInto(newGroup.uuid, webApp.order), webApp, appendOrder = false)
        }
    }
}
