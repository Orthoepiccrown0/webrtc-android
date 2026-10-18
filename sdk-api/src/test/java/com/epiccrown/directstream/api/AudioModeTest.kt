package com.epiccrown.directstream.api

import org.junit.Assert.*
import org.junit.Test

class AudioModeTest {
    private val caps =
        CodecCapabilities(
            listOf(
                CodecCapability(
                    CodecId.OPUS,
                    listOf(CodecFormat(48000, 2)),
                    listOf(CodecFormat(48000, 2)),
                ),
            ),
        )

    @Test
    fun audioRequiresNeitherVideoCapabilitiesNorCamera() {
        val policy = CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.AV1))
        CodecSelector.validateLocal(caps, policy, CallMode.Audio)
        val endpoint = CodecEndpoint(caps, policy, CallMode.Audio)
        assertEquals(CodecSelection(null, CodecId.OPUS), CodecSelector.select(endpoint, endpoint))
        assertFalse(
            StreamPermissions.required(37, Role.SENDER, CallMode.Audio)
                .contains(StreamPermissions.CAMERA),
        )
        assertTrue(
            StreamPermissions.required(37, Role.SENDER, CallMode.Audio)
                .contains(StreamPermissions.LOCAL_NETWORK),
        )
        assertTrue(StreamPermissions.required(30, Role.SENDER, CallMode.Audio).isEmpty())
    }

    @Test
    fun modesMustAgreeBeforeMediaStarts() {
        val endpoint = CodecEndpoint(caps, CallCodecPreferences(), CallMode.Audio)
        assertThrows(CodecException::class.java) {
            CodecSelector.select(endpoint, endpoint.copy(mode = CallMode.Video))
        }
    }

    @Test
    fun protocolRoundTripsAudioSelectionAndProofRedactsItself() {
        val signal =
            CodecSignal(
                "transaction-audio-1234",
                CodecSignalKind.PREPARE,
                proposal =
                    CodecProposal(
                        CallCodecPreferences(),
                        CallCodecPreferences(),
                        CodecSelection(null, CodecId.OPUS),
                    ),
            )
        assertEquals(signal, CodecProtocol.decode(CodecProtocol.encode(signal)))
        assertFalse(DeviceOwnershipProof("secret", "test", 0).toString().contains("secret"))
    }
}
