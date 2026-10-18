package com.epiccrown.directstream.showcase.ui

import android.content.Context
import com.epiccrown.directstream.api.*

/** Host-owned defaults only. Active-call policy and peer capabilities are never persisted. */
internal class CodecSettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("codec-settings", Context.MODE_PRIVATE)

    fun load(): CallCodecPreferences {
        fun read(media: String): CodecPolicy = runCatching {
            val mime = prefs.getString(media, null) ?: return@runCatching CodecPolicy.Auto
            val codec = CodecId(mime)
            require(codec.mimeType.startsWith("$media/"))
            if (prefs.getBoolean("$media-enforced", false)) CodecPolicy.Enforce(codec)
            else CodecPolicy.Prefer(codec)
        }
            .getOrDefault(CodecPolicy.Auto)
        return CallCodecPreferences(read("video"), read("audio"))
    }

    fun save(value: CallCodecPreferences) {
        prefs
            .edit()
            .apply {
                listOf("video" to value.video, "audio" to value.audio).forEach { (media, policy) ->
                    putString(media, policy.codecOrNull()?.mimeType)
                    putBoolean("$media-enforced", policy is CodecPolicy.Enforce)
                }
            }
            .apply()
    }
}
