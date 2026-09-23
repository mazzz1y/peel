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
        val clone = webApp.cloneInto(webApp.groupUuid, order = 0)
        withContext(Dispatchers.IO) { clone.copyIconFrom(webApp) }
        DataManager.addWebApp(clone, appendOrder = true)
    }

    suspend fun deepCloneGroup(group: WebAppGroup) {
        val newGroup = group.copy(
            uuid = UUID.randomUUID().toString(),
            settings = group.settings.deepCopy(),
        )
        withContext(Dispatchers.IO) { newGroup.copyIconFrom(group) }
        DataManager.addGroup(newGroup, appendOrder = true)

        DataManager.webAppsInGroup(group.uuid).forEach { webApp ->
            val clone = webApp.cloneInto(newGroup.uuid, webApp.order)
            withContext(Dispatchers.IO) { clone.copyIconFrom(webApp) }
            DataManager.addWebApp(clone)
        }
    }
}
