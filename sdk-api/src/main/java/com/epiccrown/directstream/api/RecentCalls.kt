package com.epiccrown.directstream.api

enum class CallDirection {
    INCOMING,
    OUTGOING,
}

enum class CallOutcome {
    CALLING,
    INCOMING,
    ACCEPTED,
    CONNECTED,
    COMPLETED,
    MISSED,
    DECLINED,
    CANCELLED,
    FAILED,
}

data class CallAttempt(
    val code: String?,
    val requestId: String,
    val at: Long,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val peerUid: String? = null,
    val peerName: String? = null,
)
