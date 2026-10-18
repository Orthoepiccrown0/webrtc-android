package com.epiccrown.directstream.showcase.ui

import com.epiccrown.directstream.api.*

data class RecentDevice(
    val id: String,
    val code: String?,
    val peerUid: String? = null,
    val peerName: String? = null,
    val requestId: String,
    val at: Long,
    val direction: CallDirection,
    val outcome: CallOutcome,
) {
    val displayName: String
        get() = peerName ?: code?.let(DeviceCode::format) ?: "Unknown device"
}

object RecentCalls {
    fun record(rows: List<RecentDevice>, event: CallAttempt): List<RecentDevice> {
        val code = event.code?.let(DeviceCode::normalize)
        val known = event.peerUid?.let { uid -> rows.firstOrNull { it.peerUid == uid } }
        val byCode =
            if (code != null) rows.firstOrNull { it.code == code }
            else rows.firstOrNull { it.requestId == event.requestId }
        val previous = listOfNotNull(known, byCode).maxByOrNull { it.at }
        val base = known ?: byCode
        val obsolete = setOfNotNull(known?.id, byCode?.id)
        val stale =
            previous != null &&
                ((event.at < previous.at && event.requestId != previous.requestId) ||
                    (event.at == previous.at &&
                        event.requestId != previous.requestId &&
                        event.outcome !in listOf(CallOutcome.CALLING, CallOutcome.INCOMING)))
        fun progress(outcome: CallOutcome): Int =
            when (outcome) {
                CallOutcome.CALLING,
                CallOutcome.INCOMING -> 0
                CallOutcome.ACCEPTED -> 1
                CallOutcome.CONNECTED -> 2
                else -> 3
            }
        val downgrade =
            previous != null &&
                previous.requestId == event.requestId &&
                progress(event.outcome) < progress(previous.outcome)
        val next =
            if (previous != null && (stale || downgrade))
                previous.copy(
                    id = base!!.id,
                    peerUid = event.peerUid ?: base.peerUid,
                    peerName = previous.peerName,
                )
            else
                RecentDevice(
                    base?.id ?: code ?: "unknown-${event.requestId}",
                    code,
                    event.peerUid ?: base?.peerUid,
                    event.peerName,
                    event.requestId,
                    event.at,
                    event.direction,
                    event.outcome,
                )
        return (rows.filterNot { it.id in obsolete } + next).sortedByDescending { it.at }.take(50)
    }
}
