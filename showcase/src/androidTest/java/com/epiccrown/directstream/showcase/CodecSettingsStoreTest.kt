package com.epiccrown.directstream.showcase

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.showcase.ui.CodecSettingsStore
import org.junit.Assert.*
import org.junit.Test

class CodecSettingsStoreTest {
    @Test
    fun preferencesSurviveRecreationAndCorruptValuesResetToAuto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("codec-settings", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        try {
            prefs.edit().clear().commit()
            assertEquals(CallCodecPreferences(), CodecSettingsStore(context).load())
            for (id in listOf(CodecId.VP9, CodecId.AV1)) {
                val value =
                    CallCodecPreferences(
                        CodecPolicy.Enforce(id),
                        CodecPolicy.Prefer(CodecId("audio/pcmu")),
                    )
                CodecSettingsStore(context).save(value)
                assertEquals(value, CodecSettingsStore(context).load())
            }
            prefs.edit().putString("video", "not-a-codec").commit()
            assertEquals(CodecPolicy.Auto, CodecSettingsStore(context).load().video)
        } finally {
            prefs
                .edit()
                .clear()
                .apply {
                    before.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Boolean -> putBoolean(key, value)
                        }
                    }
                }
                .commit()
        }
    }
}
