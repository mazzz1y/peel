package wtf.mazy.peel.model

sealed interface DataEvent {
    data class WebAppsChanged(val reason: Reason) : DataEvent {
        enum class Reason { ADDED, REMOVED, UPDATED }
    }

    data class GroupsChanged(val reason: Reason) : DataEvent {
        enum class Reason { ADDED, REMOVED, UPDATED }
    }

    data object SettingsChanged : DataEvent
    data object FullReload : DataEvent
}

fun interface DataEventListener {
    fun onDataEvent(event: DataEvent)
}
