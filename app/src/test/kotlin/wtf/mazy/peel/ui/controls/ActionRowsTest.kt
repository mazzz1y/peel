package wtf.mazy.peel.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionRowsTest {

    @Test
    fun fitsInOneRow() {
        assertEquals(listOf(6), ActionRows.split(count = 6, perRow = 6))
        assertEquals(listOf(4), ActionRows.split(count = 4, perRow = 9))
    }

    @Test
    fun sevenWithSixPerRowSplitsFourThree() {
        assertEquals(listOf(4, 3), ActionRows.split(count = 7, perRow = 6))
    }

    @Test
    fun eightSplitsEvenly() {
        assertEquals(listOf(4, 4), ActionRows.split(count = 8, perRow = 6))
    }

    @Test
    fun nineWithFourPerRowUsesThreeEvenRows() {
        assertEquals(listOf(3, 3, 3), ActionRows.split(count = 9, perRow = 4))
    }

    @Test
    fun earlierRowsTakeTheRemainder() {
        assertEquals(listOf(4, 4, 3), ActionRows.split(count = 11, perRow = 4))
    }

    @Test
    fun noActionsNoRows() {
        assertEquals(emptyList<Int>(), ActionRows.split(count = 0, perRow = 6))
    }

    @Test
    fun zeroFitStillShowsOneActionPerRow() {
        assertEquals(listOf(1, 1, 1), ActionRows.split(count = 3, perRow = 0))
    }
}
