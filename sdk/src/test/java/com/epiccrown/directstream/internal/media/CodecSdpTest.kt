package com.epiccrown.directstream.internal.media

import com.epiccrown.directstream.api.*
import org.junit.Assert.*
import org.junit.Test

class CodecSdpTest {
    private fun sdp(video: String) =
        "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 97\r\na=rtpmap:96 $video/90000\r\na=rtpmap:97 rtx/90000\r\na=fmtp:97 apt=96\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"

    @Test
    fun validatesVp9Av1WithRepairCodecsAndRecvOnlyAudio() {
        for (id in listOf(CodecId.VP9, CodecId.AV1)) {
            val value = sdp(id.name) + "a=recvonly\r\n"
            CodecSdp.validate(value, value, CodecSelection(id, CodecId.OPUS))
        }
    }

    @Test
    fun silentFallbackAndRejectedMediaCannotSatisfyEnforcement() {
        val selected = CodecSelection(CodecId.AV1, CodecId.OPUS)
        assertThrows(CodecException::class.java) {
            CodecSdp.validate(sdp("AV1"), sdp("VP8"), selected)
        }
        assertThrows(CodecException::class.java) {
            CodecSdp.validate(sdp("AV1"), sdp("AV1").replace("m=video 9", "m=video 0"), selected)
        }
    }
}
