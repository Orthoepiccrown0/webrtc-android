package com.epiccrown.directstream.consumer

import com.epiccrown.directstream.CallResult
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.api.CallFailure
import com.epiccrown.directstream.api.CallMode
import com.epiccrown.directstream.api.CallState

/** Compile-checked custom UI lifecycle example, also included in the README. */
class CallingActions(
    private val calls: DirectStreamClient,
    private val onFailure: (CallFailure) -> Unit,
) {
    suspend fun activate() = handle(calls.activate())

    suspend fun onLogin(displayName: String?) = handle(calls.activate(displayName))

    suspend fun onProfileNameChanged(displayName: String?) =
        handle(calls.updateParticipantName(displayName))

    suspend fun onLogout() = handle(calls.deactivate())

    // Collect in a lifecycle-aware host coroutine; cancellation stops collection.
    suspend fun observe(onState: (CallState) -> Unit) {
        calls.callState.collect { onState(it) }
    }

    // false means receive-only audio; enable the microphone only after permission is granted.
    suspend fun startCall(code: String) =
        handle(calls.startCall(code, CallMode.Audio, microphone = false))

    // Capture callId from the state rendered by the UI, before launching an action coroutine.
    suspend fun accept(callId: String) = handle(calls.accept(callId, microphone = false))

    suspend fun decline(callId: String) = handle(calls.decline(callId))

    suspend fun cancel(callId: String) = handle(calls.cancel(callId))

    suspend fun hangup(callId: String) = handle(calls.hangup(callId))

    private fun handle(result: CallResult) {
        when (result) {
            CallResult.Success ->
                Unit // Observe callState; success does not mean media is connected.
            is CallResult.Failure -> onFailure(result.error)
        }
    }
}
