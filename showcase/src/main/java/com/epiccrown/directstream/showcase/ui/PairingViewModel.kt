package com.epiccrown.directstream.showcase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.epiccrown.directstream.CallCommand
import com.epiccrown.directstream.CallResult
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface PairingIntent {
    data class CodecPreferences(
        val value: com.epiccrown.directstream.api.CallCodecPreferences,
    ) : PairingIntent

    data object Initialize : PairingIntent

    data class RemoveRecent(val id: String) : PairingIntent

    data object ClearRecents : PairingIntent

    data object ResetCode : PairingIntent

    data class Connect(
        val code: String,
        val microphone: Boolean = false,
        val mode: com.epiccrown.directstream.api.CallMode =
            com.epiccrown.directstream.api.CallMode.Video,
    ) : PairingIntent

    data class Approve(val microphone: Boolean = false) : PairingIntent

    data object Reject : PairingIntent

    data object Cancel : PairingIntent

    data object Disconnect : PairingIntent

    data class Mute(val muted: Boolean) : PairingIntent

    data class AudioOutput(val id: Int) : PairingIntent

    data object RefreshAudioOutputs : PairingIntent

    data class ReviewNotification(val requestId: String) : PairingIntent

    data object SwitchCamera : PairingIntent
}

class PairingViewModel(
    private val repository: ShowcaseRepository,
    private val executor: (suspend (CallCommand) -> CallResult)? = null,
    private val onCodecsSaved: (com.epiccrown.directstream.api.CallCodecPreferences) -> Unit = {},
) : ViewModel() {
    val state = repository.state

    fun setCallScreenVisible(visible: Boolean) = repository.setCallScreenVisible(visible)

    fun dispatch(intent: PairingIntent) {
        Timber.tag("PairingUi").d("Intent: %s", intent.javaClass.simpleName)
        viewModelScope.launch {
            when (intent) {
                is PairingIntent.RemoveRecent -> {
                    repository.removeRecent(intent.id)
                    return@launch
                }
                PairingIntent.ClearRecents -> {
                    repository.clearRecents()
                    return@launch
                }
                else -> Unit
            }
            if (executor != null) {
                val command =
                    when (intent) {
                        is PairingIntent.CodecPreferences ->
                            CallCommand.SetNextCallCodecs(intent.value)
                        PairingIntent.Initialize -> CallCommand.Register
                        is PairingIntent.RemoveRecent,
                        PairingIntent.ClearRecents -> error("Sample history handled separately")
                        PairingIntent.ResetCode -> CallCommand.ResetCode
                        is PairingIntent.Connect ->
                            CallCommand.Request(intent.code, intent.microphone, intent.mode)
                        is PairingIntent.Approve -> CallCommand.Accept(intent.microphone)
                        PairingIntent.Reject -> CallCommand.Decline
                        PairingIntent.Cancel -> CallCommand.Cancel
                        PairingIntent.Disconnect -> CallCommand.Hangup
                        is PairingIntent.Mute -> CallCommand.Mute(intent.muted)
                        is PairingIntent.AudioOutput -> CallCommand.AudioOutput(intent.id)
                        PairingIntent.RefreshAudioOutputs -> CallCommand.RefreshAudioOutputs
                        is PairingIntent.ReviewNotification ->
                            CallCommand.ReviewIncoming(intent.requestId)
                        PairingIntent.SwitchCamera -> CallCommand.FlipCamera
                    }
                val result = executor.invoke(command)
                if (intent is PairingIntent.CodecPreferences && result == CallResult.Success)
                    onCodecsSaved(intent.value)
                return@launch
            }
            when (intent) {
                is PairingIntent.CodecPreferences -> {
                    repository.setNextCallCodecs(intent.value)
                    onCodecsSaved(intent.value)
                }
                PairingIntent.Initialize -> repository.initialize()
                is PairingIntent.RemoveRecent -> repository.removeRecent(intent.id)
                PairingIntent.ClearRecents -> repository.clearRecents()
                PairingIntent.ResetCode -> repository.resetCode()
                is PairingIntent.Connect -> {
                    repository.configureMode(intent.mode)
                    repository.configureMicrophone(intent.microphone)
                    repository.requestConnection(intent.code)
                }
                is PairingIntent.Approve -> {
                    repository.configureMicrophone(intent.microphone)
                    repository.approveConnection()
                }
                PairingIntent.Reject -> repository.rejectConnection()
                PairingIntent.Cancel -> repository.cancelConnection()
                PairingIntent.Disconnect -> repository.disconnect()
                is PairingIntent.Mute -> repository.setMuted(intent.muted)
                is PairingIntent.AudioOutput -> repository.selectAudioOutput(intent.id)
                PairingIntent.RefreshAudioOutputs -> repository.refreshAudioOutputs()
                is PairingIntent.ReviewNotification ->
                    repository.reviewNotification(intent.requestId)
                PairingIntent.SwitchCamera -> repository.switchCamera()
            }
        }
    }

    override fun onCleared() {
        (repository as? AutoCloseable)?.close()
    }

    class Factory(
        private val repository: ShowcaseRepository,
        private val executor: (suspend (CallCommand) -> CallResult)? = null,
        private val onCodecsSaved: (com.epiccrown.directstream.api.CallCodecPreferences) -> Unit =
            {},
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PairingViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return PairingViewModel(repository, executor, onCodecsSaved) as T
        }
    }
}
