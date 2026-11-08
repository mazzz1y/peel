package wtf.mazy.peel.ui

object UnlockedWebApps {
    private val uuids = mutableSetOf<String>()

    fun isUnlocked(uuid: String): Boolean = uuid in uuids

    fun unlock(uuid: String) {
        uuids.add(uuid)
    }

    fun lock(uuid: String) {
        uuids.remove(uuid)
    }

    fun lockAll() = uuids.clear()
}
