package com.epiccrown.directstream.api

/** Registration is independent from host authentication and media capture. */
enum class RegistrationPhase {
    Inactive,
    Activating,
    Active,
    CleanupPending,
}

data class RegistrationState(
    val phase: RegistrationPhase = RegistrationPhase.Inactive,
    val device: Device? = null,
    val failure: CallFailure? = null,
)

/** Authoritative call snapshot; events are only transient change notifications. */
data class CallState(
    val session: StreamSessionState = StreamSessionState.Idle,
    val mode: CallMode = CallMode.Video,
    val callId: String? = null,
    val latestAttempt: CallAttempt? = null,
    val failure: CallFailure? = null,
    val peerName: String? = null,
    val peerCode: String? = null,
)

sealed interface PushHandlingResult {
    data object Unrelated : PushHandlingResult

    data object Handled : PushHandlingResult

    data object Stale : PushHandlingResult

    data object Inactive : PushHandlingResult

    data class RetryableFailure(val failure: CallFailure) : PushHandlingResult
}

enum class PushReadiness {
    MissingToken,
    Queued,
    Registered,
    Unavailable,
    Failed,
}

sealed interface PushTokenResult {
    data object Queued : PushTokenResult

    data object Registered : PushTokenResult

    data class Failure(val failure: CallFailure) : PushTokenResult
}
