package wtf.mazy.peel.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object EntityCloner {

    suspend fun cloneWebApp(webapp: WebApp) {
        val clone = webapp.cloneWith(
            webapp.groupUuid,
            DataManager.instance.nextOrderInGroup(webapp.groupUuid),
        )
        withContext(Dispatchers.IO) { clone.copyIconFrom(webapp) }
        DataManager.instance.addWebsite(clone)
    }

    suspend fun deepCloneGroup(group: WebAppGroup) {
        val newGroup = group.copy(
            uuid = UUID.randomUUID().toString(),
            order = DataManager.instance.getGroups().maxOfOrNull { it.order }?.plus(1) ?: 0,
            settings = group.settings.deepCopy(),
        )
        withContext(Dispatchers.IO) { newGroup.copyIconFrom(group) }
        DataManager.instance.addGroup(newGroup)

        DataManager.instance.activeWebsitesForGroup(group.uuid).forEach { webapp ->
            val clone = webapp.cloneWith(newGroup.uuid, webapp.order)
            withContext(Dispatchers.IO) { clone.copyIconFrom(webapp) }
            DataManager.instance.addWebsite(clone)
        }
    }
}
