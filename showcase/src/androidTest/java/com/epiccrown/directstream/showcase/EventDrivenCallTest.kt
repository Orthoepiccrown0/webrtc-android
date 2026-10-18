package com.epiccrown.directstream.showcase

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.epiccrown.directstream.api.AdmissionProvider
import com.epiccrown.directstream.api.CallProvider
import com.epiccrown.directstream.api.CallServiceBridge
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.RelayProvider
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.StreamPermissions
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.api.TurnConfiguration
import com.epiccrown.directstream.internal.media.WebRtcClient
import com.epiccrown.directstream.internal.session.StreamController
import com.epiccrown.directstream.testing.InMemoryCallProvider
import com.epiccrown.directstream.testing.InMemoryCallServer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real media engines and admission streams; no Firebase/Worker/network-service dependency. */
class EventDrivenCallTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            *StreamPermissions.required(Build.VERSION.SDK_INT, Role.SENDER).toTypedArray(),
        )

    private class Service : CallServiceBridge {
        var active = false

        override suspend fun start(microphone: Boolean) {
            active = true
        }

        override fun stop() {
            active = false
        }
    }

    @Test
    fun approvalStartsMediaAndSessionEventsEndBothPeersWithoutHeartbeats() = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = InMemoryCallServer()
        val reads = AtomicInteger()
        fun provider(key: String): CallProvider {
            val base = InMemoryCallProvider(server, key)
            return object : CallProvider by base {
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

                        override suspend fun sessionActive(roomId: String) {
                            reads.incrementAndGet()
                            base.admission.sessionActive(roomId)
                        }
                    }
                override val relay =
                    object : RelayProvider {
                        override suspend fun iceServers(roomId: String) =
                            TurnConfiguration(emptyList(), Long.MAX_VALUE)
                    }
            }
        }
        val hostProvider = provider("event-media-host")
        val callerProvider = provider("event-media-caller")
        val hostRtc = WebRtcClient(context, TestColorCamera())
        val callerRtc = WebRtcClient(context, TestColorCamera())
        val hostService = Service()
        val callerService = Service()
        val host =
            StreamController(
                context,
                hostProvider,
                hostRtc,
                callService = hostService,
            )
        val caller =
            StreamController(
                context,
                callerProvider,
                callerRtc,
                callService = callerService,
            )
        try {
            host.setVisible(true)
            caller.setVisible(true)
            host.initialize()
            caller.initialize()
            caller.requestConnection(host.state.value.device!!.code)
            withTimeout(3_000) {
                while (host.state.value.session !is StreamSessionState.IncomingRequest) delay(20)
            }
            assertFalse(hostService.active)
            assertFalse(callerService.active)
            val request = (host.state.value.session as StreamSessionState.IncomingRequest).request
            host.approveConnection()
            withTimeout(20_000) {
                while (
                    host.state.value.session !is StreamSessionState.Connected ||
                        caller.state.value.session !is StreamSessionState.Connected
                ) delay(30)
            }
            assertTrue(hostService.active)
            assertTrue(callerService.active)
            delay(200)
            val baseline = reads.get()
            // Longer than the former 30-second session heartbeat interval.
            delay(31_500)
            assertEquals("A healthy call must not poll its server", baseline, reads.get())
            val room = hostProvider.admission.status(request.requestId).roomId!!
            // End admission directly: no data-channel hangup, so only the event can stop the peers.
            hostProvider.admission.end(room)
            // A new inbox event can arrive while the previous media engine is still releasing.
            callerProvider.admission.create(
                host.state.value.device!!.code,
                "next-call-during-teardown",
            )
            withTimeout(5_000) {
                while (
                    host.state.value.session !is StreamSessionState.IncomingRequest ||
                        caller.state.value.session !is StreamSessionState.Idle
                ) delay(20)
            }
            assertEquals(
                "next-call-during-teardown",
                (host.state.value.session as StreamSessionState.IncomingRequest).request.requestId,
            )
            assertFalse(hostService.active)
            assertFalse(callerService.active)
            assertNull(hostRtc.localPreview.value)
            assertNull(callerRtc.localPreview.value)
            host.rejectConnection()
            assertEquals(StreamSessionState.Idle, host.state.value.session)
        } finally {
            host.dispose()
            caller.dispose()
            scenario.close()
        }
    }
}
