package com.epiccrown.directstream.showcase.ui

import com.epiccrown.directstream.api.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentCallsTest {
    private fun event(
        code: String? = "ABC234",
        id: String = "request",
        at: Long = 10,
        outcome: CallOutcome = CallOutcome.CALLING,
        uid: String? = null,
    ) = CallAttempt(code, id, at, CallDirection.OUTGOING, outcome, uid)

    @Test
    fun statusesDeduplicateAndTerminalEventsCannotReopenCalls() {
        for (terminal in
            listOf(
                CallOutcome.COMPLETED,
                CallOutcome.MISSED,
                CallOutcome.DECLINED,
                CallOutcome.CANCELLED,
                CallOutcome.FAILED,
            )) {
            var rows = RecentCalls.record(emptyList(), event())
            rows = RecentCalls.record(rows, event(outcome = terminal))
            rows = RecentCalls.record(rows, event(outcome = CallOutcome.CONNECTED))
            rows = RecentCalls.record(rows, event())
            assertEquals(1, rows.size)
            assertEquals(terminal, rows.single().outcome)
        }
    }

    @Test
    fun lateApprovalAndPendingEventsDoNotDowngradeConnectedCalls() {
        var rows = RecentCalls.record(emptyList(), event(outcome = CallOutcome.CONNECTED))
        rows = RecentCalls.record(rows, event(outcome = CallOutcome.ACCEPTED))
        rows = RecentCalls.record(rows, event())
        assertEquals(CallOutcome.CONNECTED, rows.single().outcome)
    }

    @Test
    fun lateEventsDoNotReplaceNewerAttempts() {
        var rows = RecentCalls.record(emptyList(), event(id = "new", at = 20))
        rows = RecentCalls.record(rows, event(id = "old", at = 10, outcome = CallOutcome.COMPLETED))
        assertEquals("new", rows.single().requestId)
    }

    @Test
    fun approvedIdentityMergesRefreshedCodesWithNewHostName() {
        var rows =
            RecentCalls.record(emptyList(), event(uid = "peer", outcome = CallOutcome.COMPLETED))
                .map { it.copy(peerName = "Old name") }
        rows = RecentCalls.record(rows, event(code = "DEF567", id = "new", at = 20))
        assertEquals(2, rows.size)
        rows =
            RecentCalls.record(
                rows,
                event(
                        code = "DEF567",
                        id = "new",
                        at = 20,
                        uid = "peer",
                        outcome = CallOutcome.ACCEPTED,
                    )
                    .copy(peerName = "Kitchen"),
            )
        assertEquals(1, rows.size)
        assertEquals("Kitchen", rows.single().peerName)
        assertEquals("DEF567", rows.single().code)
        assertEquals("peer", rows.single().peerUid)
    }

    @Test
    fun oldServersCanRecordUnknownIncomingWithoutEnablingRedial() {
        val rows = RecentCalls.record(emptyList(), event(code = null))
        assertNull(rows.single().code)
        assertEquals("Unknown device", rows.single().displayName)
    }

    @Test
    fun retainsOnlyFiftyNewestDevices() {
        var rows = emptyList<RecentDevice>()
        for (i in 0..55) rows =
            RecentCalls.record(rows, event(code = null, id = "$i", at = i.toLong()))
        assertEquals(50, rows.size)
        assertEquals("55", rows.first().requestId)
        assertEquals("6", rows.last().requestId)
    }

    @Test
    fun namesFollowNewAttemptsButDelayedEventsCannotReplaceThem() {
        var rows =
            RecentCalls.record(emptyList(), event(id = "new", at = 20).copy(peerName = "New name"))
        rows = RecentCalls.record(rows, event(id = "old", at = 10).copy(peerName = "Old name"))
        assertEquals("New name", rows.single().peerName)
        rows = RecentCalls.record(rows, event(id = "latest", at = 30))
        assertNull(rows.single().peerName)
    }
}
