package com.epiccrown.directstream.api

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Primary RTP codec family. Profiles remain native-negotiated; repair codecs are not choices. */
@Serializable
data class CodecId(val mimeType: String) {
    init {
        require(
            mimeType == mimeType.lowercase(Locale.ROOT) &&
                mimeType.matches(Regex("(video|audio)/[a-z0-9-]{1,32}")),
        )
        require(mimeType.substringAfter('/') !in auxiliaryCodecs)
    }

    val name: String
        get() = mimeType.substringAfter('/').uppercase(Locale.ROOT)

    companion object {
        val auxiliaryCodecs = setOf("rtx", "red", "ulpfec", "flexfec-03", "cn", "telephone-event")
        val VP8 = CodecId("video/vp8")
        val H264 = CodecId("video/h264")
        val VP9 = CodecId("video/vp9")
        val AV1 = CodecId("video/av1")
        val OPUS = CodecId("audio/opus")
    }
}

@Serializable
sealed interface CodecPolicy {
    @Serializable @SerialName("auto") data object Auto : CodecPolicy

    @Serializable @SerialName("prefer") data class Prefer(val codec: CodecId) : CodecPolicy

    @Serializable @SerialName("enforce") data class Enforce(val codec: CodecId) : CodecPolicy
}

@Serializable
data class CallCodecPreferences(
    val video: CodecPolicy = CodecPolicy.Auto,
    val audio: CodecPolicy = CodecPolicy.Auto,
) {
    init {
        require(video.codecOrNull()?.mimeType?.startsWith("audio/") != true)
        require(audio.codecOrNull()?.mimeType?.startsWith("video/") != true)
    }
}

fun CodecPolicy.codecOrNull(): CodecId? =
    when (this) {
        CodecPolicy.Auto -> null
        is CodecPolicy.Prefer -> codec
        is CodecPolicy.Enforce -> codec
    }

@Serializable
enum class CodecImplementation {
    HARDWARE,
    SOFTWARE,
    UNKNOWN,
}

