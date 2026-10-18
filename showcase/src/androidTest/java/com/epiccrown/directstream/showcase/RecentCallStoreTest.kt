package com.epiccrown.directstream.showcase

import androidx.test.platform.app.InstrumentationRegistry
import com.epiccrown.directstream.api.CallAttempt
import com.epiccrown.directstream.api.CallDirection
import com.epiccrown.directstream.api.CallOutcome
import com.epiccrown.directstream.showcase.ui.RecentCallStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentCallStoreTest {
    @Test
    fun persistedOutcomesNamesMigrationRemovalAndClear() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RecentCallStore(context, "recent-calls-test")
        store.clear()
        try {
            for (outcome in CallOutcome.entries) {
                store.record(
                    CallAttempt(
                        "ABC234",
                        outcome.name,
                        outcome.ordinal.toLong(),
                        CallDirection.INCOMING,
                        outcome,
                        peerName = "Kitchen",
                    ),
                )
                val loaded = RecentCallStore(context, "recent-calls-test").rows.value.single()
                assertEquals(outcome, loaded.outcome)
                assertEquals(CallDirection.INCOMING, loaded.direction)
            }
            assertEquals(
                "Kitchen",
                RecentCallStore(context, "recent-calls-test").rows.value.single().peerName,
            )
            store.remove(store.rows.value.single().id)
            assertTrue(RecentCallStore(context, "recent-calls-test").rows.value.isEmpty())
            store.record(
                CallAttempt(null, "unknown", 100, CallDirection.INCOMING, CallOutcome.MISSED),
            )
            assertNull(RecentCallStore(context, "recent-calls-test").rows.value.single().code)
            store.clear()
            assertTrue(RecentCallStore(context, "recent-calls-test").rows.value.isEmpty())
        } finally {
            store.clear()
        }
    }

    @Test
    fun legacyPrivateNicknamesAreDiscardedWithoutDeletingHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("recent-calls-migration-test", 0)
        prefs
            .edit()
            .putString(
                "rows",
                """[{"id":"ABC234","code":"ABC234","alias":"Private nickname","requestId":"old-request","at":42,"direction":"INCOMING","outcome":"MISSED"}]""",
            )
            .commit()
        try {
            val row = RecentCallStore(context, "recent-calls-migration-test").rows.value.single()
            assertEquals("ABC234", row.code)
            assertNull(row.peerName)
            assertEquals(CallOutcome.MISSED, row.outcome)
            assertTrue(!prefs.getString("rows", "")!!.contains("Private nickname"))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
