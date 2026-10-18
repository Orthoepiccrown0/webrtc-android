package com.epiccrown.directstream.showcase

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.epiccrown.directstream.CallService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CallServiceTest {
    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(
            *(listOf(Manifest.permission.RECORD_AUDIO) +
                    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS)
                    else emptyList())
                .toTypedArray(),
        )

    @Test
    fun serviceStartsWhileVisibleRejectsStaleActionsAndStopsCleanly() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            val bridge =
                EntryPointAccessors.fromApplication(context, CallTestEntryPoint::class.java)
                    .bridge()
            val notifications = context.getSystemService(NotificationManager::class.java)
            try {
                withContext(Dispatchers.Main) { bridge.start(true) }
                withTimeout(5_000) {
                    while (notifications.activeNotifications.none { it.id == 41 }) delay(100)
                }
                val token = bridge.token
                context.startService(
                    Intent(context, CallService::class.java)
                        .setAction("end")
                        .putExtra("token", "stale-test-token"),
                )
                delay(300)
                assertEquals(token, bridge.token)
                assertTrue(notifications.activeNotifications.any { it.id == 41 })
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                delay(300)
                assertTrue(notifications.activeNotifications.any { it.id == 41 })
            } finally {
                withContext(Dispatchers.Main) { bridge.stop() }
                withTimeout(5_000) {
                    while (notifications.activeNotifications.any { it.id == 41 }) delay(100)
                }
                assertNull(bridge.token)
                scenario.close()
            }
        }

    @Test
    fun communicationRoutingAndTransientFocusRestoreUserMute() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            val bridge =
                EntryPointAccessors.fromApplication(context, CallTestEntryPoint::class.java)
                    .bridge()
            val rtc =
                com.epiccrown.directstream.internal.media.WebRtcClient(context, TestColorCamera())
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val competing =
                android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                    )
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(
                        {},
                        android.os.Handler(android.os.Looper.getMainLooper()),
                    )
                    .build()
            try {
                withContext(Dispatchers.Main) { bridge.start(true) }
                rtc.start(
                    com.epiccrown.directstream.api.Role.RECEIVER,
                    emptyList(),
                    false,
                    {},
                    {},
                    {},
                    microphone = true,
                    manageAudio = true,
                )
                withTimeout(5_000) { while (rtc.media.value.outputs.isEmpty()) delay(100) }
                assertEquals(android.media.AudioManager.MODE_IN_COMMUNICATION, audio.mode)
                val speaker = rtc.media.value.outputs.first { it.label == "Speakerphone" }
                rtc.selectAudioOutput(speaker.id)
                withTimeout(5_000) { while (rtc.media.value.output?.id != speaker.id) delay(100) }
                rtc.setMuted(true)
                assertEquals(
                    android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    audio.requestAudioFocus(competing),
                )
                withTimeout(5_000) { while (!rtc.media.value.interrupted) delay(100) }
                audio.abandonAudioFocusRequest(competing)
                withTimeout(5_000) { while (rtc.media.value.interrupted) delay(100) }
                assertTrue(rtc.media.value.muted)
            } finally {
                audio.abandonAudioFocusRequest(competing)
                rtc.close()
                withContext(Dispatchers.Main) { bridge.stop() }
                scenario.close()
            }
        }
}
