package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewingBudgetTest {
    private var now = 1_000L
    private val budget = ViewingBudget(clock = { now })

    @Test
    fun viewingBudget_countsOnlyWhileRunning() {
        assertEquals(30_000L, budget.remainingMs)
        now += 5_000
        assertEquals(30_000L, budget.remainingMs)
        budget.start()
        now += 4_000
        assertEquals(26_000L, budget.remainingMs)
        assertFalse(budget.expired)
    }

    @Test
    fun viewingBudget_keepsUsedTimeAcrossPauses() {
        budget.start()
        now += 10_000
        budget.stop()
        now += 60_000
        assertEquals(20_000L, budget.remainingMs)
        budget.start()
        budget.start() // a second start while running changes nothing
        now += 5_000
        budget.stop()
        budget.stop()
        assertEquals(15_000L, budget.remainingMs)
    }

    @Test
    fun viewingBudget_roundsSecondsUp() {
        assertEquals(30, budget.remainingSeconds)
        budget.start()
        now += 1
        assertEquals(30, budget.remainingSeconds)
        now += 999
        assertEquals(29, budget.remainingSeconds)
        now += 28_999
        assertEquals(1, budget.remainingSeconds)
        now += 1
        assertEquals(0, budget.remainingSeconds)
    }

    @Test
    fun viewingBudget_expiresAtLimit() {
        budget.start()
        now += 29_999
        assertFalse(budget.expired)
        now += 1
        assertTrue(budget.expired)
        now += 10_000
        budget.stop()
        assertEquals(0L, budget.remainingMs)
        assertTrue(budget.expired)
    }
}
