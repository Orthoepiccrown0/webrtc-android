package com.epiccrown.directstream.showcase.ui

import kotlinx.coroutines.flow.StateFlow

interface ShowcaseRepository {
    val state: StateFlow<ShowcaseState>

    fun reportFailure(failure: com.epiccrown.directstream.api.CallFailure) {}

    suspend fun dispose() {}

    suspend fun discoverCodecs() {}

    suspend fun setNextCallCodecs(
        preferences: com.epiccrown.directstream.api.CallCodecPreferences,
    ) {}

    suspend fun switchCodecs(
        preferences: com.epiccrown.directstream.api.CallCodecPreferences,
    ) {
        throw com.epiccrown.directstream.api.CodecException(
            com.epiccrown.directstream.api.CodecFailureReason.NO_ACTIVE_CALL,
        )
    }

    suspend fun initialize()

    suspend fun resetCode()

    fun configureMode(mode: com.epiccrown.directstream.api.CallMode) {}

    suspend fun suspendRegistration() {}

    suspend fun requestConnection(code: String)

    suspend fun approveConnection()

    suspend fun rejectRequest(requestId: String?) = rejectConnection()

    suspend fun rejectConnection()

    suspend fun cancelRequest(requestId: String?) = cancelConnection()

    suspend fun cancelConnection()

    suspend fun disconnect()

    suspend fun switchCamera()

    suspend fun removeRecent(id: String) {}

    suspend fun clearRecents() {}

    fun setCallScreenVisible(visible: Boolean) {}

    fun configureMicrophone(allowed: Boolean) {}

    suspend fun setMuted(muted: Boolean) {}

    suspend fun selectAudioOutput(id: Int) {}

    suspend fun refreshAudioOutputs() {}

    suspend fun reviewNotification(requestId: String) {}

    fun onCallServiceStopped() {}

    fun setVisible(visible: Boolean)

    fun refreshPushToken(token: String)
}
