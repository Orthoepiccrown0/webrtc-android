package com.epiccrown.directstream

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber

class CallService : Service() {
    private val client
        get() = DirectStreamClient.current()

    private val repository
        get() = client.uiRepository

    private val bridge
        get() = client.callBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var token: String? = null
    private var observing: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("token")
        if (DirectStreamClient.currentOrNull() == null || id == null || bridge.token != id) {
            if (token == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action != null) {
            if (token != id) return START_NOT_STICKY
            when (intent.action) {
                "end" -> scope.launch { intent.getStringExtra("callId")?.let { client.hangup(it) } }
                "mute" ->
                    scope.launch {
                        intent.getStringExtra("callId")?.let {
                            client.setMuted(it, !repository.state.value.media.muted)
                        }
                    }
            }
            return START_NOT_STICKY
        }
        if (token != null) return START_NOT_STICKY
        token = id
        try {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        "call",
                        this.getString(R.string.ds_sdk_active_calls),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (
                intent.getBooleanExtra("microphone", false) &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
            )
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            foregroundTypes = types
            startForeground(41, notification(), types)
            wakeLock =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DirectStream:Call")
                    .apply { acquire(65 * 60 * 1000L) }
            bridge.acknowledge(id)
            observing = scope.launch {
                repository.state
                    .map { it.media }
                    .distinctUntilChanged()
                    .collect {
                        if (token != null && bridge.token == token) {
                            try {
                                // Reapply the foreground flag; a plain notify can race Telecom's
                                // teardown and Android rejects an unbound CallStyle notification.
                                startForeground(41, notification(), foregroundTypes)
                            } catch (error: RuntimeException) {
                                if (bridge.token == token) {
                                    Timber.tag("StreamSession")
                                        .w(
                                            "Call notification update failed (%s)",
                                            error.javaClass.simpleName,
                                        )
                                    repository.onCallServiceStopped()
                                    stopSelf()
                                }
                            }
                        }
                    }
            }
            Timber.tag("StreamSession").i("Call foreground service started")
        } catch (error: Exception) {
            bridge.acknowledge(id, error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val media = repository.state.value.media
        val open =
            PendingIntent.getActivity(
                this,
                41,
                client
                    .returnIntent()
                    .putExtra("returnToCall", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        fun action(name: String, request: Int) =
            PendingIntent.getService(
                this,
                request,
                Intent(this, CallService::class.java)
                    .setAction(name)
                    .setData(android.net.Uri.parse("webrtcstream-internal://call/$token/$name"))
                    .putExtra("token", token)
                    .putExtra("callId", client.callState.value.callId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val title =
            if (media.interrupted) this.getString(R.string.ds_sdk_call_audio_interrupted)
            else this.getString(R.string.ds_sdk_call_in_progress)
        val text =
            if (media.localVideoPaused)
                this.getString(R.string.ds_sdk_audio_continues_camera_paused)
            else this.getString(R.string.ds_sdk_tap_to_return_to_your_call)
        val builder =
            NotificationCompat.Builder(this, "call")
                .setSmallIcon(client.configuration.notificationIcon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setStyle(
                    NotificationCompat.CallStyle.forOngoingCall(
                        androidx.core.app.Person.Builder()
                            .setName(
                                repository.state.value.peerDisplayName
                                    ?: repository.state.value.peerCode?.let(
                                        com.epiccrown.directstream.api.DeviceCode::format,
                                    )
                                    ?: client.configuration.appName,
                            )
                            .setImportant(true)
                            .build(),
                        action("end", 43),
                    ),
                )
                .addAction(0, this.getString(R.string.ds_sdk_return), open)
        if (media.microphoneAvailable)
            builder.addAction(
                0,
                if (media.muted) this.getString(R.string.ds_sdk_unmute)
                else this.getString(R.string.ds_sdk_mute),
                action("mute", 42),
            )
        return builder.build()
    }

    override fun onDestroy() {
        val unexpected =
            DirectStreamClient.currentOrNull() != null && token != null && token == bridge.token
        observing?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (unexpected) repository.onCallServiceStopped()
        Timber.tag("StreamSession").d("Call foreground service stopped")
        super.onDestroy()
    }
}
