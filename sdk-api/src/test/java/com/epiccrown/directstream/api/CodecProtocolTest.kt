package com.epiccrown.directstream.api

import org.junit.Assert.*
import org.junit.Test

class CodecProtocolTest {
    @Test
    fun firstReleasePreservesAudioAndVideoCapabilitiesWithoutRewritingPayloads() {
        assertEquals(1, CodecProtocol.VERSION)
        for (mode in CallMode.entries) {
            val signal =
                CodecSignal(
                    "first-release-capabilities",
                    CodecSignalKind.CAPABILITIES,
                    endpoint = CodecEndpoint(CodecCapabilities(), CallCodecPreferences(), mode),
                )
            assertEquals(signal, CodecProtocol.decode(CodecProtocol.encode(signal)))
        }
    }

    @Test
    fun audioOnlySelectionSurvivesPrepareRoundTrip() {
        val signal =
            CodecSignal(
                "first-release-audio-prepare",
                CodecSignalKind.PREPARE,
                proposal =
                    CodecProposal(
                        CallCodecPreferences(),
                        CallCodecPreferences(),
                        CodecSelection(null, CodecId.OPUS),
                    ),
            )
        assertEquals(signal, CodecProtocol.decode(CodecProtocol.encode(signal)))
    }
}
