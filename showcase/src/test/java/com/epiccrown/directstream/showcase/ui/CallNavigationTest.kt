package com.epiccrown.directstream.showcase.ui

import androidx.navigation3.runtime.NavKey
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.StreamSessionState
import org.junit.Assert.*
import org.junit.Test

class CallNavigationTest {
    private val stack = mutableListOf<NavKey>(CallRoute.Home)
    private val request = PairingRequest("request", "123456", Long.MAX_VALUE)

    @Test
    fun repeatedIncomingEventsHaveOneDestination() {
        repeat(3) { stack.reconcileSession(StreamSessionState.IncomingRequest(request), false) }
        assertEquals(listOf(CallRoute.Home, CallRoute.IncomingCall("request")), stack)
    }

    @Test
    fun approvalReplacesWaitingAndConnectionDoesNotReopenAfterBack() {
        stack.openOnce(CallRoute.CallEntry)
        stack.reconcileSession(StreamSessionState.AwaitingApproval(request), false)
        assertEquals(listOf(CallRoute.Home, CallRoute.Waiting("request")), stack)
        val presented = stack.reconcileSession(StreamSessionState.Negotiating, false)
        assertEquals(listOf(CallRoute.Home, CallRoute.InCall), stack)
        stack.showHome()
        stack.reconcileSession(StreamSessionState.Connected(Role.RECEIVER), presented)
        assertEquals(listOf(CallRoute.Home), stack)
    }

    @Test
    fun terminalStateDiscardsRestoredLiveDestinationsButPreservesGallery() {
        stack.openOnce(CallRoute.InCall)
        assertFalse(stack.reconcileSession(StreamSessionState.Idle, true))
        assertEquals(listOf(CallRoute.Home), stack)
        stack.openOnce(CallRoute.ScreenGallery)
        stack.openOnce(CallRoute.ScreenExample(ShowcaseScreen.INCOMING))
        stack.reconcileSession(StreamSessionState.Idle, false)
        assertEquals(CallRoute.ScreenExample(ShowcaseScreen.INCOMING), stack.last())
    }

    @Test
    fun realIncomingRequestInterruptsExampleWithoutReusingSyntheticRequest() {
        stack.openOnce(CallRoute.ScreenGallery)
        stack.openOnce(CallRoute.ScreenExample(ShowcaseScreen.IN_CALL))
        stack.reconcileSession(StreamSessionState.IncomingRequest(request), false)
        assertEquals(listOf(CallRoute.Home, CallRoute.IncomingCall("request")), stack)
    }

    @org.junit.Test
    fun removedNicknameDestinationsRemainDecodableForSafeRestoration() {
        val route =
            kotlinx.serialization.json.Json.decodeFromString<CallRoute>(
                """{"type":"com.epiccrown.directstream.showcase.ui.CallRoute.EditAlias","recentId":"old-local-row"}""",
            )
        org.junit.Assert.assertTrue(route is CallRoute.RetiredAlias)
        val example = kotlinx.serialization.json.Json.decodeFromString<ShowcaseScreen>("\"ALIAS\"")
        org.junit.Assert.assertEquals(ShowcaseScreen.RETIRED_ALIAS, example)
    }
}
