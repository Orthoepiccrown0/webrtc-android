package com.epiccrown.directstream.showcase.integration

import android.app.NotificationManager
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.epiccrown.directstream.*
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.incoming.IncomingCallNotifications
import com.epiccrown.directstream.testing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Run only with SdkTestRunner on a disposable emulator, with notification/full-screen access. */
class IncomingWakeTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            )
            .use { it.readBytes() }
    }

    private suspend fun await(description: String = "condition", condition: () -> Boolean) {
        try {
            withTimeout(20_000) { while (!condition()) delay(100) }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $description", error)
        }
    }

    @Test
    fun backgroundRealtimeRequestWakesScreenAndCancelledPushCannotReopenIt() = runBlocking {
        val original = DirectStreamClient.current().configuration
        DirectStreamClient.current().dispose()
        context.getSharedPreferences("directstream-activation", 0).edit().clear().commit()
        val server = InMemoryCallServer()
        val target = InMemoryCallProvider(server, "wake-target")
        val caller = InMemoryCallProvider(server, "wake-caller")
        val client =
            withContext(Dispatchers.Main) {
                DirectStreamClient.initialize(
                    context,
                    DirectStreamConfiguration(
                        target,
                        participantName = "Target Owl",
                        returnActivity = SdkTestActivity::class.java,
                    ),
                )
            }
        val power = context.getSystemService(PowerManager::class.java)
        try {
            assertTrue(IncomingCallNotifications.fullScreenAllowed(context))
            assertEquals(CallResult.Success, client.activate())
            await { client.registration.value.device != null }
            caller.admission.register("Caller Fox")
            ActivityScenario.launch(SdkTestActivity::class.java).use {
                shell("input keyevent 3")
                delay(500)
                shell("input keyevent 223")
                await { !power.isInteractive }
                val request =
                    caller.admission.create(
                        client.registration.value.device!!.code,
                        "wake-request-123456789",
                    )
                await { power.isInteractive }
                await { client.state.value.session is StreamSessionState.IncomingRequest }
                await("incoming host resumed over the lock screen") {
                    var resumed = false
                    instrumentation.runOnMainSync {
                        resumed =
                            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                                .getInstance()
                                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                                .any { activity -> activity is SdkTestActivity }
                    }
                    resumed
                }
                assertEquals("Caller Fox", client.state.value.peerDisplayName)
                assertFalse(client.video.localAvailable.value)
                assertEquals("Caller Fox", client.callState.value.latestAttempt?.peerName)
                await("incoming notification posted") {
                    context
                        .getSystemService(NotificationManager::class.java)
                        .activeNotifications
                        .any { n ->
                            n.tag == IncomingCallNotifications.TAG
                        }
                }
                // Reproduce cancellation after the receiver has left the incoming screen.
                // The host's stopped state must not prevent terminal admission events.
                shell("input keyevent 3")
                withContext(Dispatchers.Main) { client.setVisible(false) }
                shell("input keyevent 223")
                await("receiver asleep before cancellation") { !power.isInteractive }
                caller.admission.cancel(request.requestId)
                await { client.state.value.session !is StreamSessionState.IncomingRequest }
                await {
                    context
                        .getSystemService(NotificationManager::class.java)
                        .activeNotifications
                        .none { n -> n.tag == IncomingCallNotifications.TAG }
                }
                assertEquals(CallOutcome.CANCELLED, client.callState.value.latestAttempt?.outcome)
                assertFalse(
                    "Cancelling an incoming request must not wake the receiver",
                    power.isInteractive,
                )
                assertFalse(client.video.localAvailable.value)
                assertEquals(
                    PushHandlingResult.Stale,
                    client.handlePush(
                        mapOf(
                            "type" to "directstream.incoming",
                            "protocolVersion" to "1",
                            "requestId" to request.requestId,
                        ),
                    ),
                )
                delay(1_000)
                assertFalse(power.isInteractive)
            }
        } finally {
            shell("input keyevent 224")
            client.deactivate()
            client.dispose()
            withContext(Dispatchers.Main) { DirectStreamClient.initialize(context, original) }
        }
    }

    @Test
    fun deniedNotificationAccessRetainsIncomingStateWithoutWakingScreen() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Revoke POST_NOTIFICATIONS before launching this isolated test",
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        val original = DirectStreamClient.current().configuration
        DirectStreamClient.current().dispose()
        context.getSharedPreferences("directstream-activation", 0).edit().clear().commit()
        val server = InMemoryCallServer()
        val target = InMemoryCallProvider(server, "denied-target")
        val caller = InMemoryCallProvider(server, "denied-caller")
        val client =
            withContext(Dispatchers.Main) {
                DirectStreamClient.initialize(
                    context,
                    DirectStreamConfiguration(target, returnActivity = SdkTestActivity::class.java),
                )
            }
        val power = context.getSystemService(PowerManager::class.java)
        try {
            assertEquals(CallResult.Success, client.activate())
            await("registration") { client.registration.value.device != null }
            caller.admission.register("Quiet Fox")
            shell("input keyevent 3")
            shell("input keyevent 223")
            await("screen asleep") { !power.isInteractive }
            val request =
                caller.admission.create(
                    client.registration.value.device!!.code,
                    "denied-request-123456789",
                )
            await("background incoming state") {
                client.state.value.session is StreamSessionState.IncomingRequest
            }
            delay(1_000)
            assertFalse(power.isInteractive)
            assertTrue(
                context.getSystemService(NotificationManager::class.java).activeNotifications.none {
                    it.tag == IncomingCallNotifications.TAG
                },
            )
            val verified = client.validateIncoming(request.requestId)!!
            assertNull(
                IncomingCallNotifications.build(context, verified, "Quiet Fox", fullScreen = false)
                    .fullScreenIntent,
            )
            assertFalse(client.video.localAvailable.value)
            // Returning from permission settings must retry this pending request without a new
            // push.
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            withContext(Dispatchers.Main) {
                client.setVisible(false)
                client.setVisible(true)
            }
            await("pending alert after granting notification permission") {
                context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.tag == IncomingCallNotifications.TAG
                }
            }
            assertFalse(client.video.localAvailable.value)
        } finally {
            shell("input keyevent 224")
            client.deactivate()
            client.dispose()
            withContext(Dispatchers.Main) { DirectStreamClient.initialize(context, original) }
        }
    }
}
