package com.epiccrown.directstream.showcase

import androidx.test.platform.app.InstrumentationRegistry
import com.epiccrown.directstream.CallCommand
import com.epiccrown.directstream.CallResult
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.DirectStreamConfiguration
import com.epiccrown.directstream.InitializationStatus
import com.epiccrown.directstream.api.AdmissionProvider
import com.epiccrown.directstream.api.BackendException
import com.epiccrown.directstream.api.CallProvider
import com.epiccrown.directstream.api.ConfigurationCheck
import com.epiccrown.directstream.api.Device
import com.epiccrown.directstream.api.FailureKind
import com.epiccrown.directstream.api.IdentityProvider
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.testing.InMemoryCallProvider
import com.epiccrown.directstream.testing.InMemoryCallServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdkClientTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun isolated(
        provider: CallProvider = InMemoryCallProvider(deviceKey = "sdk-test"),
        block: suspend (DirectStreamClient) -> Unit,
    ) {
        val original = DirectStreamClient.currentOrNull()?.configuration
        DirectStreamClient.currentOrNull()?.dispose()
        // Every test starts inactive; a previous test's activation must not trigger recovery.
        context.getSharedPreferences("directstream-activation", 0).edit().clear().commit()
        val config = DirectStreamConfiguration(provider, returnActivity = MainActivity::class.java)
        val client =
            withContext(Dispatchers.Main) { DirectStreamClient.initialize(context, config) }
        try {
            block(client)
        } finally {
            client.dispose()
            withContext(Dispatchers.Main) {
                DirectStreamClient.initialize(context, requireNotNull(original))
            }
        }
    }

    @Test
    fun codecDefaultsAndFailuresArePublicAndNeverStartMedia() = runBlocking {
        isolated { client ->
            withTimeout(5000) { while (client.codecs.value.capabilities == null) delay(20) }
            assertFalse(client.state.value.ready)
            assertNull(client.state.value.device)
            assertFalse(client.video.localAvailable.value)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
            assertEquals(
                CallResult.Success,
                client.execute(
                    CallCommand.SetNextCallCodecs(
                        com.epiccrown.directstream.api.CallCodecPreferences(),
                    ),
                ),
            )
            // Switching codecs requires activation, even when there is no current call.
            assertEquals(CallResult.Success, client.activate())
            val noCall =
                client.execute(
                    CallCommand.SwitchCodecs(
                        com.epiccrown.directstream.api.CallCodecPreferences(),
                    ),
                ) as CallResult.Failure
            assertEquals(FailureKind.CODEC, noCall.error.kind)
            assertEquals(
                com.epiccrown.directstream.api.CodecFailureReason.NO_ACTIVE_CALL,
                noCall.error.codecReason,
            )
            val previous = client.codecs.value.nextCall
            val invalid =
                client.execute(
                    CallCommand.SetNextCallCodecs(
                        com.epiccrown.directstream.api.CallCodecPreferences(
                            video =
                                com.epiccrown.directstream.api.CodecPolicy.Enforce(
                                    com.epiccrown.directstream.api.CodecId(
                                        "video/not-bundled",
                                    ),
                                ),
                        ),
                    ),
                ) as CallResult.Failure
            assertEquals(
                com.epiccrown.directstream.api.CodecFailureReason.UNSUPPORTED_LOCAL,
                invalid.error.codecReason,
            )
            assertEquals(previous, client.codecs.value.nextCall)
            assertFalse(client.video.localAvailable.value)
        }
    }

    @Test
    fun regenerateCodeRevokesOldAdmissionCancelsPendingAndPreservesApprovedRooms() = runBlocking {
        val server = InMemoryCallServer()
        val target = InMemoryCallProvider(server, "rotation-target")
        val caller = InMemoryCallProvider(server, "rotation-caller")
        isolated(target) { client ->
            client.activate()
            val old = client.state.value.device!!.code
            val pending = caller.admission.create(old, "rotation-pending-request-1")
            client.execute(CallCommand.ReviewIncoming(pending.requestId))
            assertTrue(client.state.value.session is StreamSessionState.IncomingRequest)
            assertEquals(CallResult.Success, client.regenerateCode())
            val replacement = client.state.value.device!!.code
            assertNotEquals(old, replacement)
            assertEquals("cancelled", caller.admission.status(pending.requestId).status)
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            assertNotNull(client.state.value.outcome)
            try {
                caller.admission.create(old, "rotation-revoked-request")
                fail("Old code must be revoked")
            } catch (error: BackendException) {
                assertEquals(404, error.status)
            }
            val next = caller.admission.create(replacement, "rotation-approved-request")
            val room = target.admission.approve(next.requestId)
            assertEquals(CallResult.Success, client.regenerateCode())
            caller.admission.sessionActive(room)
            assertEquals("approved", caller.admission.status(next.requestId).status)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
        }
    }

    @Test
    fun failedRegenerationReturnsTypedFailureAndKeepsLastConfirmedCode() = runBlocking {
        val base = InMemoryCallProvider(deviceKey = "rotation-failure")
        val provider =
            object : CallProvider by base {
                override val admission =
                    object : AdmissionProvider by base.admission {
                        override suspend fun reset(): Device =
                            throw BackendException(503, "Simulated service outage")
                    }
            }
        isolated(provider) { client ->
            client.activate()
            val previous = client.state.value.device
            val result = client.regenerateCode() as CallResult.Failure
            assertEquals(FailureKind.UNAVAILABLE, result.error.kind)
            assertEquals(previous, client.state.value.device)
            assertFalse(client.state.value.busy)
        }
    }

    @Test
    fun cachedPendingEventsCannotReopenADeclinedRequest() = runBlocking {
        val server = InMemoryCallServer()
        val base = InMemoryCallProvider(server, "replay-target")
        val caller = InMemoryCallProvider(server, "replay-caller")
        val replay = kotlinx.coroutines.flow.MutableStateFlow<PairingRequest?>(null)
        val provider =
            object : CallProvider by base {
                override val admission =
                    object : AdmissionProvider by base.admission {
                        override fun incomingEvents() = replay
                    }
            }
        isolated(provider) { client ->
            client.setVisible(true)
            client.activate()
            val request =
                caller.admission.create(client.state.value.device!!.code, "replayed-request-123456")
            val incoming = base.admission.status(request.requestId)
            replay.value = incoming
            withTimeout(2_000) {
                while (client.state.value.session !is StreamSessionState.IncomingRequest) delay(20)
            }
            client.execute(CallCommand.Decline)
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            replay.value = null
            delay(50)
            replay.value = incoming
            delay(100)
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            client.setVisible(false)
            client.setVisible(true)
            delay(100)
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
        }
    }

    @Test
    fun failedDeclineIsRetainedAndAConcurrentApprovalIsReleasedOnResume() = runBlocking {
        val server = InMemoryCallServer()
        val base = InMemoryCallProvider(server, "discard-target")
        val caller = InMemoryCallProvider(server, "discard-caller")
        val unavailable = java.util.concurrent.atomic.AtomicBoolean(true)
        val provider =
            object : CallProvider by base {
                override val admission =
                    object : AdmissionProvider by base.admission {
                        override suspend fun reject(id: String) {
                            if (unavailable.get()) throw java.io.IOException("Simulated outage")
                            base.admission.reject(id)
                        }
                    }
            }
        isolated(provider) { client ->
            client.setVisible(true)
            client.activate()
            val pending =
                caller.admission.create(
                    client.state.value.device!!.code,
                    "discard-race-request-123",
                )
            withTimeout(2_000) {
                while (client.state.value.session !is StreamSessionState.IncomingRequest) delay(20)
            }
            assertTrue(client.execute(CallCommand.Decline) is CallResult.Failure)
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            client.setVisible(false)
            val room = base.admission.approve(pending.requestId)
            unavailable.set(false)
            client.setVisible(true)
            withTimeout(3_000) {
                while (runCatching { caller.admission.sessionActive(room) }.isSuccess) delay(20)
            }
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
        }
    }

    @Test
    fun requestsAreDeliveredByEventsWithoutRecurringApiReads() = runBlocking {
        val server = InMemoryCallServer()
        val base = InMemoryCallProvider(server, "events-target")
        val caller = InMemoryCallProvider(server, "events-caller")
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val subscriptions = java.util.concurrent.atomic.AtomicInteger()
        val provider =
            object : CallProvider by base {
                override val admission =
                    object : AdmissionProvider by base.admission {
                        override suspend fun incoming(): PairingRequest? {
                            reads.incrementAndGet()
                            return base.admission.incoming()
                        }

                        override suspend fun status(
                            id: String,
                            timeoutSeconds: Long,
                        ): PairingRequest {
                            reads.incrementAndGet()
                            return base.admission.status(id, timeoutSeconds)
                        }

                        override fun incomingEvents() =
                            kotlinx.coroutines.flow.flow {
                                subscriptions.incrementAndGet()
                                try {
                                    emitAll(base.admission.incomingEvents())
                                } finally {
                                    subscriptions.decrementAndGet()
                                }
                            }
                    }
            }
        isolated(provider) { client ->
            client.setVisible(true)
            client.activate()
            withTimeout(3_000) { while (reads.get() == 0 || subscriptions.get() == 0) delay(20) }
            val baseline = reads.get()
            val pending =
                caller.admission.create(
                    client.state.value.device!!.code,
                    "event-driven-request-1234",
                )
            withTimeout(2_000) {
                while (client.state.value.session !is StreamSessionState.IncomingRequest) delay(20)
            }
            caller.admission.cancel(pending.requestId)
            withTimeout(2_000) {
                while (client.state.value.session !is StreamSessionState.Idle) delay(20)
            }
            // Longer than the former five-second poll interval: no timer-driven network reads.
            delay(6_200)
            // The facade revalidates an incoming alert once; this is not recurring polling.
            assertTrue(reads.get() - baseline in 0..1)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
            client.setVisible(false)
            // Incoming admission stays live in the background so alerts/cancellations work.
            assertTrue(subscriptions.get() > 0)
            caller.admission.create(client.state.value.device!!.code, "background-events-request")
            withTimeout(2_000) {
                while (client.state.value.session !is StreamSessionState.IncomingRequest) delay(20)
            }
            assertFalse(client.video.localAvailable.value)
            client.setVisible(true)
        }
    }

    @Test
    fun registrationRecoversOnResumeWithoutRecreatingClientOrStartingMedia() = runBlocking {
        val base = InMemoryCallProvider(deviceKey = "recovery-test")
        val available = java.util.concurrent.atomic.AtomicBoolean(false)
        val registrations = java.util.concurrent.atomic.AtomicInteger()
        val provider =
            object : CallProvider by base {
                override val admission =
                    object : AdmissionProvider by base.admission {
                        override suspend fun register(participantName: String?): Device {
                            registrations.incrementAndGet()
                            if (!available.get()) throw java.io.IOException("Simulated offline")
                            return base.admission.register(participantName)
                        }
                    }
            }
        isolated(provider) { client ->
            client.setVisible(true)
            client.activate()
            // Recovery may already be retrying when activation returns; readiness is authoritative.
            assertFalse(client.state.value.ready)
            withTimeout(4_000) { while (registrations.get() < 2) delay(20) }
            client.setVisible(false)
            available.set(true)
            client.setVisible(true)
            withTimeout(2_000) {
                while (
                    !client.state.value.ready ||
                        client.setup.value.initialization != InitializationStatus.READY
                ) delay(20)
            }
            assertNull(client.state.value.error)
            assertNull(client.state.value.failure)
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
            assertSame(client, DirectStreamClient.current())
        }
    }

    @Test
    fun oneShotReconciliationRecoversOnResumeAndClearsItsOwnError() = runBlocking {
        val base = InMemoryCallProvider(deviceKey = "poll-recovery-test")
        val unavailable = java.util.concurrent.atomic.AtomicBoolean(true)
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val provider =
            object : CallProvider by base {
                override val admission =
                    object : AdmissionProvider by base.admission {
                        override suspend fun incoming(): PairingRequest? {
                            polls.incrementAndGet()
                            if (unavailable.get()) throw BackendException(503, "Simulated outage")
                            return null
                        }
                    }
            }
        isolated(provider) { client ->
            client.setVisible(true)
            client.activate()
            withTimeout(3_000) { while (client.state.value.error == null) delay(20) }
            client.setVisible(false)
            val before = polls.get()
            unavailable.set(false)
            client.setVisible(true)
            withTimeout(2_000) {
                while (polls.get() <= before || client.state.value.error != null) delay(20)
            }
            assertTrue(client.state.value.ready)
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
        }
    }

    @Test
    fun equivalentInitializationReusesRuntimeAndConflictingConfigurationFails() = runBlocking {
        isolated { client ->
            assertSame(client, DirectStreamClient.initialize(context, client.configuration))
            assertTrue(
                runCatching {
                    DirectStreamClient.initialize(
                        context,
                        client.configuration.copy(forceRelay = true),
                    )
                }
                    .isFailure,
            )
            assertEquals(InitializationStatus.NOT_INITIALIZED, client.setup.value.initialization)
            assertEquals(CallResult.Success, client.activate())
            assertEquals(InitializationStatus.READY, client.setup.value.initialization)
            assertNotNull(client.state.value.device)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
        }
    }

    @Test
    fun disposalAllowsFreshRuntimeAndOldClientReturnsTypedFailure() = runBlocking {
        isolated { client ->
            client.activate()
            client.dispose()
            assertEquals(InitializationStatus.DISPOSED, client.setup.value.initialization)
            assertEquals(
                FailureKind.DISPOSED,
                (client.activate() as CallResult.Failure).error.kind,
            )
            val fresh =
                withContext(Dispatchers.Main) {
                    DirectStreamClient.initialize(context, client.configuration)
                }
            assertNotSame(client, fresh)
            assertEquals(CallResult.Success, fresh.activate())
            fresh.dispose()
        }
    }

    @Test
    fun malformedAndSelfCodesReturnTypedFailureWithoutHistoryOrMedia() = runBlocking {
        isolated { client ->
            client.activate()
            assertEquals(
                FailureKind.INVALID_INPUT,
                (client.execute(CallCommand.Request("invalid")) as CallResult.Failure).error.kind,
            )
            assertEquals(
                FailureKind.INVALID_INPUT,
                (client.execute(CallCommand.Request(client.state.value.device!!.code))
                        as CallResult.Failure)
                    .error
                    .kind,
            )
            assertNull(client.state.value.latestAttempt)
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
            assertNull(client.validateIncoming("malformed"))
        }
    }

    @Test
    fun missingConfigurationIsTypedAndNeverRegistersServices() = runBlocking {
        isolated { client ->
            client.dispose()
            var registered = false
            val provider =
                object : CallProvider by client.configuration.provider {
                    override val configurationKey: Any = "missing-config-test"

                    override fun validate() = ConfigurationCheck(missing = listOf("WORKER_URL"))

                    override val identity =
                        object : IdentityProvider {
                            override suspend fun signIn(): String {
                                registered = true
                                error("Must not contact identity")
                            }
                        }
                }
            val invalid =
                withContext(Dispatchers.Main) {
                    DirectStreamClient.initialize(
                        context,
                        client.configuration.copy(provider = provider),
                    )
                }
            try {
                assertEquals(
                    FailureKind.CONFIGURATION,
                    (invalid.activate() as CallResult.Failure).error.kind,
                )
                assertEquals(InitializationStatus.FAILED, invalid.setup.value.initialization)
                assertFalse(invalid.configuration.provider.validate().valid)
                assertFalse(registered)
                assertFalse(invalid.state.value.ready)
                assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(invalid).token)
            } finally {
                invalid.dispose()
            }
        }
    }

    @Test
    fun cancelledPushHintsCannotShowOrReopenIncomingCall() = runBlocking {
        val server = InMemoryCallServer()
        val receiver = InMemoryCallProvider(server, "push-receiver")
        val caller = InMemoryCallProvider(server, "push-caller")
        isolated(receiver) { client ->
            client.activate()
            val request =
                caller.admission.create(client.state.value.device!!.code, "push-test-request-12345")
            assertNotNull(client.validateIncoming(request.requestId))
            caller.admission.cancel(request.requestId)
            assertNull(client.validateIncoming(request.requestId))
            assertFalse(
                com.epiccrown.directstream.incoming.IncomingCalls.handlePush(
                    context,
                    mapOf("requestId" to request.requestId),
                ),
            )
            assertEquals(StreamSessionState.Idle, client.state.value.session)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
            assertNull(client.state.value.latestAttempt)
        }
    }
}
