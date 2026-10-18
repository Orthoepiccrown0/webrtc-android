package com.epiccrown.directstream

import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ProviderContractTest {
    private var now = 1_000L
    private val server = InMemoryCallServer { now }
    private val caller = InMemoryCallProvider(server, "caller")
    private val target = InMemoryCallProvider(server, "target")
    private val other = InMemoryCallProvider(server, "other")

    private suspend fun pending(id: String = "request-123456789") =
        caller.admission.create(target.admission.register().code, id)

    private suspend fun status(block: suspend () -> Unit): Int =
        try {
            block()
            fail("Expected provider rejection")
            0
        } catch (error: BackendException) {
            error.status
        }

    @Test
    fun revocationRemovesTheParticipantNameForTheNextRegistration() = runBlocking {
        caller.admission.register("Alice")
        caller.admission.revoke()
        assertNull(caller.admission.register().participantName)
    }

    @Test
    fun participantNamesArePrivateAndSnapshotAcrossRegistrationChanges() = runBlocking {
        caller.admission.register("  Caller Fox  ")
        val device = target.admission.register("Target Owl")
        val outgoing = caller.admission.create(device.code, "named-request-123456")
        assertEquals("Target Owl", outgoing.peerName)
        assertEquals("Caller Fox", target.admission.incoming()!!.peerName)
        target.admission.register("Replacement Owl")
        caller.admission.register(null)
        assertEquals("Target Owl", caller.admission.status(outgoing.requestId).peerName)
        assertEquals("Caller Fox", target.admission.status(outgoing.requestId).peerName)
        assertEquals(403, status { other.admission.status(outgoing.requestId) })
        target.admission.approve(outgoing.requestId)
        assertEquals("Caller Fox", target.admission.status(outgoing.requestId).peerName)
    }

    @Test
    fun codecStreamsAreParticipantScopedAndCarryCorrelatedDescriptions() = runBlocking {
        val request = pending()
        val room = target.admission.approve(request.requestId)
        val signal =
            CodecSignal(
                "codec-contract-test-1234",
                CodecSignalKind.CAPABILITIES,
                endpoint = CodecEndpoint(CodecCapabilities(), CallCodecPreferences()),
            )
        target.signaling.publishCodecSignal(room, Role.SENDER, signal)
        assertEquals(
            signal,
            withTimeout(1000) { caller.signaling.codecSignals(room, Role.RECEIVER).first() },
        )
        assertEquals(403, status { other.signaling.codecSignals(room, Role.RECEIVER).first() })
        assertEquals(403, status { caller.signaling.publishCodecSignal(room, Role.SENDER, signal) })
        val description = SignalDescription("offer", "v=0", signal.transactionId)
        target.signaling.publishDescription(room, Role.SENDER, description)
        assertEquals(description, caller.signaling.descriptions(room, Role.RECEIVER).first())
        target.admission.end(room)
        assertEquals(
            410,
            status { caller.signaling.publishCodecSignal(room, Role.RECEIVER, signal) },
        )
    }

    @Test
    fun requestAndSessionStreamsDeliverChangesAndRejectOutsiders() = runBlocking {
        val code = target.admission.register().code
        val received =
            async(start = CoroutineStart.UNDISPATCHED) {
                target.admission.incomingEvents().first { it != null }
            }
        val request = caller.admission.create(code, "reactive-request-123456")
        assertEquals(request.requestId, withTimeout(1_000) { received.await() }!!.requestId)
        val statuses = mutableListOf<String>()
        val observer =
            launch(start = CoroutineStart.UNDISPATCHED) {
                caller.admission.requestEvents(request.requestId).take(2).collect {
                    statuses.add(it.status)
                }
            }
        val room = target.admission.approve(request.requestId)
        withTimeout(1_000) { observer.join() }
        assertEquals(listOf("pending", "approved"), statuses)
        val active =
            async(start = CoroutineStart.UNDISPATCHED) {
                caller.admission.sessionEvents(room).take(2).toList()
            }
        target.admission.end(room)
        assertEquals(listOf(true, false), withTimeout(1_000) { active.await() }.map { it.active })
        assertEquals(403, status { other.admission.requestEvents(request.requestId).first() })
        assertEquals(403, status { other.admission.sessionEvents(room).first() })
    }

    @Test
    fun registrationNormalizationAndParticipantCallbacks() = runBlocking {
        val code = target.admission.register().code
        assertEquals(code, target.admission.register().code)
        val request =
            caller.admission.create(DeviceCode.format(code).lowercase(), "request-123456789")
        assertEquals(Role.RECEIVER, request.role)
        val incoming = target.admission.incoming()!!
        assertEquals(Role.SENDER, incoming.role)
        assertEquals(caller.admission.register().code, incoming.peerCode)
        assertEquals(request.verificationValue, incoming.verificationValue)
        assertEquals(403, status { other.admission.status(request.requestId) })
    }

    @Test
    fun noRoomOrSignalingBeforeApproval() = runBlocking {
        val request = pending()
        assertNull(request.roomId)
        assertEquals(404, status { caller.signaling.getRoom("unapproved") })
        assertEquals(403, status { caller.admission.approve(request.requestId) })
    }

    @Test
    fun concurrentRequestsReserveBothParticipants() = runBlocking {
        val code = target.admission.register().code
        val results =
            listOf(caller, other)
                .mapIndexed { index, provider ->
                    async {
                        runCatching { provider.admission.create(code, "concurrent-request-$index") }
                    }
                }
                .awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(
            409,
            (results.first { it.isFailure }.exceptionOrNull() as BackendException).status,
        )
        assertEquals(
            409,
            status {
                target.admission.create(other.admission.register().code, "target-outgoing-12345")
            },
        )
    }

    @Test
    fun callerCodeResetCancelsItsPendingAttemptAndReleasesTheTarget() = runBlocking {
        val request = pending()
        val oldCode = caller.admission.register().code
        val refreshed = caller.admission.reset()
        assertNotEquals(oldCode, refreshed.code)
        assertEquals("cancelled", target.admission.status(request.requestId).status)
        assertNull(target.admission.incoming())
        assertEquals(
            "pending",
            other.admission.create(target.admission.register().code, "after-caller-reset").status,
        )
    }

    @Test
    fun repeatedApprovalReturnsOneRoomAndEndReleasesBoth() = runBlocking {
        val request = pending()
        val ids = (1..12).map { async { target.admission.approve(request.requestId) } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        val room = caller.signaling.getRoom(ids.first())
        assertEquals("target", room.hostUid)
        assertEquals(403, status { other.signaling.getRoom(room.roomId) })
        assertEquals(
            409,
            status {
                other.admission.create(target.admission.register().code, "busy-request-12345")
            },
        )
        target.admission.end(room.roomId)
        assertTrue(caller.signaling.ended(room.roomId).first())
        assertEquals(410, status { target.admission.approve(request.requestId) })
        assertEquals(
            "pending",
            other.admission.create(target.admission.register().code, "new-request-123456").status,
        )
    }

    @Test
    fun cancellationExpiryAndRejectionNeverReopen() = runBlocking {
        var request = pending()
        caller.admission.cancel(request.requestId)
        assertEquals("cancelled", caller.admission.status(request.requestId).status)
        assertEquals(410, status { target.admission.approve(request.requestId) })
        request = pending("expiry-request-12345")
        now += 300_001
        assertEquals("expired", target.admission.status(request.requestId).status)
        assertNull(target.admission.incoming())
        assertEquals(410, status { target.admission.approve(request.requestId) })
        request = pending("decline-request-12345")
        target.admission.reject(request.requestId)
        assertEquals("rejected", caller.admission.status(request.requestId).status)
        assertEquals(410, status { target.admission.approve(request.requestId) })
    }

    @Test
    fun resetCancelsPendingButPreservesApprovedRoom() = runBlocking {
        val request = pending()
        val old = target.admission.register().code
        val next = target.admission.reset().code
        assertNotEquals(old, next)
        assertEquals("cancelled", caller.admission.status(request.requestId).status)
        assertEquals(404, status { caller.admission.create(old, "old-code-request-12345") })
        val approved = pending("approved-request-12345")
        val room = target.admission.approve(approved.requestId)
        target.admission.reset()
        assertEquals(room, caller.signaling.getRoom(room).roomId)
    }

    @Test
    fun authorizedDescriptionsAndCandidatesAreSeparateByRole() = runBlocking {
        val room = target.admission.approve(pending().requestId)
        val description = SignalDescription("offer", "test description")
        target.signaling.publishDescription(room, Role.SENDER, description)
        assertEquals(description, caller.signaling.descriptions(room, Role.RECEIVER).first())
        assertEquals(
            403,
            status { caller.signaling.publishDescription(room, Role.SENDER, description) },
        )
        val candidate = SignalCandidate("test", "0", 0, "test candidate")
        caller.signaling.publishCandidate(room, Role.RECEIVER, candidate)
        assertEquals(candidate, target.signaling.candidates(room, Role.SENDER).first())
    }

    @Test
    fun selfCallsAndConflictingRetriesFail() = runBlocking {
        assertEquals(
            400,
            status {
                caller.admission.create(caller.admission.register().code, "self-request-123456")
            },
        )
        val request = pending()
        assertEquals(
            request,
            caller.admission.create(target.admission.register().code, request.requestId),
        )
        assertEquals(
            409,
            status { caller.admission.create(other.admission.register().code, request.requestId) },
        )
    }
}
