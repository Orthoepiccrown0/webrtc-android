package com.epiccrown.directstream.internal.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class RtcStatsTrackerTest {
    private fun report(time: Long, vararg rows: RTCStats) =
        RTCStatsReport(time, rows.associateBy { it.id })

    private fun rtp(id: String, kind: String, outgoing: Boolean, bytes: Long) =
        RTCStats(
            0,
            if (outgoing) "outbound-rtp" else "inbound-rtp",
            id,
            mapOf("kind" to kind, (if (outgoing) "bytesSent" else "bytesReceived") to bytes),
        )

    @Test
    fun changingCodecResetsRatesAndReportsProfileAndImplementation() {
        val tracker = RtcStatsTracker()
        fun sample(time: Long, codec: String, bytes: Long) =
            report(
                time,
                RTCStats(
                    0,
                    "codec",
                    codec,
                    mapOf(
                        "mimeType" to "video/$codec",
                        "sdpFmtpLine" to "profile-id=0;private-ignored=value",
                    ),
                ),
                RTCStats(
                    0,
                    "outbound-rtp",
                    "video",
                    mapOf(
                        "kind" to "video",
                        "codecId" to codec,
                        "bytesSent" to bytes,
                        "encoderImplementation" to "libvpx",
                    ),
                ),
            )
        tracker.parse(sample(1000000, "VP8", 1000), 0, 0, "NEW", "NEW")
        val changed = tracker.parse(sample(2000000, "VP9", 9000), 0, 0, "CONNECTED", "CONNECTED")
        assertNull(changed.videoOut.bitrateKbps)
        assertEquals("profile-id=0", changed.videoOut.codecProfile)
        assertEquals("libvpx", changed.videoOut.implementation)
        assertEquals(
            8.0,
            tracker
                .parse(sample(3000000, "VP9", 10000), 0, 0, "CONNECTED", "CONNECTED")
                .videoOut
                .bitrateKbps!!,
            .001,
        )
    }

    @Test
    fun independentAudioAndVideoRatesAreInKilobitsPerSecond() {
        val tracker = RtcStatsTracker()
        tracker.parse(
            report(1_000_000, rtp("vi", "video", false, 100), rtp("ao", "audio", true, 10)),
            0,
            0,
            "NEW",
            "NEW",
        )
        val result =
            tracker.parse(
                report(
                    3_000_000,
                    rtp("vi", "video", false, 250100),
                    rtp("ao", "audio", true, 6010),
                ),
                2000,
                2000,
                "CONNECTED",
                "CONNECTED",
            )
        assertEquals(1000.0, result.videoIn.bitrateKbps!!, .001)
        assertEquals(24.0, result.audioOut.bitrateKbps!!, .001)
        assertNull(result.audioIn.bitrateKbps)
        assertNull(result.videoOut.fps)
    }

    @Test
    fun counterResetsAndNewStreamIdsDoNotProduceSpikes() {
        val tracker = RtcStatsTracker()
        tracker.parse(report(1_000_000, rtp("v", "video", false, 9000)), 0, 0, "NEW", "NEW")
        assertNull(
            tracker
                .parse(report(2_000_000, rtp("v", "video", false, 100)), 0, 0, "NEW", "NEW")
                .videoIn
                .bitrateKbps,
        )
        assertEquals(
            8.0,
            tracker
                .parse(report(3_000_000, rtp("v", "video", false, 1100)), 0, 0, "NEW", "NEW")
                .videoIn
                .bitrateKbps!!,
            .001,
        )
        assertNull(
            tracker
                .parse(report(4_000_000, rtp("new", "video", false, 900000)), 0, 0, "NEW", "NEW")
                .videoIn
                .bitrateKbps,
        )
    }

    @Test
    fun silenceIsZeroTrafficAndMissingMetricsAreUnavailable() {
        val tracker = RtcStatsTracker()
        tracker.parse(report(1_000_000, rtp("a", "audio", false, 500)), 0, 0, "NEW", "NEW")
        val silent =
            tracker.parse(report(2_000_000, rtp("a", "audio", false, 500)), 0, 0, "NEW", "NEW")
        assertEquals(0.0, silent.audioIn.bitrateKbps!!, .001)
        assertNull(silent.audioIn.packetsLost)
        val absent = tracker.parse(report(3_000_000), 0, 0, "NEW", "NEW")
        assertNull(absent.audioIn.bitrateKbps)
        assertNull(
            tracker
                .parse(report(4_000_000, rtp("a", "audio", false, 500)), 0, 0, "NEW", "NEW")
                .audioIn
                .bitrateKbps,
        )
    }

    @Test
    fun selectedRouteIsAvailableEvenBeforeRtp() {
        val tracker = RtcStatsTracker()
        val result =
            tracker.parse(
                report(
                    1_000_000,
                    RTCStats(
                        0,
                        "candidate-pair",
                        "p",
                        mapOf(
                            "nominated" to true,
                            "state" to "succeeded",
                            "localCandidateId" to "l",
                            "currentRoundTripTime" to .02,
                        ),
                    ),
                    RTCStats(0, "local-candidate", "l", mapOf("candidateType" to "relay")),
                ),
                100,
                100,
                "CONNECTED",
                "CONNECTED",
            )
        assertEquals(com.epiccrown.directstream.api.ConnectionType.RELAYED, result.transport)
        assertEquals(20.0, result.rttMs!!, .001)
        assertNull(result.videoIn.codec)
        tracker.reset()
    }
}
