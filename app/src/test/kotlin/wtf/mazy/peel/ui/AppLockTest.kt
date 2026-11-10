package wtf.mazy.peel.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockTest {

    private sealed interface Step {
        data class Unlock(val uuid: String) : Step
        data class Shown(val uuid: String) : Step
        data class Hidden(val uuid: String) : Step
        data object ScreenOff : Step
    }

    private data class Case(val name: String, val steps: List<Step>, val unlocked: Set<String>)

    @Test
    fun unlocksLastWhileAWindowIsOnScreen() {
        val cases = listOf(
            Case("unlocked while shown", listOf(Step.Shown("a"), Step.Unlock("a")), setOf("a")),
            Case("locks when its only window leaves", listOf(Step.Shown("a"), Step.Unlock("a"), Step.Hidden("a")), emptySet()),
            Case(
                "popup opens before the main window stops",
                listOf(Step.Shown("a"), Step.Unlock("a"), Step.Shown("a"), Step.Hidden("a")),
                setOf("a"),
            ),
            Case(
                "last of two windows leaves",
                listOf(Step.Shown("a"), Step.Unlock("a"), Step.Shown("a"), Step.Hidden("a"), Step.Hidden("a")),
                emptySet(),
            ),
            Case(
                "another web app's window does not keep it",
                listOf(Step.Shown("a"), Step.Unlock("a"), Step.Shown("b"), Step.Hidden("a")),
                emptySet(),
            ),
            Case(
                "unlocks do not leak between web apps",
                listOf(Step.Shown("a"), Step.Shown("b"), Step.Unlock("a")),
                setOf("a"),
            ),
            Case(
                "screen off locks everything",
                listOf(Step.Shown("a"), Step.Unlock("a"), Step.Shown("b"), Step.Unlock("b"), Step.ScreenOff),
                emptySet(),
            ),
            Case("a stray hide changes nothing", listOf(Step.Hidden("a")), emptySet()),
        )
        cases.forEach { case ->
            AppLock.lockAll()
            val uuids = case.steps.mapNotNull {
                when (it) {
                    is Step.Unlock -> it.uuid
                    is Step.Shown -> it.uuid
                    is Step.Hidden -> it.uuid
                    Step.ScreenOff -> null
                }
            }.toSet()
            uuids.forEach { uuid -> repeat(10) { AppLock.windowHidden(uuid) } }
            case.steps.forEach {
                when (it) {
                    is Step.Unlock -> AppLock.unlock(it.uuid)
                    is Step.Shown -> AppLock.windowShown(it.uuid)
                    is Step.Hidden -> AppLock.windowHidden(it.uuid)
                    Step.ScreenOff -> AppLock.lockAll()
                }
            }
            assertEquals(case.name, case.unlocked, uuids.filter(AppLock::isUnlocked).toSet())
        }
    }
}
