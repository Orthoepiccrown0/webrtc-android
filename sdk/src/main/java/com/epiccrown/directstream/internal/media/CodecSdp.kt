package com.epiccrown.directstream.internal.media

import com.epiccrown.directstream.api.*

/** Read-only inspection of native SDP; codec selection never rewrites SDP text. */
internal object CodecSdp {
    data class Media(val kind: String, val codecs: List<Pair<CodecId, String?>>)

    fun inspect(sdp: String): List<Media> {
        val sections = sdp.replace("\r", "").split("\nm=").drop(1)
        return sections.mapNotNull { section ->
            val lines = section.lines()
            val m = lines.first().split(' ')
            if (m.size < 4 || m[0] !in listOf("audio", "video") || m[1] == "0")
                return@mapNotNull null
            val fmts =
                lines
                    .filter { it.startsWith("a=fmtp:") }
                    .associate {
                        it.substringAfter(':').substringBefore(' ') to it.substringAfter(' ', "")
                    }
            val rtp =
                lines
                    .filter { it.startsWith("a=rtpmap:") }
                    .mapNotNull {
                        val payload = it.substringAfter(':').substringBefore(' ')
                        val family = it.substringAfter(' ').substringBefore('/').lowercase()
                        if (family in CodecId.auxiliaryCodecs) null
                        else payload to (CodecId("${m[0]}/$family") to fmts[payload])
                    }
                    .toMap()
            Media(m[0], m.drop(3).mapNotNull(rtp::get))
        }
    }

    fun validate(local: String, remote: String, selection: CodecSelection) {
        for (sdp in listOf(local, remote)) {
            val sections = inspect(sdp)
            for ((kind, expected) in
                listOf("video" to selection.video, "audio" to selection.audio)) {
                if (expected == null) {
                    if (sections.any { it.kind == kind })
                        throw CodecException(CodecFailureReason.NEGOTIATION_FAILED)
                    continue
                }
                val codecs = sections.firstOrNull { it.kind == kind }?.codecs.orEmpty()
                if (codecs.isEmpty() || codecs.any { it.first != expected })
                    throw CodecException(CodecFailureReason.NEGOTIATION_FAILED)
            }
        }
    }
}
