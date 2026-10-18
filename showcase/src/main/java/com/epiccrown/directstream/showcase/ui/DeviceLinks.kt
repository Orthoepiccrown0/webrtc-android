package com.epiccrown.directstream.showcase.ui

import com.epiccrown.directstream.api.DeviceCode
import java.net.URI

/** Prototype pairing links remain sample-only. */
object DeviceLinks {
    /** Accept a plain code or our pairing URI; never extract a code from unrelated URLs. */
    fun fromInput(value: String): String {
        val input = value.trim()
        if (!input.contains("://")) return DeviceCode.normalize(input)
        val uri = URI(input)
        require(
            uri.scheme.equals("webrtcstream", ignoreCase = true) &&
                uri.host.equals("pair", ignoreCase = true) &&
                uri.rawUserInfo == null &&
                uri.port == -1 &&
                uri.rawQuery == null &&
                uri.rawFragment == null,
        ) {
            "Invalid pairing link."
        }
        return DeviceCode.normalize(uri.path.orEmpty().removePrefix("/"))
    }
}