@Serializable
data class CodecFormat(
    val clockRate: Int,
    val channels: Int? = null,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class CodecCapability(
    val codec: CodecId,
    val encoding: List<CodecFormat> = emptyList(),
    val decoding: List<CodecFormat> = emptyList(),
    val encoderImplementation: CodecImplementation = CodecImplementation.UNKNOWN,
    val decoderImplementation: CodecImplementation = CodecImplementation.UNKNOWN,
) {
    val canEncode
        get() = encoding.isNotEmpty()

    val canDecode
        get() = decoding.isNotEmpty()

    val bidirectional
        get() = canEncode && canDecode
}

@Serializable
data class CodecCapabilities(val codecs: List<CodecCapability> = emptyList()) {
    fun find(id: CodecId) = codecs.firstOrNull { it.codec == id }
}

@Serializable
data class CodecEndpoint(
    val capabilities: CodecCapabilities,
    val preferences: CallCodecPreferences,
    val mode: CallMode = CallMode.Video,
)

@Serializable data class CodecSelection(val video: CodecId?, val audio: CodecId)

@Serializable
enum class CodecFailureReason {
    UNSUPPORTED_LOCAL,
    INCOMPATIBLE_PEER,
    PEER_UNAVAILABLE,
    BUSY,
    NO_ACTIVE_CALL,
    NEGOTIATION_FAILED,
    TIMEOUT,
    SESSION_ENDED,
}

class CodecException(val reason: CodecFailureReason) : Exception(reason.name)

enum class CodecPhase {
    IDLE,
    PREPARING,
    APPLYING,
    ACTIVE,
    ROLLING_BACK,
    FAILED,
}

data class CallCodecState(
    val capabilities: CodecCapabilities? = null,
    val capabilitiesFailure: CodecFailureReason? = null,
    val nextCall: CallCodecPreferences = CallCodecPreferences(),
    val active: CallCodecPreferences? = null,
    val requested: CallCodecPreferences? = null,
    val peer: CodecEndpoint? = null,
    val selection: CodecSelection? = null,
    val transactionId: String? = null,
    val phase: CodecPhase = CodecPhase.IDLE,
    val failure: CodecFailureReason? = null,
)

/** Approved-room protocol only; never put these messages in admission or a device directory. */
@Serializable
enum class CodecSignalKind {
    CAPABILITIES,
    REQUEST,
    PREPARE,
    READY,
    COMMIT,
    APPLIED,
    COMPLETE,
    REJECT,
    ABORT,
}

@Serializable
data class CodecProposal(
    val sender: CallCodecPreferences,
    val receiver: CallCodecPreferences,
    val selection: CodecSelection,
    val restartIce: Boolean = false,
    val rollback: Boolean = false,
)

@Serializable
data class CodecSignal(
    val transactionId: String,
    val kind: CodecSignalKind,
    val endpoint: CodecEndpoint? = null,
    val preferences: CallCodecPreferences? = null,
    val proposal: CodecProposal? = null,
    val failure: CodecFailureReason? = null,
)

/** Deterministic family selection. Native SDP remains the final profile/parameter authority. */
object CodecSelector {
    fun validateLocal(
        capabilities: CodecCapabilities,
        preferences: CallCodecPreferences,
        mode: CallMode = CallMode.Video,
    ) {
        (if (mode == CallMode.Video) listOf(preferences.video, preferences.audio)
            else listOf(preferences.audio))
            .forEach { policy ->
                if (
                    policy is CodecPolicy.Enforce &&
                        capabilities.find(policy.codec)?.bidirectional != true
                )
                    throw CodecException(CodecFailureReason.UNSUPPORTED_LOCAL)
            }
    }

    fun select(sender: CodecEndpoint, receiver: CodecEndpoint): CodecSelection =
        CodecSelection(
            if (sender.mode != receiver.mode)
                throw CodecException(CodecFailureReason.INCOMPATIBLE_PEER)
            else if (sender.mode == CallMode.Video) selectMedia(sender, receiver, true) else null,
            selectMedia(sender, receiver, false),
        )

    private fun selectMedia(a: CodecEndpoint, b: CodecEndpoint, video: Boolean): CodecId {
        val policies =
            if (video) listOf(a.preferences.video, b.preferences.video)
            else listOf(a.preferences.audio, b.preferences.audio)
        val enforced = policies.filterIsInstance<CodecPolicy.Enforce>().map { it.codec }.distinct()
        if (enforced.size > 1) throw CodecException(CodecFailureReason.INCOMPATIBLE_PEER)
        val common =
            a.capabilities.codecs
                .filter { left ->
                    left.codec.mimeType.startsWith(if (video) "video/" else "audio/") &&
                        b.capabilities.find(left.codec)?.let { right ->
                            left.bidirectional &&
                                right.bidirectional &&
                                compatible(left.codec, left.encoding, right.decoding) &&
                                compatible(left.codec, right.encoding, left.decoding)
                        } == true
                }
                .map { it.codec }
        enforced.singleOrNull()?.let {
            return it.takeIf(common::contains)
                ?: throw CodecException(CodecFailureReason.INCOMPATIBLE_PEER)
        }
        val preferred = policies.filterIsInstance<CodecPolicy.Prefer>().map { it.codec }
        val hardwareOrder = listOf(CodecId.AV1, CodecId.H264, CodecId.VP9, CodecId.VP8)
        val bothHardware = common.filter { id ->
            listOfNotNull(a.capabilities.find(id), b.capabilities.find(id)).all {
                it.encoderImplementation == CodecImplementation.HARDWARE &&
                    it.decoderImplementation == CodecImplementation.HARDWARE
            }
        }
        val order =
            if (video)
                preferred +
                    hardwareOrder.filter(bothHardware::contains) +
                    listOf(CodecId.VP8, CodecId.H264, CodecId.VP9, CodecId.AV1)
            else preferred + CodecId.OPUS
        return (order + common.sortedBy { it.mimeType }).firstOrNull(common::contains)
            ?: throw CodecException(CodecFailureReason.INCOMPATIBLE_PEER)
    }

    private fun compatible(
        id: CodecId,
        encoders: List<CodecFormat>,
        decoders: List<CodecFormat>,
    ): Boolean = encoders.any { a ->
        decoders.any { b ->
            a.clockRate == b.clockRate &&
                a.channels == b.channels &&
                when (id) {
                    CodecId.H264 ->
                        a.parameters.getOrDefault("packetization-mode", "0") ==
                            b.parameters.getOrDefault("packetization-mode", "0") &&
                            h264Profile(a.parameters["profile-level-id"])?.let {
                                it == h264Profile(b.parameters["profile-level-id"])
                            } == true
                    CodecId.VP9 ->
                        a.parameters.getOrDefault("profile-id", "0") ==
                            b.parameters.getOrDefault("profile-id", "0")
                    CodecId.AV1 ->
                        a.parameters.getOrDefault("profile", "0") ==
                            b.parameters.getOrDefault("profile", "0")
                    else -> true
                }
        }
    }

    // RFC 6184 profile patterns, ignoring level: SDP negotiates level asymmetry and limits.
    private fun h264Profile(value: String?): String? {
        val raw = value ?: "42e01f"
        if (!raw.matches(Regex("[0-9a-fA-F]{6}"))) return null
        val idc = raw.take(2).toIntOrNull(16) ?: return null
        val flags = raw.substring(2, 4).toIntOrNull(16) ?: return null
        val patterns =
            listOf(
                intArrayOf(0x42, 0x4f, 0x40, 0),
                intArrayOf(0x4d, 0x8f, 0x80, 0),
                intArrayOf(0x58, 0xcf, 0xc0, 0),
                intArrayOf(0x42, 0x4f, 0, 1),
                intArrayOf(0x58, 0xcf, 0x80, 1),
                intArrayOf(0x4d, 0xaf, 0, 2),
                intArrayOf(0x64, 0xff, 0x0c, 3),
                intArrayOf(0x64, 0xff, 0, 4),
                intArrayOf(0xf4, 0xff, 0, 5),
            )
        return patterns.firstOrNull { it[0] == idc && flags and it[1] == it[2] }?.get(3)?.toString()
    }
}
