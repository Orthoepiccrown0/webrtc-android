package com.epiccrown.directstream.showcase.integration

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.epiccrown.directstream.*
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.internal.media.WebRtcClient
import com.epiccrown.directstream.internal.session.StreamController
import com.epiccrown.directstream.testing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SdkIntegrationTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun isolated(
        provider: CallProvider,
        block: suspend (DirectStreamClient) -> Unit,
    ) {
        val original = DirectStreamClient.currentOrNull()?.configuration
        DirectStreamClient.currentOrNull()?.dispose()
        context.getSharedPreferences("directstream-activation", 0).edit().clear().commit()
        val client =
            withContext(Dispatchers.Main) {
                DirectStreamClient.initialize(
                    context,
                    DirectStreamConfiguration(
                        provider,
                        returnActivity = SdkTestActivity::class.java,
                    ),
                )
            }
        try {
            block(client)
        } finally {
            client.deactivate()
            client.dispose()
            withContext(Dispatchers.Main) {
                DirectStreamClient.initialize(context, requireNotNull(original))
            }
        }
    }

    private suspend fun waitFor(block: () -> Boolean) {
        withTimeout(45_000) { while (!block()) delay(50) }
    }

    @Test
    fun activationRevocationPushAndStaleCommandsUsePublicContracts() = runBlocking {
        val delegate = InMemoryCallProvider(deviceKey = "sdk-first-release-lifecycle")
        var registrations = 0
        var offline = false
        val provider =
            object : CallProvider by delegate {
                override val configurationKey = "sdk-first-release-lifecycle-provider"
                override val push =
                    object : PushProvider {
                        override suspend fun currentToken(): String? = null

                        override suspend fun registerToken(token: String) {
                            registrations++
                        }
                    }
                override val admission =
                    object : AdmissionProvider by delegate.admission {
                        override suspend fun revoke() {
                            if (offline) throw java.io.IOException()
                            delegate.admission.revoke()
                        }
                    }
            }
        isolated(provider) { client ->
            assertFalse(client.isActivated)
            assertNull(client.registration.value.device)
            assertEquals(
                PushTokenResult.Queued,
                client.updatePushToken("test-token-before-activation"),
            )
            assertEquals(0, registrations)
            assertEquals(PushHandlingResult.Unrelated, client.handlePush(mapOf("type" to "other")))
            assertEquals(CallResult.Success, client.activate())
            waitFor { client.registration.value.device != null }
            assertEquals(1, registrations)
            val first = client.registration.value.device!!.code
            assertFalse(client.video.localAvailable.value)
            assertTrue(client.accept("not-current-call") is CallResult.Failure)
            offline = true
            assertTrue(client.deactivate() is CallResult.Failure)
            assertEquals(RegistrationPhase.CleanupPending, client.registration.value.phase)
            assertFalse(client.isActivated)
            assertEquals(
                PushHandlingResult.Inactive,
                client.handlePush(
                    mapOf("type" to "directstream.incoming", "protocolVersion" to "1"),
                ),
            )
            assertTrue(client.activate() is CallResult.Failure)
            offline = false
            assertEquals(CallResult.Success, client.activate())
            waitFor {
                client.registration.value.device?.code != null &&
                    client.registration.value.device?.code != first
            }
            assertFalse(client.video.localAvailable.value)
        }
    }

    @Test
    fun loginNamesCanChangeWhilePendingWithoutChangingExistingSnapshots() = runBlocking {
        val server = InMemoryCallServer()
        val local = InMemoryCallProvider(server, "login-name-local")
        val remote = InMemoryCallProvider(server, "login-name-remote")
        isolated(local) { client ->
            assertFalse(client.isActivated)
            assertEquals(CallResult.Success, client.activate("  Alice  "))
            waitFor { client.registration.value.device?.participantName == "Alice" }
            val code = client.registration.value.device!!.code
            val pending = remote.admission.create(code, "login-name-snapshot-1234")
            assertEquals("Alice", pending.peerName)
            assertEquals(CallResult.Success, client.reviewIncoming(pending.requestId))
            assertEquals(CallResult.Success, client.updateParticipantName("Alice Updated"))
            assertEquals(code, client.registration.value.device!!.code)
            assertEquals("Alice Updated", local.admission.register().participantName)
            assertEquals("Alice", remote.admission.status(pending.requestId).peerName)
            assertEquals("pending", remote.admission.status(pending.requestId).status)
            val invalid = client.updateParticipantName("x".repeat(41)) as CallResult.Failure
            assertEquals(FailureKind.INVALID_INPUT, invalid.error.kind)
            assertEquals("Alice Updated", local.admission.register().participantName)
            assertEquals(CallResult.Success, client.updateParticipantName(null))
            assertNull(client.registration.value.device!!.participantName)
            assertNull(local.admission.register().participantName)
            assertEquals(CallResult.Success, client.decline(pending.requestId))
            val next = remote.admission.create(code, "login-name-cleared-1234")
            assertNull(next.peerName)
            remote.admission.cancel(next.requestId)
        }
    }

    @Test
    fun stagedNameSurvivesRestartAndLogoutDoesNotRestoreInitializationSeed() = runBlocking {
        val provider = InMemoryCallProvider(deviceKey = "login-name-restart")
        isolated(provider) { client ->
            assertEquals(CallResult.Success, client.updateParticipantName("Staged Name"))
            assertFalse(client.isActivated)
            assertNull(client.registration.value.device)
            assertEquals(CallResult.Success, client.activate())
            val firstCode = client.state.value.device!!.code
            client.dispose()
            val restored =
                withContext(Dispatchers.Main) {
                    DirectStreamClient.initialize(
                        context,
                        client.configuration.copy(participantName = "Initialization Seed"),
                    )
                }
            try {
                assertEquals(CallResult.Success, restored.activate())
                assertEquals("Staged Name", restored.state.value.device!!.participantName)
                assertEquals(
                    FailureKind.DISPOSED,
                    (client.deactivate() as CallResult.Failure).error.kind,
                )
                assertEquals("Staged Name", restored.state.value.device!!.participantName)
                assertEquals(firstCode, restored.state.value.device!!.code)
                assertEquals(CallResult.Success, restored.deactivate())
                assertFalse(
                    context
                        .getSharedPreferences("directstream-activation", 0)
                        .contains("participant-name"),
                )
                assertNull(restored.registration.value.device)
            } finally {
                restored.dispose()
            }
            val loggedOut =
                withContext(Dispatchers.Main) {
                    DirectStreamClient.initialize(
                        context,
                        client.configuration.copy(participantName = "Initialization Seed"),
                    )
                }
            try {
                assertEquals(CallResult.Success, loggedOut.activate())
                assertNull(loggedOut.state.value.device!!.participantName)
                assertNotEquals(firstCode, loggedOut.state.value.device!!.code)
                assertEquals(CallResult.Success, loggedOut.deactivate())
                assertEquals(CallResult.Success, loggedOut.activate("Bob"))
                assertEquals("Bob", loggedOut.state.value.device!!.participantName)
            } finally {
                loggedOut.deactivate()
                loggedOut.dispose()
            }
        }
    }

    @Test
    fun logoutClearsLocalNameOfflineAndBlocksActivationUntilRevocationCompletes() = runBlocking {
        val base = InMemoryCallProvider(deviceKey = "login-name-offline")
        var offline = false
        val provider =
            object : CallProvider by base {
                override val admission =
                    object : AdmissionProvider by base.admission {
                        override suspend fun revoke() {
                            if (offline) throw java.io.IOException("Simulated offline")
                            base.admission.revoke()
                        }
                    }
            }
        isolated(provider) { client ->
            assertEquals(CallResult.Success, client.activate("Alice"))
            offline = true
            assertTrue(client.deactivate() is CallResult.Failure)
            assertFalse(client.isActivated)
            assertNull(client.registration.value.device)
            assertFalse(
                context
                    .getSharedPreferences("directstream-activation", 0)
                    .contains("participant-name"),
            )
            assertTrue(client.activate("Bob") is CallResult.Failure)
            offline = false
            client.setVisible(true)
            waitFor { client.registration.value.phase == RegistrationPhase.Inactive }
            assertEquals(CallResult.Success, client.activate())
            assertEquals("Bob", client.state.value.device!!.participantName)
        }
    }

    @Test
    fun logoutCannotBeUndoneByAnInFlightNameUpdate() = runBlocking {
        val base = InMemoryCallProvider(deviceKey = "login-name-race")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val provider =
            object : CallProvider by base {
                override val admission =
                    object : AdmissionProvider by base.admission {
                        override suspend fun register(participantName: String?): Device {
                            if (participantName == "Delayed Name") {
                                started.complete(Unit)
                                finish.await()
                            }
                            return base.admission.register(participantName)
                        }
                    }
            }
        isolated(provider) { client ->
            assertEquals(CallResult.Success, client.activate("Alice"))
            val update = async { client.updateParticipantName("Delayed Name") }
            started.await()
            val logout = async { client.deactivate() }
            waitFor { !client.isActivated }
            assertFalse(
                context
                    .getSharedPreferences("directstream-activation", 0)
                    .contains("participant-name"),
            )
            finish.complete(Unit)
            assertTrue(update.await() is CallResult.Failure)
            assertEquals(CallResult.Success, logout.await())
            assertEquals(CallResult.Success, client.activate())
            assertNull(client.state.value.device!!.participantName)
        }
    }

    @Test
    fun pendingActionsAreScopedAndPermissionFailuresAreTyped() = runBlocking {
        // Run this case with camera permission denied; it never starts native capture.
        org.junit.Assume.assumeTrue(
            context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        val server = InMemoryCallServer()
        val local = InMemoryCallProvider(server, "release-contract-local")
        val remote = InMemoryCallProvider(server, "release-contract-remote")
        isolated(local) { client ->
            assertEquals(
                FailureKind.UNAVAILABLE,
                (client.startCall("ABC234", CallMode.Audio) as CallResult.Failure).error.kind,
            )
            assertEquals(CallResult.Success, client.activate())
            client.setVisible(true)
            val remoteCode = remote.admission.register().code
            val denied = client.startCall(remoteCode, CallMode.Video) as CallResult.Failure
            assertEquals(FailureKind.PERMISSION, denied.error.kind)
            assertTrue(android.Manifest.permission.CAMERA in denied.error.requiredPermissions)
            assertFalse(client.video.localAvailable.value)

            val incoming =
                remote.admission.create(client.state.value.device!!.code, "release-incoming-1234")
            assertEquals(CallResult.Success, client.reviewIncoming(incoming.requestId))
            val acceptDenied = client.accept(incoming.requestId) as CallResult.Failure
            assertEquals(FailureKind.PERMISSION, acceptDenied.error.kind)
            assertTrue(android.Manifest.permission.CAMERA in acceptDenied.error.requiredPermissions)
            assertEquals("pending", remote.admission.status(incoming.requestId).status)
            for (result in
                listOf(
                    client.accept("obsolete-call"),
                    client.decline("obsolete-call"),
                    client.cancel("obsolete-call"),
                    client.hangup("obsolete-call"),
                )) assertTrue(result is CallResult.Failure)
            assertEquals("pending", remote.admission.status(incoming.requestId).status)
            assertEquals(CallResult.Success, client.decline(incoming.requestId))
            assertEquals("rejected", remote.admission.status(incoming.requestId).status)

            assertEquals(CallResult.Success, client.startCall(remoteCode, CallMode.Audio))
            waitFor { client.callState.value.session is StreamSessionState.AwaitingApproval }
            val outgoingId = client.callState.value.callId!!
            assertEquals(CallResult.Success, client.cancel(outgoingId))
            assertEquals("cancelled", remote.admission.status(outgoingId).status)
            assertFalse(client.video.localAvailable.value)
        }
    }

    @Test
    fun audioOnlyCallUsesTelecomAndNeverStartsCamera() = runBlocking {
        val server = InMemoryCallServer()
        val caller = InMemoryCallProvider(server, "sdk-first-release-caller")
        val callee = InMemoryCallProvider(server, "sdk-first-release-callee")
        val peerRtc = WebRtcClient(context)
        val peer =
            StreamController(
                context,
                callee,
                peerRtc,
                callService =
                    object : CallServiceBridge {
                        override suspend fun start(microphone: Boolean) = Unit

                        override fun stop() = Unit
                    },
            )
        isolated(caller) { client ->
            client.activate()
            peer.setVisible(true)
            peer.initialize()
            val scenario =
                withContext(Dispatchers.Default) {
                    ActivityScenario.launch(SdkTestActivity::class.java)
                }
            try {
                waitFor { client.state.value.ready && peer.state.value.ready }
                assertEquals(
                    CallResult.Success,
                    client.startCall(peer.state.value.device!!.code, CallMode.Audio),
                )
                waitFor { peer.state.value.session is StreamSessionState.IncomingRequest }
                assertEquals(
                    CallMode.Audio,
                    (peer.state.value.session as StreamSessionState.IncomingRequest).request.mode,
                )
                peer.approveConnection()
                waitFor {
                    client.callState.value.session is StreamSessionState.Connected &&
                        peer.state.value.session is StreamSessionState.Connected
                }
                val callId = client.callState.value.callId
                assertEquals(CallResult.Success, client.updateParticipantName("Connected Name"))
                assertEquals(callId, client.callState.value.callId)
                assertTrue(client.callState.value.session is StreamSessionState.Connected)
                assertEquals("Connected Name", caller.admission.register().participantName)
                callee.admission.sessionActive(
                    client.state.value.latestAttempt!!.let {
                        callee.admission.status(it.requestId).roomId!!
                    },
                )
                assertNull(client.codecs.value.selection!!.video)
                assertEquals(CodecId.OPUS, client.codecs.value.selection!!.audio)
                assertFalse(client.video.localAvailable.value)
                assertNull(peerRtc.localPreview.value)
                assertFalse(client.media.value.microphoneAvailable)
                waitFor { client.media.value.outputs.isNotEmpty() }
                assertEquals(CallResult.Success, client.hangup(client.callState.value.callId!!))
                waitFor { client.callState.value.session == StreamSessionState.Idle }
                waitFor {
                    context
                        .getSystemService(android.app.NotificationManager::class.java)
                        .activeNotifications
                        .none { it.id == 41 }
                }
            } finally {
                withContext(Dispatchers.Default) { scenario.close() }
                peer.dispose()
                peerRtc.dispose()
            }
        }
    }
}
