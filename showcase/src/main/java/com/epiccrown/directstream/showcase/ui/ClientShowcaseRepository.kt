package com.epiccrown.directstream.showcase.ui

import android.content.Context
import com.epiccrown.directstream.*
import com.epiccrown.directstream.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Sample-only history and participant names, layered exclusively on public SDK contracts. */
class ClientShowcaseRepository(context: Context, private val client: DirectStreamClient) :
    ShowcaseRepository, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val history = RecentCallStore(context)
    override val state =
        combine(client.state, history.rows) { call, rows -> call.toShowcaseState(rows) }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                client.state.value.toShowcaseState(history.rows.value),
            )

    init {
        scope.launch {
            client.callState
                .map { it.latestAttempt }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { history.record(it) }
        }
    }

    override suspend fun initialize() {
        client.activate()
    }

    override suspend fun resetCode() {
        client.regenerateCode()
    }

    override suspend fun requestConnection(code: String) {
        client.startCall(code, mode, microphone)
    }

    override suspend fun approveConnection() {
        (state.value.session as? StreamSessionState.IncomingRequest)?.request?.let {
            client.accept(it.requestId, microphone)
        }
    }

    private var mode = CallMode.Video

    override fun configureMode(mode: CallMode) {
        this.mode = mode
    }

    private var microphone = false

    override fun configureMicrophone(allowed: Boolean) {
        microphone = allowed
    }

    override suspend fun rejectConnection() {
        client.callState.value.callId?.let { client.decline(it) }
    }

    override suspend fun cancelConnection() {
        client.callState.value.callId?.let { client.cancel(it) }
    }

    override suspend fun disconnect() {
        client.callState.value.callId?.let { client.hangup(it) }
    }

    override suspend fun switchCamera() {
        client.callState.value.callId?.let { client.flipCamera(it) }
    }

    override suspend fun setMuted(muted: Boolean) {
        client.callState.value.callId?.let { client.setMuted(it, muted) }
    }

    override suspend fun selectAudioOutput(id: Int) {
        client.callState.value.callId?.let { client.selectAudioOutput(it, id) }
    }

    override suspend fun refreshAudioOutputs() {
        client.callState.value.callId?.let { client.refreshAudioOutputs(it) }
    }

    override suspend fun reviewNotification(requestId: String) {
        client.reviewIncoming(requestId)
    }

    override suspend fun setNextCallCodecs(preferences: CallCodecPreferences) {
        client.setNextCallCodecs(preferences)
    }

    override suspend fun switchCodecs(preferences: CallCodecPreferences) {
        client.callState.value.callId?.let { client.switchCodecs(it, preferences) }
    }

    override suspend fun removeRecent(id: String) = history.remove(id)

    override suspend fun clearRecents() = history.clear()

    override fun setVisible(visible: Boolean) = client.setVisible(visible)

    override fun setCallScreenVisible(visible: Boolean) = client.setCallScreenVisible(visible)

    override fun refreshPushToken(token: String) {
        scope.launch { client.updatePushToken(token) }
    }

    override fun close() {
        scope.cancel()
    }
}
