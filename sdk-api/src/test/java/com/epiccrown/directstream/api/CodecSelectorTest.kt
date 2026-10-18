package com.epiccrown.directstream.api

import org.junit.Assert.*
import org.junit.Test

class CodecSelectorTest {
    private fun cap(
        id: CodecId,
        hardware: Boolean = false,
        params: Map<String, String> = emptyMap(),
    ): CodecCapability {
        val format =
            CodecFormat(
                if (id.mimeType.startsWith("audio")) 48000 else 90000,
                if (id.mimeType.startsWith("audio")) 2 else null,
                params,
            )
        val implementation =
            if (hardware) CodecImplementation.HARDWARE else CodecImplementation.SOFTWARE
        return CodecCapability(id, listOf(format), listOf(format), implementation, implementation)
    }

    private fun endpoint(vararg codecs: CodecCapability, policy: CodecPolicy = CodecPolicy.Auto) =
        CodecEndpoint(
            CodecCapabilities(codecs.toList() + cap(CodecId.OPUS)),
            CallCodecPreferences(video = policy),
        )

    @Test
    fun unavailableLocalPreferenceCanFallBackWithoutRelaxingEnforcement() {
        val a = endpoint(cap(CodecId.VP8), policy = CodecPolicy.Prefer(CodecId.AV1))
        CodecSelector.validateLocal(a.capabilities, a.preferences)
        assertEquals(
            CodecId.VP8,
            CodecSelector.select(a, a.copy(preferences = CallCodecPreferences())).video,
        )
    }

    @Test
    fun autoPrefersCommonHardwareAv1ThenH264ThenVp9() {
        val a =
            endpoint(
                cap(CodecId.VP8),
                cap(CodecId.VP9, true),
                cap(CodecId.H264, true),
                cap(CodecId.AV1, true),
            )
        assertEquals(CodecId.AV1, CodecSelector.select(a, a).video)
        val b = endpoint(cap(CodecId.VP8), cap(CodecId.VP9, true), cap(CodecId.H264, true))
        assertEquals(CodecId.H264, CodecSelector.select(a, b).video)
        val c = endpoint(cap(CodecId.VP8), cap(CodecId.VP9, true))
        assertEquals(CodecId.VP9, CodecSelector.select(a, c).video)
    }

    @Test
    fun autoDoesNotPreferOneSidedHardwareOverSoftwareVp8() {
        val a = endpoint(cap(CodecId.VP8), cap(CodecId.AV1, true))
        val b = endpoint(cap(CodecId.VP8), cap(CodecId.AV1))
        assertEquals(CodecId.VP8, CodecSelector.select(a, b).video)
    }

    @Test
    fun explicitSoftwareVp9AndAv1CanBeEnforced() {
        for (id in listOf(CodecId.VP9, CodecId.AV1)) {
            val a = endpoint(cap(CodecId.VP8), cap(id), policy = CodecPolicy.Enforce(id))
            assertEquals(
                id,
                CodecSelector.select(a, a.copy(preferences = CallCodecPreferences())).video,
            )
        }
    }

    @Test
    fun preferFallsBackButEnforceDoesNot() {
        val a =
            endpoint(cap(CodecId.VP8), cap(CodecId.AV1), policy = CodecPolicy.Prefer(CodecId.AV1))
        val b = endpoint(cap(CodecId.VP8))
        assertEquals(CodecId.VP8, CodecSelector.select(a, b).video)
        val e =
            assertThrows(CodecException::class.java) {
                CodecSelector.select(
                    a.copy(
                        preferences =
                            CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.AV1)),
                    ),
                    b,
                )
            }
        assertEquals(CodecFailureReason.INCOMPATIBLE_PEER, e.reason)
    }

    @Test
    fun conflictingEnforcementFails() {
        val a =
            endpoint(cap(CodecId.VP8), cap(CodecId.VP9), policy = CodecPolicy.Enforce(CodecId.VP8))
        val b = a.copy(preferences = CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.VP9)))
        assertThrows(CodecException::class.java) { CodecSelector.select(a, b) }
    }

    @Test
    fun decodeOnlyCodecCannotBeSelected() {
        val a = endpoint(cap(CodecId.VP8), cap(CodecId.AV1).copy(encoding = emptyList()))
        assertThrows(CodecException::class.java) {
            CodecSelector.validateLocal(
                a.capabilities,
                CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.AV1)),
            )
        }
        assertEquals(CodecId.VP8, CodecSelector.select(a, a).video)
    }

    @Test
    fun vp9AndAv1ProfilesMustBeCompatible() {
        for ((id, key) in listOf(CodecId.VP9 to "profile-id", CodecId.AV1 to "profile")) {
            val a =
                endpoint(
                    cap(CodecId.VP8),
                    cap(id, params = mapOf(key to "0")),
                    policy = CodecPolicy.Enforce(id),
                )
            val b = endpoint(cap(CodecId.VP8), cap(id, params = mapOf(key to "1")))
            assertThrows(CodecException::class.java) { CodecSelector.select(a, b) }
        }
    }

    @Test
    fun h264LevelsMayDifferButProfilesAndPacketizationCannot() {
        val a =
            endpoint(
                cap(
                    CodecId.H264,
                    params = mapOf("profile-level-id" to "42e01f", "packetization-mode" to "1"),
                ),
            )
        val compatible =
            endpoint(
                cap(
                    CodecId.H264,
                    params = mapOf("profile-level-id" to "42e033", "packetization-mode" to "1"),
                ),
            )
        assertEquals(CodecId.H264, CodecSelector.select(a, compatible).video)
        val high =
            endpoint(
                cap(
                    CodecId.H264,
                    params = mapOf("profile-level-id" to "640c1f", "packetization-mode" to "1"),
                ),
            )
        assertThrows(CodecException::class.java) { CodecSelector.select(a, high) }
        assertThrows(CodecException::class.java) {
            CodecSelector.select(a, endpoint(cap(CodecId.H264)))
        }
    }

    @Test
    fun audioPolicyIsIndependentAndOpusDefault() {
        val pcmu =
            cap(CodecId("audio/pcmu"))
                .copy(
                    encoding = listOf(CodecFormat(8000, 1)),
                    decoding = listOf(CodecFormat(8000, 1)),
                )
        val a = endpoint(cap(CodecId.VP8), pcmu)
        assertEquals(CodecId.OPUS, CodecSelector.select(a, a).audio)
        val forced =
            a.copy(preferences = CallCodecPreferences(audio = CodecPolicy.Enforce(pcmu.codec)))
        assertEquals(pcmu.codec, CodecSelector.select(forced, a).audio)
    }

    @Test
    fun protocolRoundTripsAndRejectsOversizedOrInvalidMessages() {
        val signal =
            CodecSignal(
                "capabilities-test-1234",
                CodecSignalKind.CAPABILITIES,
                endpoint = endpoint(cap(CodecId.VP9), cap(CodecId.AV1)),
            )
        assertEquals(signal, CodecProtocol.decode(CodecProtocol.encode(signal)))
        assertThrows(IllegalArgumentException::class.java) {
            CodecProtocol.decode("x".repeat(32769))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CodecProtocol.encode(signal.copy(transactionId = "bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CodecProtocol.encode(signal.copy(kind = CodecSignalKind.PREPARE))
        }
    }
}
