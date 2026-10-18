package com.epiccrown.directstream.showcase

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.incoming.IncomingCallActivity
import com.epiccrown.directstream.incoming.IncomingCallNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test

class IncomingNotificationTest {
    @get:Rule
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(
            *(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            else emptyArray()),
        )
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request() =
        PairingRequest(
            "notification-test-request",
            "123456",
            System.currentTimeMillis() + 60_000,
            role = Role.SENDER,
        )

    @Test
    fun callNotificationHasPrivateScopedIntentsAndPermissionFallback() {
        IncomingCallNotifications.createChannel(context)
        val notification =
            IncomingCallNotifications.build(context, request(), "Kitchen", fullScreen = true)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertNotNull(notification.fullScreenIntent)
        assertNotNull(notification.contentIntent)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 31)
            assertEquals(
                "android.app.Notification\$CallStyle",
                notification.extras.getString(Notification.EXTRA_TEMPLATE),
            )
        assertNull(
            IncomingCallNotifications.build(context, request(), fullScreen = false)
                .fullScreenIntent,
        )
        val activity =
            context.packageManager.getActivityInfo(
                ComponentName(context, IncomingCallActivity::class.java),
                0,
            )
        assertFalse(
            "Notification entry point must not allow third-party activity launches",
            activity.exported,
        )
    }

    @Test
    fun malformedExpiredApprovedAndWrongRoleRequestsCannotAlert() {
        assertTrue(IncomingCallNotifications.valid(request()))
        for (invalid in
            listOf(
                request().copy(expiresAt = System.currentTimeMillis() - 1),
                request().copy(status = "approved"),
                request().copy(role = Role.RECEIVER),
                request().copy(requestId = "bad"),
            )) {
            assertFalse(IncomingCallNotifications.valid(invalid))
            assertTrue(runCatching { IncomingCallNotifications.build(context, invalid) }.isFailure)
        }
    }

    @Test
    fun eligibleFullScreenAlertWakesSleepingEmulatorWithoutStartingMedia() {
        val manager = context.getSystemService(NotificationManager::class.java)
        IncomingCallNotifications.createChannel(context)
        Assume.assumeTrue(
            IncomingCallNotifications.fullScreenAllowed(context) &&
                manager.areNotificationsEnabled(),
        )
        Assume.assumeTrue(
            manager.getNotificationChannel(IncomingCallNotifications.CHANNEL).importance >=
                NotificationManager.IMPORTANCE_HIGH,
        )
        val power = context.getSystemService(PowerManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val request = request()
        try {
            automation.executeShellCommand("input keyevent 223").close()
            val asleepDeadline = SystemClock.elapsedRealtime() + 3_000
            while (
                power.isInteractive && SystemClock.elapsedRealtime() < asleepDeadline
            ) SystemClock.sleep(50)
            assertFalse("Device should sleep before the incoming alert", power.isInteractive)
            // Synthetic authorized model: the private activity then rejects its nonexistent server
            // request.
            IncomingCallNotifications.show(context, request)
            val awakeDeadline = SystemClock.elapsedRealtime() + 8_000
            while (
                !power.isInteractive && SystemClock.elapsedRealtime() < awakeDeadline
            ) SystemClock.sleep(50)
            assertTrue(
                "An eligible full-screen call alert should wake the screen",
                power.isInteractive,
            )
            val entry =
                dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context,
                    CallTestEntryPoint::class.java,
                )
            assertNull(
                "Waking the screen must not start microphone/camera service",
                entry.bridge().token,
            )
        } finally {
            IncomingCallNotifications.cancel(context, request.requestId)
            automation.executeShellCommand("input keyevent 224").close()
        }
    }
}
