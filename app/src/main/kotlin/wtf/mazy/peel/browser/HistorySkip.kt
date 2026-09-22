package wtf.mazy.peel.browser

import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.util.SameAppDomainMatcher

data class HistoryEntry(val uri: String, val hasUserInteraction: Boolean)

data class HistorySnapshot(val entries: List<HistoryEntry>, val currentIndex: Int) {
    companion object {
        fun from(state: GeckoSession.SessionState): HistorySnapshot? {
            val json = state.toString() ?: return null
            val history = JSONObject(json).optJSONObject("history") ?: return null
            val items = history.optJSONArray("entries") ?: return null
            val entries = List(items.length()) { i ->
                val item = items.getJSONObject(i)
                HistoryEntry(
                    uri = item.optString("url"),
                    hasUserInteraction = item.optBoolean("hasUserInteraction", true),
                )
            }
            return HistorySnapshot(entries, history.optInt("index", 0) - 1)
        }
    }
}

sealed interface HistoryStep {
    /** Nothing to skip: let the caller navigate the way it always has. */
    data object Unchanged : HistoryStep

    /** Skip to this absolute history index. */
    data class GoTo(val index: Int) : HistoryStep

    /** Every entry in this direction is skipped: the caller has no target left. */
    data object Exhausted : HistoryStep
}

object HistorySkip {

    const val BACKWARD = -1

    fun step(
        history: HistorySnapshot,
        direction: Int,
        skipDomains: List<String>,
    ): HistoryStep {
        if (skipDomains.isEmpty()) return HistoryStep.Unchanged
        val adjacent = history.currentIndex + direction
        var index = adjacent
        while (index in history.entries.indices) {
            if (restsOn(history, index, skipDomains)) {
                return if (index == adjacent) HistoryStep.Unchanged else HistoryStep.GoTo(index)
            }
            index += direction
        }
        return HistoryStep.Exhausted
    }

    // Mirrors Gecko's own goBack() rule (ChildSHistory::Go with requireUserInteraction): the
    // first and last entries always count as stops, anything between must have been interacted
    // with. Without this, jumping by index would rest on redirect hops that goBack() steps over.
    private fun restsOn(history: HistorySnapshot, index: Int, skipDomains: List<String>): Boolean {
        val entry = history.entries[index]
        if (SameAppDomainMatcher.matches(entry.uri, skipDomains)) return false
        val isEdge = index == 0 || index == history.entries.lastIndex
        return isEdge || entry.hasUserInteraction
    }
}
