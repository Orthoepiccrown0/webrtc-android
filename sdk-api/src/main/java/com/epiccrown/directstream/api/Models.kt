package com.epiccrown.directstream.api

import java.util.Locale

object DeviceCode {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    fun normalize(input: String): String {
        val code = input.filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT)
        require(code.length == 6 && code.all { it in ALPHABET }) {
            "Enter a six-character device code."
        }
        return code
    }

    fun format(code: String): String = normalize(code).let { "${it.take(3)}-${it.drop(3)}" }
}

@kotlinx.serialization.Serializable
enum class CallMode {
    Audio,
    Video,
}

enum class Role {
    SENDER,
    RECEIVER,
}

enum class ConnectionType {
    DIRECT,
    RELAYED,
    UNKNOWN,
}

data class Device(
    val code: String,
    val requestsEnabled: Boolean = true,
    val activeRoomId: String? = null,
    val relayAvailable: Boolean? = null,
    val supportedCallModes: Set<CallMode> = CallMode.entries.toSet(),
    val registrationRevocationSupported: Boolean = true,
    val participantName: String? = null,
)

data class PairingRequest(
    val requestId: String,
    val verificationValue: String,
    val expiresAt: Long,
    val status: String = "pending",
    val roomId: String? = null,
    val role: Role = Role.RECEIVER,
    val peerCode: String? = null,
    val createdAt: Long? = null,
    val mode: CallMode = CallMode.Video,
    val peerName: String? = null,
)

data class Room(
    val roomId: String,
    val hostUid: String,
    val guestUid: String,
    val expiresAt: Long,
    val mode: CallMode = CallMode.Video,
)

data class IceServer(val urls: List<String>, val username: String = "", val credential: String = "")

data class TurnConfiguration(val servers: List<IceServer>, val expiresAt: Long)

data class SignalDescription(val type: String, val sdp: String, val negotiationId: String? = null)

data class SignalCandidate(
    val id: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
)

data class StreamStats(
    val videoIn: MediaMetrics = MediaMetrics(),
    val videoOut: MediaMetrics = MediaMetrics(),
    val audioIn: MediaMetrics = MediaMetrics(),
    val audioOut: MediaMetrics = MediaMetrics(),
    val transport: ConnectionType = ConnectionType.UNKNOWN,
    val rttMs: Double? = null,
    val iceState: String = "NEW",
    val peerState: String = "NEW",
    val sampledAtMs: Long = 0L,
    val elapsedMs: Long = 0L,
) {
    val codec: String
        get() = videoIn.codec ?: "Unknown"

    val width: Int
        get() = videoIn.width ?: 0

    val height: Int
        get() = videoIn.height ?: 0

    val fps: Double
        get() = videoIn.fps ?: 0.0

    val bitrateKbps: Double
        get() = videoIn.bitrateKbps ?: 0.0

    val packetsLost: Long
        get() = videoIn.packetsLost ?: 0L

    val droppedFrames: Long
        get() = videoIn.droppedFrames ?: 0L
}

sealed interface StreamSessionState {
    data object Idle : StreamSessionState

    data object RequestingConnection : StreamSessionState

    data class AwaitingApproval(val request: PairingRequest) : StreamSessionState

    data class IncomingRequest(val request: PairingRequest) : StreamSessionState

    data object Negotiating : StreamSessionState

    data class Connected(val role: Role) : StreamSessionState

    data object Reconnecting : StreamSessionState

    data class Failed(val reason: String) : StreamSessionState
}

data class CallSnapshot(
    val codecs: CallCodecState = CallCodecState(),
    val ready: Boolean = false,
    val mode: CallMode = CallMode.Video,
    val latestAttempt: CallAttempt? = null,
    val device: Device? = null,
    val session: StreamSessionState = StreamSessionState.Idle,
    val stats: StreamStats = StreamStats(),
    val busy: Boolean = false,
    val failure: CallFailure? = null,
    val error: String? = null,
    val outcome: String? = null,
    val media: CallMediaState = CallMediaState(),
    val online: Boolean = true,
    val peerDisplayName: String? = null,
    val peerCode: String? = null,
    val showDebugIdentifiers: Boolean = false,
)
