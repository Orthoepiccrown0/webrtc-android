package com.epiccrown.directstream.internal.media

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.epiccrown.directstream.api.*
import kotlinx.coroutines.*

/** Sole production owner of system call state and communication audio endpoints. */
@androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP)
class AndroidCallPlatform(
    private val context: Context,
    private val rtc: WebRtcClient,
    private val appName: String = "DirectStream SDK",
) {
    private var job: Job? = null
    private var control: CallControlScope? = null
    private var endpoints = emptyList<CallEndpointCompat>()
    private var current: CallEndpointCompat? = null

    suspend fun start(
        scope: CoroutineScope,
        role: Role,
        mode: CallMode,
        peerName: String? = null,
        onEnd: () -> Unit,
    ) {
        check(job == null)
        val ready = CompletableDeferred<Unit>()
        val manager = CallsManager(context)
        manager.registerAppWithTelecom(
            CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING,
        )
        val type =
            if (mode == CallMode.Audio) CallAttributesCompat.CALL_TYPE_AUDIO_CALL
            else CallAttributesCompat.CALL_TYPE_VIDEO_CALL
        val incoming = role == Role.SENDER
        rtc.platformOutput = { id ->
            control?.let { call ->
                endpoints
                    .firstOrNull { it.identifier.hashCode() == id }
                    ?.let { call.requestEndpointChange(it) }
            }
            Unit
        }
        job = scope.launch {
            try {
                manager.addCall(
                    CallAttributesCompat(
                        peerName ?: appName,
                        Uri.parse("directstream:call"),
                        if (incoming) CallAttributesCompat.DIRECTION_INCOMING
                        else CallAttributesCompat.DIRECTION_OUTGOING,
                        type,
                        CallAttributesCompat.SUPPORTS_SET_INACTIVE,
                    ),
                    onAnswer = { rtc.platformInterrupted(false) },
                    onDisconnect = { onEnd() },
                    onSetActive = { rtc.platformInterrupted(false) },
                    onSetInactive = { rtc.platformInterrupted(true) },
                ) {
                    control = this
                    var selectedDefault = false
                    launch {
                        availableEndpoints.collect { available ->
                            val lostHeadset =
                                endpoints.any {
                                    it.type in
                                        listOf(
                                            CallEndpointCompat.TYPE_BLUETOOTH,
                                            CallEndpointCompat.TYPE_WIRED_HEADSET,
                                        )
                                } &&
                                    available.none {
                                        it.type in
                                            listOf(
                                                CallEndpointCompat.TYPE_BLUETOOTH,
                                                CallEndpointCompat.TYPE_WIRED_HEADSET,
                                            )
                                    }
                            endpoints = available
                            publish()
                            if ((!selectedDefault || lostHeadset) && available.isNotEmpty()) {
                                selectedDefault = true
                                val preferred =
                                    available.firstOrNull {
                                        it.type in
                                            listOf(
                                                CallEndpointCompat.TYPE_BLUETOOTH,
                                                CallEndpointCompat.TYPE_WIRED_HEADSET,
                                            )
                                    }
                                        ?: available.firstOrNull {
                                            it.type == CallEndpointCompat.TYPE_SPEAKER
                                        }
                                preferred?.let { requestEndpointChange(it) }
                            }
                        }
                    }
                    launch {
                        currentCallEndpoint.collect {
                            current = it
                            publish()
                        }
                    }
                    launch { isMuted.collect { rtc.platformMuted(it) } }
                    launch {
                        val result = if (incoming) answer(type) else setActive()
                        if (result !is androidx.core.telecom.CallControlResult.Success) {
                            ready.completeExceptionally(
                                IllegalStateException("System call activation failed."),
                            )
                        } else ready.complete(Unit)
                    }
                }
            } catch (cancelled: CancellationException) {
                ready.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                if (!ready.completeExceptionally(error)) onEnd()
            } finally {
                control = null
            }
        }
        try {
            withTimeout(5_000) { ready.await() }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun publish() {
        fun output(endpoint: CallEndpointCompat) =
            AudioOutput(
                endpoint.identifier.hashCode(),
                when (endpoint.type) {
                    CallEndpointCompat.TYPE_SPEAKER ->
                        context.getString(com.epiccrown.directstream.R.string.ds_audio_speakerphone)
                    CallEndpointCompat.TYPE_EARPIECE ->
                        context.getString(com.epiccrown.directstream.R.string.ds_audio_earpiece)
                    CallEndpointCompat.TYPE_BLUETOOTH ->
                        context.getString(
                            com.epiccrown.directstream.R.string.ds_audio_bluetooth_headset,
                        )
                    CallEndpointCompat.TYPE_WIRED_HEADSET ->
                        context.getString(
                            com.epiccrown.directstream.R.string.ds_audio_wired_headset,
                        )
                    else ->
                        context.getString(com.epiccrown.directstream.R.string.ds_audio_other_device)
                },
                endpoint.type == CallEndpointCompat.TYPE_BLUETOOTH,
            )
        rtc.platformOutputs(endpoints.map(::output), current?.let(::output))
    }

    suspend fun close() {
        val active = control
        control = null
        runCatching {
            withTimeout(1_000) { active?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
        }
        job?.cancel()
        job = null
        endpoints = emptyList()
        current = null
        rtc.platformOutput = null
    }
}
