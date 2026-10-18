package com.epiccrown.directstream.incoming

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.epiccrown.directstream.R
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.Role
import timber.log.Timber

/**
 * Request status is authenticated before this helper is called; notification data never approves a
 * call.
 */
object IncomingCallNotifications {
    const val CHANNEL = "incoming-calls"
    const val TAG = "pairing-request"
    const val ID = 0
    private const val REQUEST = "incomingRequest"
    private var lastBlockedAlert: Pair<String, String>? = null

    fun valid(request: PairingRequest, now: Long = System.currentTimeMillis()): Boolean =
        request.role == Role.SENDER &&
            request.status == "pending" &&
            request.expiresAt > now &&
            request.requestId.matches(Regex("[a-zA-Z0-9_-]{16,100}"))

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val previous = manager.getNotificationChannel("pairing")
        val importance =
            if (previous?.importance == NotificationManager.IMPORTANCE_NONE)
                NotificationManager.IMPORTANCE_NONE
            else NotificationManager.IMPORTANCE_HIGH
        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.ds_incoming_incoming_calls),
                    importance,
                )
                .apply {
                    description =
                        context.getString(
                            R.string.ds_incoming_video_call_alerts_sound_and_lock_screen_display,
                        )
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                },
        )
    }

    fun fullScreenAllowed(context: Context): Boolean {
        val declared =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
        return Manifest.permission.USE_FULL_SCREEN_INTENT in declared &&
            (Build.VERSION.SDK_INT < 34 ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
    }

    private fun pending(context: Context, request: PairingRequest, action: String): PendingIntent {
        val intent =
            Intent(context, IncomingCallActivity::class.java)
                .setData(Uri.parse("webrtcstream-internal://incoming/${request.requestId}/$action"))
                .putExtra("requestId", request.requestId)
                .putExtra("expiresAt", request.expiresAt)
                .putExtra("callAction", action)
        val options =
            if (Build.VERSION.SDK_INT >= 35)
                ActivityOptions.makeBasic()
                    .apply {
                        setPendingIntentCreatorBackgroundActivityStartMode(
                            if (Build.VERSION.SDK_INT >= 36)
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                            else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                    }
                    .toBundle()
            else null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
    }

    fun build(
        context: Context,
        request: PairingRequest,
        name: String =
            context.getString(R.string.ds_incoming_caller, IncomingCalls.options.appName),
        fullScreen: Boolean = fullScreenAllowed(context),
    ): Notification {
        require(valid(request)) {
            context.getString(R.string.ds_incoming_request_is_no_longer_available)
        }
        val title =
            context.getString(
                if (request.mode == com.epiccrown.directstream.api.CallMode.Audio)
                    R.string.ds_incoming_incoming_audio_call
                else R.string.ds_incoming_incoming_video_call,
            )
        val open = pending(context, request, "open")
        val decline = pending(context, request, "decline")
        val person = Person.Builder().setName(name).setImportant(true).build()
        val hidden =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(IncomingCalls.options.notificationIcon)
                .setContentTitle(title)
                .setContentText(
                    context.getString(
                        R.string.ds_incoming_review_call,
                        IncomingCalls.options.appName,
                    ),
                )
                .build()
        val builder =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(IncomingCalls.options.notificationIcon)
                .setContentTitle(title)
                .setContentText(
                    context.getString(
                        R.string.ds_incoming_compare_the_verification_value_before_accepting,
                    ),
                )
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(hidden)
                .setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(person, decline, open)
                        .setIsVideo(request.mode == com.epiccrown.directstream.api.CallMode.Video),
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter((request.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
                .addExtras(Bundle().apply { putString(REQUEST, request.requestId) })
        if (fullScreen) builder.setFullScreenIntent(open, true)
        return builder.build()
    }

    @Synchronized
    fun show(
        context: Context,
        request: PairingRequest,
        name: String =
            context.getString(R.string.ds_incoming_caller, IncomingCalls.options.appName),
    ): Boolean {
        fun blocked(reason: String): Boolean {
            val attempt = request.requestId to reason
            if (lastBlockedAlert != attempt) {
                lastBlockedAlert = attempt
                Timber.tag("Push").w("Incoming call alert suppressed: %s", reason)
            }
            return false
        }
        if (!valid(request)) return blocked("request is expired or no longer pending")
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return blocked("POST_NOTIFICATIONS denied; enable notifications in app Settings")
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled())
            return blocked("app notifications disabled; enable notifications in Android settings")
        val importance = manager.getNotificationChannel(CHANNEL)?.importance
        if (importance == NotificationManager.IMPORTANCE_NONE)
            return blocked("incoming-calls channel disabled; enable it in Android channel settings")
        if (
            manager.activeNotifications.any {
                it.tag == TAG && it.notification.extras.getString(REQUEST) == request.requestId
            }
        )
            return false
        // A replacement request must alert even if Android still holds the previous notification.
        // Duplicate deliveries for the same request have already returned above.
        manager.cancel(TAG, ID)
        val fullScreen = fullScreenAllowed(context)
        try {
            manager.notify(TAG, ID, build(context, request, name, fullScreen))
        } catch (_: SecurityException) {
            return blocked("Android denied notification posting; check app notification access")
        }
        lastBlockedAlert = null
        Timber.tag("Push")
            .i(
                "Incoming call alert posted; channel importance=%s, full-screen access=%s, locked=%s",
                importance,
                fullScreen,
                context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked,
            )
        if (importance != null && importance < NotificationManager.IMPORTANCE_HIGH)
            Timber.tag("Push")
                .w(
                    "Incoming-calls channel is silent/low priority; enable pop-up alerts in Android channel settings",
                )
        if (!fullScreen)
            Timber.tag("Push")
                .w(
                    "Full-screen incoming screen unavailable; notification fallback only. Enable full-screen call alerts in app Settings",
                )
        return true
    }

    fun cancel(context: Context, requestId: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (
            requestId == null ||
                manager.activeNotifications.any {
                    it.tag == TAG && it.notification.extras.getString(REQUEST) == requestId
                }
        )
            manager.cancel(TAG, ID)
    }
}
