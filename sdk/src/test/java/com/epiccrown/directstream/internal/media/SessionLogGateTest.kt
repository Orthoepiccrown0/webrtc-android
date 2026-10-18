package com.epiccrown.directstream.internal.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogGateTest {
    @Test
    fun periodicSummariesAreBoundedDespiteFrequentPolling() {
        val gate = SessionLogGate()
        val emitted = (0L..60_000L step 2_000L).filter { gate.due("stats", it, 15_000) }
        assertEquals(listOf(0L, 16_000L, 32_000L, 48_000L), emitted)
    }

    @Test
    fun repeatedStatesAreSuppressedButRecoveryIsReported() {
        val gate = SessionLogGate()
        assertTrue(gate.changed("ice", "CONNECTED"))
        repeat(100) { assertFalse(gate.changed("ice", "CONNECTED")) }
        assertTrue(gate.changed("ice", "DISCONNECTED"))
        assertTrue(gate.changed("ice", "CONNECTED"))
    }

    @Test
    fun newSessionResetsMilestonesAndRateLimits() {
        val gate = SessionLogGate()
        assertTrue(gate.once("first-frame"))
        assertFalse(gate.once("first-frame"))
        assertTrue(gate.due("stats", 100L, 15_000L))
        gate.reset()
        assertTrue(gate.once("first-frame"))
        assertTrue(gate.due("stats", 101L, 15_000L))
    }
}
