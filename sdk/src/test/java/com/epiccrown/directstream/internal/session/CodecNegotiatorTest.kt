package com.epiccrown.directstream.internal.session

import com.epiccrown.directstream.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodecNegotiatorTest {
    private class Bus {
        var offline = false
        val messages =
            Role.entries.associateWith { MutableStateFlow<Map<String, CodecSignal>>(emptyMap()) }
        val descriptions =
            Role.entries.associateWith {
                MutableSharedFlow<SignalDescription>(replay = 1, extraBufferCapacity = 16)
            }

        fun provider(role: Role) =
            object : SignalingProvider {
                override suspend fun publishCodecSignal(
                    id: String,
                    role: Role,
                    signal: CodecSignal,
                ) {
                    yield() // Real provider writes suspend; timeouts must not cancel their own
                    // rollback.
                    if (offline) awaitCancellation()
                    messages.getValue(role).update {
                        it + ("${signal.transactionId}_${signal.kind}" to signal)
                    }
                }

                override fun codecSignals(id: String, role: Role) = flow {
                    val seen = mutableSetOf<Pair<String, CodecSignalKind>>()
                    messages
                        .getValue(if (role == Role.SENDER) Role.RECEIVER else Role.SENDER)
                        .collect { rows ->
                            rows.values.forEach {
                                if (seen.add(it.transactionId to it.kind)) emit(it)
                            }
                        }
                }

                override suspend fun getRoom(id: String): Room = error("unused")

                override suspend fun publishDescription(
                    id: String,
                    role: Role,
                    description: SignalDescription,
                ) {
                    descriptions.getValue(role).emit(description)
                }

                override fun descriptions(id: String, role: Role) =
                    descriptions.getValue(if (role == Role.SENDER) Role.RECEIVER else Role.SENDER)

                override suspend fun publishCandidate(
                    id: String,
                    role: Role,
                    candidate: SignalCandidate,
                ) = Unit

                override fun candidates(id: String, role: Role) = emptyFlow<SignalCandidate>()

                override fun ended(id: String) = emptyFlow<Boolean>()

                override suspend fun presence(id: String, role: Role, connected: Boolean) = Unit
            }
    }

    private fun capabilities() =
        CodecCapabilities(
            listOf(CodecId.VP8, CodecId.VP9, CodecId.AV1, CodecId.OPUS).map {
                val format =
                    CodecFormat(
                        if (it == CodecId.OPUS) 48000 else 90000,
                        if (it == CodecId.OPUS) 2 else null,
                    )
                CodecCapability(
                    it,
                    listOf(format),
                    listOf(format),
                    CodecImplementation.SOFTWARE,
                    CodecImplementation.SOFTWARE,
                )
            },
        )

    private inner class Peer(
        val scope: CoroutineScope,
        val bus: Bus,
        val role: Role,
        val preferences: CallCodecPreferences = CallCodecPreferences(),
    ) {
        var state = CallCodecState()
        val failures = mutableListOf<CodecFailureReason>()
        var choice: CodecSelection? = null
        var transaction = ""
        var failOnce: CodecId? = null
        var dropOnce = false
        var rollbackCount = 0
        var offers = 0
        lateinit var negotiator: CodecNegotiator

        init {
            negotiator =
                CodecNegotiator(
                    scope,
                    bus.provider(role),
                    "room",
                    role,
                    capabilities(),
                    preferences,
                    configure = { c, id, rollback ->
                        choice = c
                        transaction = id
                        if (rollback) rollbackCount++
                    },
                    offer = { offer() },
                    applyDescription = { description ->
                        if (failOnce == choice?.video) {
                            failOnce = null
                            throw CodecException(CodecFailureReason.NEGOTIATION_FAILED)
                        }
                        if (description.type == "offer")
                            negotiator.publishDescription(
                                SignalDescription("answer", choice.toString(), transaction),
                            )
                        negotiator.nativeApplied(transaction)
                    },
                    update = { state = it },
                    fatal = { failures.add(it.reason) },
                )
        }

        fun offer() {
            offers++
            if (dropOnce) {
                dropOnce = false
                return
            }
            negotiator.publishDescription(
                SignalDescription("offer", choice.toString(), transaction),
            )
        }

        suspend fun start() {
            choice = negotiator.prepareInitial()
            transaction = negotiator.initialTransactionId()
            negotiator.markMediaReady()
            if (role == Role.SENDER) offer()
        }
    }

    private suspend fun TestScope.connected(): Pair<Peer, Peer> {
        val bus = Bus()
        val host = Peer(backgroundScope, bus, Role.SENDER)
        val guest = Peer(backgroundScope, bus, Role.RECEIVER)
        val a = async { host.start() }
        val b = async { guest.start() }
        runCurrent()
        a.await()
        b.await()
        runCurrent()
        assertEquals(CodecPhase.ACTIVE, host.state.phase)
        assertEquals(host.state.selection, guest.state.selection)
        return host to guest
    }

    @Test
    fun offlineSignalingWriteCannotHoldTheSwitchOpenPastItsDeadline() = runTest {
        val (a, b) = connected()
        a.bus.offline = true
        a.negotiator.request(CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.AV1)))
        runCurrent()
        advanceTimeBy(15001)
        runCurrent()
        assertEquals(CodecPhase.ACTIVE, a.state.phase)
        assertEquals(CodecId.VP8, a.state.selection?.video)
        assertEquals(CodecFailureReason.TIMEOUT, a.state.failure)
        assertEquals(1, a.offers)
        assertEquals(CodecPhase.ACTIVE, b.state.phase)
    }

    @Test
    fun incompatibleInitialEnforcementFailsBothPeersBeforeAnyNativeOffer() = runTest {
        val bus = Bus()
        val host =
            Peer(
                backgroundScope,
                bus,
                Role.SENDER,
                CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.VP9)),
            )
        val guest =
            Peer(
                backgroundScope,
                bus,
                Role.RECEIVER,
                CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.AV1)),
            )
        val a = async { runCatching { host.start() }.exceptionOrNull() }
        val b = async { runCatching { guest.start() }.exceptionOrNull() }
        runCurrent()
        assertEquals(CodecFailureReason.INCOMPATIBLE_PEER, (a.await() as CodecException).reason)
        assertEquals(CodecFailureReason.INCOMPATIBLE_PEER, (b.await() as CodecException).reason)
        assertEquals(0, host.offers)
        assertEquals(0, guest.offers)
    }

    @Test
    fun initialNegotiationAndLiveVp9ThenGuestAv1Commit() = runTest {
        val (a, b) = connected()
        a.negotiator.request(CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.VP9)))
        runCurrent()
        assertEquals(CodecId.VP9, a.state.selection?.video)
        assertEquals(a.state.selection, b.state.selection)
        b.negotiator.request(CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.AV1)))
        runCurrent()
        // The host's enforced VP9 policy remains authoritative until explicitly changed.
        assertEquals(CodecId.VP9, a.state.selection?.video)
        assertEquals(CodecFailureReason.INCOMPATIBLE_PEER, b.state.failure)
        a.negotiator.request(CallCodecPreferences())
        runCurrent()
        b.negotiator.request(CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.AV1)))
        runCurrent()
        assertEquals(CodecId.AV1, a.state.selection?.video)
        assertEquals(a.state.selection, b.state.selection)
        assertTrue(a.failures.isEmpty() && b.failures.isEmpty())
    }

    @Test
    fun simultaneousRequestsRejectWithoutGlareOrChangingCurrentCodec() = runTest {
        val (a, b) = connected()
        a.negotiator.request(CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.VP9)))
        b.negotiator.request(CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.AV1)))
        runCurrent()
        assertEquals(CodecId.VP8, a.state.selection?.video)
        assertEquals(CodecPhase.ACTIVE, a.state.phase)
        assertEquals(CodecPhase.ACTIVE, b.state.phase)
        assertEquals(1, a.offers)
    }

    @Test
    fun nativeFailureRollsBackInsteadOfEndingEstablishedCall() = runTest {
        val (a, b) = connected()
        b.failOnce = CodecId.VP9
        a.negotiator.request(CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.VP9)))
        runCurrent()
        assertEquals(CodecId.VP8, a.state.selection?.video)
        assertEquals(CodecId.VP8, b.state.selection?.video)
        assertEquals(CodecPhase.ACTIVE, a.state.phase)
        assertTrue(a.rollbackCount > 0 && b.rollbackCount > 0)
        assertEquals(CodecFailureReason.NEGOTIATION_FAILED, a.state.failure)
        assertTrue(a.failures.isEmpty() && b.failures.isEmpty())
    }

    @Test
    fun timeoutAfterCommitRestoresPreviousCodec() = runTest {
        val (a, b) = connected()
        a.dropOnce = true
        a.negotiator.request(CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.AV1)))
        runCurrent()
        advanceTimeBy(15001)
        runCurrent()
        assertEquals(CodecPhase.ACTIVE, a.state.phase)
        assertEquals(CodecId.VP8, a.state.selection?.video)
        assertEquals(a.state.selection, b.state.selection)
        assertEquals(CodecFailureReason.TIMEOUT, a.state.failure)
    }

    @Test
    fun staleTransactionsCannotReopenCompletedSelection() = runTest {
        val (a, b) = connected()
        val old =
            a.bus.messages.getValue(Role.SENDER).value.values.first {
                it.kind == CodecSignalKind.PREPARE
            }
        a.negotiator.request(CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.VP9)))
        runCurrent()
        a.bus.messages.getValue(Role.SENDER).update { it + ("replay" to old) }
        runCurrent()
        assertEquals(CodecId.VP9, b.state.selection?.video)
        assertEquals(CodecPhase.ACTIVE, b.state.phase)
    }

    @Test
    fun iceRestartWaitsForCodecTransaction() = runTest {
        val (a, b) = connected()
        a.negotiator.request(CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.VP9)))
        a.negotiator.restartIce()
        runCurrent()
        assertEquals(CodecPhase.ACTIVE, a.state.phase)
        assertEquals(a.state.selection, b.state.selection)
        assertEquals(3, a.offers)
    }

    @Test
    fun closedSessionRejectsNewSwitchAndIgnoresLateCompletion() = runTest {
        val (a, b) = connected()
        a.negotiator.close()
        b.negotiator.close()
        assertEquals(
            CodecFailureReason.NO_ACTIVE_CALL,
            assertThrows(CodecException::class.java) {
                    a.negotiator.request(CallCodecPreferences())
                }
                .reason,
        )
    }
}
