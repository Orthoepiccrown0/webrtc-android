package com.epiccrown.directstream.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Versioned, bounded provider payload. Carries codec capabilities only, never SDP or credentials.
 */
object CodecProtocol {
    const val VERSION = 1
    private val json = Json { encodeDefaults = true }

    fun encode(signal: CodecSignal): String =
        json.encodeToString(signal).also { validate(signal, it.length) }

    fun decode(value: String): CodecSignal {
        require(value.length in 1..32768)
        return json.decodeFromString<CodecSignal>(value).also { validate(it, value.length) }
    }

    private fun validate(signal: CodecSignal, size: Int) {
        require(size <= 32768 && signal.transactionId.matches(Regex("[A-Za-z0-9_-]{16,64}")))
        signal.endpoint?.capabilities?.let { caps ->
            require(
                caps.codecs.size <= 32 &&
                    caps.codecs.map { it.codec }.distinct().size == caps.codecs.size,
            )
            caps.codecs.forEach { codec ->
                require(codec.encoding.size <= 32 && codec.decoding.size <= 32)
                (codec.encoding + codec.decoding).forEach { format ->
                    require(
                        format.clockRate in 1..384000 &&
                            (format.channels == null || format.channels in 1..8),
                    )
                    require(
                        format.parameters.size <= 24 &&
                            format.parameters.all { (k, v) ->
                                k.length in 1..64 && v.length <= 256
                            },
                    )
                }
            }
        }
        when (signal.kind) {
            CodecSignalKind.CAPABILITIES -> requireNotNull(signal.endpoint)
            CodecSignalKind.REQUEST -> requireNotNull(signal.preferences)
            CodecSignalKind.PREPARE -> requireNotNull(signal.proposal)
            CodecSignalKind.REJECT,
            CodecSignalKind.ABORT -> requireNotNull(signal.failure)
            else -> Unit
        }
    }
}
