package com.epiccrown.directstream.internal.media

import com.epiccrown.directstream.api.ConnectionType
import com.epiccrown.directstream.api.MediaMetrics
import com.epiccrown.directstream.api.StreamStats
import org.webrtc.RTCStatsReport

/** Counter baselines belong to RTP stream IDs, not a direction or a codec name. */
class RtcStatsTracker {
    private data class Counter(val bytes: Long, val timeUs: Double, val codecId: String?)

    private val previous = mutableMapOf<String, Counter>()

    fun reset() = previous.clear()

    fun parse(
        report: RTCStatsReport,
        nowMs: Long,
        elapsedMs: Long,
        ice: String,
        peer: String,
    ): StreamStats {
        val rows = report.statsMap
        val used = mutableSetOf<String>()
        fun media(kind: String, outgoing: Boolean): MediaMetrics {
            val rtp =
                rows.values.firstOrNull {
                    it.type == (if (outgoing) "outbound-rtp" else "inbound-rtp") &&
                        (it.members["kind"] == kind || it.members["mediaType"] == kind) &&
                        rows[it.members["codecId"]]
                            ?.members
                            ?.get("mimeType")
                            ?.toString()
                            ?.substringAfter('/')
                            ?.lowercase() !in listOf("rtx", "red", "ulpfec", "flexfec-03")
                } ?: return MediaMetrics()
            fun num(key: String) = (rtp.members[key] as? Number)?.toDouble()
            val bytes = num(if (outgoing) "bytesSent" else "bytesReceived")?.toLong()
            var rate: Double? = null
            if (bytes != null) {
                used.add(rtp.id)
                val old =
                    previous.put(
                        rtp.id,
                        Counter(bytes, report.timestampUs, rtp.members["codecId"]?.toString()),
                    )
                if (
                    old != null &&
                        old.codecId == rtp.members["codecId"]?.toString() &&
                        bytes >= old.bytes &&
                        report.timestampUs > old.timeUs
                )
                    rate = (bytes - old.bytes) * 8_000.0 / (report.timestampUs - old.timeUs)
            }
            val remote =
                rows.values.firstOrNull {
                    it.type == "remote-inbound-rtp" && it.members["localId"] == rtp.id
                }
            val lost =
                if (outgoing) (remote?.members?.get("packetsLost") as? Number)?.toLong()
                else num("packetsLost")?.toLong()
            return MediaMetrics(
                rows[rtp.members["codecId"]]
                    ?.members
                    ?.get("mimeType")
                    ?.toString()
                    ?.substringAfter('/'),
                rate,
                num("frameWidth")?.toInt(),
                num("frameHeight")?.toInt(),
                num("framesPerSecond"),
                lost,
                num("framesDropped")?.toLong(),
                num(if (outgoing) "framesEncoded" else "framesDecoded")?.toLong(),
                num("audioLevel"),
                rows[rtp.members["codecId"]]
                    ?.members
                    ?.get("sdpFmtpLine")
                    ?.toString()
                    ?.split(';')
                    ?.map { it.trim() }
                    ?.filter {
                        it.substringBefore('=') in
                            listOf(
                                "profile-level-id",
                                "packetization-mode",
                                "profile-id",
                                "profile",
                                "level-idx",
                                "tier",
                            )
                    }
                    ?.joinToString("; ")
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(160),
                rtp.members[if (outgoing) "encoderImplementation" else "decoderImplementation"]
                    ?.toString()
                    ?.replace(Regex("[\\r\\n]"), " ")
                    ?.take(120),
            )
        }
        val transport =
            rows.values.firstOrNull {
                it.type == "transport" && it.members["selectedCandidatePairId"] != null
            }
        val pair =
            rows[transport?.members?.get("selectedCandidatePairId")]
                ?: rows.values.firstOrNull {
                    it.type == "candidate-pair" &&
                        it.members["nominated"] == true &&
                        it.members["state"] == "succeeded"
                }
        val local = rows[pair?.members?.get("localCandidateId")]?.members?.get("candidateType")
        val remote = rows[pair?.members?.get("remoteCandidateId")]?.members?.get("candidateType")
        val result =
            StreamStats(
                media("video", false),
                media("video", true),
                media("audio", false),
                media("audio", true),
                if (local == "relay" || remote == "relay") ConnectionType.RELAYED
                else if (pair != null) ConnectionType.DIRECT else ConnectionType.UNKNOWN,
                (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000),
                ice,
                peer,
                nowMs,
                elapsedMs,
            )
        previous.keys.retainAll(used)
        return result
    }
}
