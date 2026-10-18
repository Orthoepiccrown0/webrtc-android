package com.epiccrown.directstream.showcase

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.epiccrown.directstream.api.ConnectionType
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.SignalCandidate
import com.epiccrown.directstream.api.SignalDescription
import com.epiccrown.directstream.api.StreamPermissions
import com.epiccrown.directstream.internal.media.WebRtcClient
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.VideoSink
import timber.log.Timber

/**
 * Exercises native camera capture, SDP, trickled ICE, encoding and decoding without cloud services.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcLoopbackTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            *(StreamPermissions.required(Build.VERSION.SDK_INT, Role.SENDER) +
                    android.Manifest.permission.RECORD_AUDIO)
                .toTypedArray(),
        )

    @Test
    fun cameraFramesReachReceiverAndResourcesClose() = runBlocking {
        val messages = Collections.synchronizedList(mutableListOf<Pair<String?, String>>())
        val logging =
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (tag == "WebRtc" || tag == "WebRtcStats") messages.add(tag to message)
                }
            }
        Timber.plant(logging)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sender = WebRtcClient(context)
        val receiver = WebRtcClient(context, TestColorCamera())
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val offers = Channel<SignalDescription>(Channel.UNLIMITED)
        val answers = Channel<SignalDescription>(Channel.UNLIMITED)
        val hostIce = Channel<SignalCandidate>(Channel.UNLIMITED)
        val guestIce = Channel<SignalCandidate>(Channel.UNLIMITED)
        val frames = AtomicInteger()
        val received = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val remoteFrames = AtomicInteger()
        val returnSink = VideoSink { frame ->
            val yuv = frame.buffer.toI420()
            if (yuv != null) {
                val y = yuv.dataY.get(0).toInt() and 255
                yuv.release()
                if (y in 77..87 && remoteFrames.incrementAndGet() >= 5) returned.complete(Unit)
            }
        }
        val sink = VideoSink { frame ->
            if (frame.buffer.width > 0 && frames.incrementAndGet() >= 5) received.complete(Unit)
        }
        try {
            receiver.attachSink(sink)
            sender.attachSink(returnSink)
            receiver.start(
                Role.RECEIVER,
                emptyList(),
                false,
                { guestIce.trySend(it) },
                {},
                { answers.trySend(it) },
            )
            jobs.launch { for (offer in offers) receiver.remoteDescription(offer) }
            jobs.launch { for (answer in answers) sender.remoteDescription(answer) }
            jobs.launch { for (candidate in hostIce) receiver.remoteCandidate(candidate) }
            jobs.launch { for (candidate in guestIce) sender.remoteCandidate(candidate) }
            sender.start(
                Role.SENDER,
                emptyList(),
                false,
                { hostIce.trySend(it) },
                {},
                { offers.trySend(it) },
                microphone = true,
            )
            withTimeout(45_000) {
                received.await()
                returned.await()
            }
            assertNotSame(sender.localPreview.value, sender.track.value)
            assertNotSame(receiver.localPreview.value, receiver.track.value)
            assertTrue(remoteFrames.get() >= 5)
            assertTrue(frames.get() >= 5)
            withTimeout(10_000) {
                while (receiver.stats.value.codec == "Unknown") {
                    receiver.collectStats()
                    delay(300)
                }
            }
            assertTrue(
                receiver.stats.value.codec.uppercase() in listOf("AV1", "H264", "VP8", "VP9"),
            )
            assertEquals(ConnectionType.DIRECT, receiver.stats.value.transport)
            withTimeout(10_000) {
                while (receiver.stats.value.audioIn.codec == null) {
                    sender.collectStats()
                    receiver.collectStats()
                    delay(300)
                }
            }
            assertEquals("opus", receiver.stats.value.audioIn.codec?.lowercase())
            assertFalse(receiver.media.value.microphoneAvailable)
            sender.setMuted(true)
            withTimeout(5_000) { while (receiver.media.value.remoteMuted != true) delay(100) }
            assertNotNull(
                receiver.track.value,
            ) // Audio tracks/control messages cannot replace video.
            sender.setVideoPaused(true)
            withTimeout(5_000) { while (!receiver.media.value.remoteVideoPaused) delay(100) }
            assertTrue(sender.media.value.localVideoPaused)
            sender.setVideoPaused(false)
            sender.setMuted(false)
            withTimeout(5_000) {
                while (
                    receiver.media.value.remoteVideoPaused ||
                        receiver.media.value.remoteMuted != false
                ) delay(100)
            }
            assertNotNull(receiver.track.value)
            // Poll at the real app cadence for longer than one log interval.
            repeat(9) {
                sender.collectStats()
                receiver.collectStats()
                delay(2_000)
            }
            val captured = synchronized(messages) { messages.toList() }
            assertTrue(captured.any { it.second.startsWith("First camera frame available") })
            assertTrue(captured.any { it.second.startsWith("First video RTP received") })
            assertTrue(captured.any { it.second.startsWith("Selected route: DIRECT") })
            assertTrue(
                "Stats summaries must remain bounded for both peers",
                captured.count { it.first == "WebRtcStats" } in 2..4,
            )
        } finally {
            jobs.cancel()
            sender.close()
            receiver.close()
            val detached = CompletableDeferred<Unit>()
            receiver.detachSink(sink) { detached.complete(Unit) }
            withTimeout(5_000) { detached.await() }
            val senderDetached = CompletableDeferred<Unit>()
            sender.detachSink(returnSink) { senderDetached.complete(Unit) }
            withTimeout(5_000) { senderDetached.await() }
            assertNull(sender.track.value)
            assertNull(receiver.track.value)
            assertNull(sender.localPreview.value)
            assertNull(receiver.localPreview.value)
            scenario.close()
            Timber.uproot(logging)
        }
    }
}
