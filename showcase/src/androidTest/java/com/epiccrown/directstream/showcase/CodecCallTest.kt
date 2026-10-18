package com.epiccrown.directstream.showcase

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.internal.media.WebRtcClient
import com.epiccrown.directstream.internal.session.StreamController
import com.epiccrown.directstream.testing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import timber.log.Timber

/**
 * Real native codecs/SDP/RTP and approved-room protocol; no Firebase or Internet route is needed.
 */
class CodecCallTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            *(StreamPermissions.required(Build.VERSION.SDK_INT, Role.SENDER) +
                    android.Manifest.permission.RECORD_AUDIO)
                .toTypedArray(),
        )

    private class Service : CallServiceBridge {
        var starts = 0

        override suspend fun start(microphone: Boolean) {
            starts++
        }

        override fun stop() = Unit
    }

    @Volatile private var corruptNextVideoAnswer: String? = null

    private fun provider(server: InMemoryCallServer, key: String): CallProvider {
        val base = InMemoryCallProvider(server, key)
        return object : CallProvider by base {
            override val signaling =
                object : SignalingProvider by base.signaling {
                    override suspend fun publishDescription(
                        id: String,
                        role: Role,
                        description: SignalDescription,
                    ) {
                        val corrupt = corruptNextVideoAnswer
                        val value =
                            if (
                                description.type == "answer" &&
                                    corrupt != null &&
                                    description.sdp.contains("$corrupt/90000")
                            ) {
                                corruptNextVideoAnswer = null
                                description.copy(
                                    sdp = description.sdp.replace("$corrupt/90000", "VP8/90000"),
                                )
                            } else description
                        base.signaling.publishDescription(id, role, value)
                    }
                }
            override val relay =
                object : RelayProvider {
                    override suspend fun iceServers(roomId: String) =
                        TurnConfiguration(emptyList(), Long.MAX_VALUE)
                }
        }
    }

    @Test
    fun nativeVp9Av1AudioAndNextCallPoliciesRemainSeparate() = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = InMemoryCallServer()
        val hostRtc = WebRtcClient(context, TestColorCamera())
        val guestRtc = WebRtcClient(context, TestColorCamera())
        val service = Service()
        val a =
            StreamController(
                context,
                provider(server, "codec-native-host"),
                hostRtc,
                callService = service,
            )
        val b =
            StreamController(
                context,
                provider(server, "codec-native-guest"),
                guestRtc,
                callService = Service(),
            )
        suspend fun waitFor(condition: () -> Boolean) =
            withTimeout(25000) {
                while (!condition()) {
                    listOf(a, b).forEach {
                        if (it.state.value.session is StreamSessionState.Failed)
                            throw AssertionError(
                                "Call failed: ${it.state.value.codecs.failure}/${it.state.value.failure?.kind}",
                            )
                    }
                    delay(40)
                }
            }
        suspend fun waitCodec(id: CodecId, audio: CodecId = CodecId.OPUS) {
            try {
                waitFor {
                    listOf(a, b).all { c ->
                        val s = c.state.value
                        s.codecs.phase == CodecPhase.ACTIVE &&
                            s.codecs.selection == CodecSelection(id, audio) &&
                            listOf(s.stats.videoIn, s.stats.videoOut).all {
                                it.codec.equals(id.name, true) &&
                                    it.frames != null &&
                                    it.frames!! > 0 &&
                                    (it.bitrateKbps ?: 0.0) > 0.0
                            } &&
                            // One emulator cannot guarantee two active microphone recorders.
                            // Native SDP validates audio selection even while stats are silent.
                            listOf(s.stats.audioIn, s.stats.audioOut)
                                .filter { (it.bitrateKbps ?: 0.0) > 0.0 }
                                .all { it.codec.equals(audio.name, true) }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                fun summary(c: StreamController): String {
                    val s = c.state.value
                    return "${s.codecs.phase}/${s.codecs.selection}: video=${s.stats.videoIn.codec}/${s.stats.videoOut.codec} frames=${s.stats.videoIn.frames}/${s.stats.videoOut.frames} audio=${s.stats.audioIn.codec}/${s.stats.audioOut.codec} failure=${s.codecs.failure}"
                }
                throw AssertionError(
                    "Expected $id/$audio; host=${summary(a)}; guest=${summary(b)}",
                    timeout,
                )
            }
        }
        try {
            a.setVisible(true)
            b.setVisible(true)
            a.initialize()
            b.initialize()
            a.discoverCodecs()
            b.discoverCodecs()
            val ac = a.state.value.codecs.capabilities!!
            val bc = b.state.value.codecs.capabilities!!
            val available =
                listOf(CodecId.VP9, CodecId.AV1).filter {
                    ac.find(it)?.bidirectional == true && bc.find(it)?.bidirectional == true
                }
            Timber.tag("CodecTest")
                .i(
                    "Native VP9 available=%s; AV1 available=%s",
                    CodecId.VP9 in available,
                    CodecId.AV1 in available,
                )
            // Explicitly exercise every available requested codec; absence must remain observable.
            val first = available.firstOrNull() ?: CodecId.VP8
            a.setNextCallCodecs(CallCodecPreferences(video = CodecPolicy.Enforce(first)))
            a.configureMicrophone(true)
            b.configureMicrophone(true)
            b.requestConnection(a.state.value.device!!.code)
            waitFor { a.state.value.session is StreamSessionState.IncomingRequest }
            assertEquals(0, service.starts)
            a.setCallScreenVisible(false)
            a.approveConnection()
            waitFor {
                a.state.value.codecs.phase == CodecPhase.ACTIVE &&
                    a.state.value.session is StreamSessionState.Connected
            }
            assertTrue(a.state.value.media.localVideoPaused)
            a.setCallScreenVisible(true)
            waitCodec(first)
            a.switchCodecs(CallCodecPreferences())
            waitCodec(
                CodecSelector.select(
                        CodecEndpoint(ac, CallCodecPreferences()),
                        CodecEndpoint(bc, CallCodecPreferences()),
                    )
                    .video!!,
            )
            val auto = requireNotNull(a.state.value.codecs.selection!!.video)
            if (CodecId.VP9 in available && auto != CodecId.VP9) {
                corruptNextVideoAnswer = "VP9"
                a.switchCodecs(CallCodecPreferences(video = CodecPolicy.Prefer(CodecId.VP9)))
                waitFor {
                    a.state.value.codecs.phase == CodecPhase.ACTIVE &&
                        a.state.value.codecs.failure == CodecFailureReason.NEGOTIATION_FAILED
                }
                waitCodec(auto)
            }
            for (id in available) {
                b.switchCodecs(CallCodecPreferences(video = CodecPolicy.Enforce(id)))
                waitCodec(id)
            }
            val current = requireNotNull(b.state.value.codecs.selection!!.video)
            val incompatible = if (current == CodecId.VP8) CodecId.VP9 else CodecId.VP8
            if (ac.find(incompatible)?.bidirectional == true) {
                a.switchCodecs(CallCodecPreferences(video = CodecPolicy.Enforce(incompatible)))
                waitFor { a.state.value.codecs.failure == CodecFailureReason.INCOMPATIBLE_PEER }
                assertEquals(current, a.state.value.codecs.selection!!.video)
            }
            val pcmu = CodecId("audio/pcmu")
            if (ac.find(pcmu)?.bidirectional == true && bc.find(pcmu)?.bidirectional == true) {
                b.switchCodecs(
                    CallCodecPreferences(
                        video = CodecPolicy.Enforce(current),
                        audio = CodecPolicy.Enforce(pcmu),
                    ),
                )
                waitCodec(current, pcmu)
            }
            val active = a.state.value.codecs.selection
            a.setNextCallCodecs(CallCodecPreferences(video = CodecPolicy.Prefer(first)))
            assertEquals(active, a.state.value.codecs.selection)
            a.setVisible(false)
            waitFor { a.state.value.media.localVideoPaused }
            b.switchCodecs(CallCodecPreferences(video = CodecPolicy.Enforce(current)))
            waitFor {
                a.state.value.codecs.phase == CodecPhase.ACTIVE &&
                    a.state.value.codecs.selection?.audio == CodecId.OPUS
            }
            assertTrue(a.state.value.media.localVideoPaused)
            a.setVisible(true)
            waitFor { !a.state.value.media.localVideoPaused }
            waitCodec(current)
            assertEquals(1, service.starts)
            b.disconnect()
            waitFor { a.state.value.session is StreamSessionState.Idle }
            assertNull(hostRtc.localPreview.value)
        } finally {
            a.dispose()
            b.dispose()
            scenario.close()
        }
    }
}
