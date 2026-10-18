package com.epiccrown.directstream.showcase.ui

import com.epiccrown.directstream.api.AudioOutput
import com.epiccrown.directstream.api.CallDirection
import com.epiccrown.directstream.api.CallMediaState
import com.epiccrown.directstream.api.CallOutcome
import com.epiccrown.directstream.api.ConnectionType
import com.epiccrown.directstream.api.Device
import com.epiccrown.directstream.api.MediaMetrics
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.api.StreamStats

/**
 * Synthetic values only. Preview compositions never initialize a repository or a native media
 * client.
 */
internal object ShowcaseData {
    private val now = System.currentTimeMillis()
    val request
        get() =
            PairingRequest(
                "preview-request",
                "482615",
                System.currentTimeMillis() + 240_000,
                peerCode = "DEF567",
                createdAt = now - 60_000,
            )

    val recent =
        RecentDevice(
            "preview-device",
            "DEF567",
            peerName = "Kitchen",
            requestId = "preview-recent",
            at = now - 3_600_000,
            direction = CallDirection.OUTGOING,
            outcome = CallOutcome.COMPLETED,
        )
    val home =
        ShowcaseState(
            ready = true,
            device = Device("ABC234"),
            recents =
                listOf(
                    recent,
                    RecentDevice(
                        "preview-missed",
                        "GHJ789",
                        requestId = "preview-missed-request",
                        at = now - 86_400_000,
                        direction = CallDirection.INCOMING,
                        outcome = CallOutcome.MISSED,
                    ),
                ),
        )
    val waiting = home.copy(session = StreamSessionState.AwaitingApproval(request))
    val incoming =
        home.copy(session = StreamSessionState.IncomingRequest(request.copy(role = Role.SENDER)))
    val media =
        CallMediaState(
            microphoneAvailable = true,
            muted = false,
            output = AudioOutput(1, "Speaker"),
            outputs =
                listOf(
                    AudioOutput(1, "Speaker"),
                    AudioOutput(2, "Earpiece"),
                    AudioOutput(3, "Headset", bluetooth = true),
                ),
        )
    val call =
        home.copy(
            session = StreamSessionState.Connected(Role.RECEIVER),
            media = media,
            stats =
                StreamStats(
                    videoIn = MediaMetrics("VP8", 850.0, 1280, 720, 30.0, 0, 2),
                    videoOut = MediaMetrics("VP8", 720.0, 960, 720, 30.0, 0, 0),
                    audioIn =
                        MediaMetrics(
                            codec = "Opus",
                            bitrateKbps = 28.0,
                            packetsLost = 0,
                            audioLevel = .15,
                        ),
                    audioOut = MediaMetrics(codec = "Opus", bitrateKbps = 24.0, packetsLost = 0),
                    transport = ConnectionType.DIRECT,
                    rttMs = 32.0,
                    iceState = "CONNECTED",
                    peerState = "CONNECTED",
                    elapsedMs = 65_000,
                    sampledAtMs = 1,
                ),
        )
}
