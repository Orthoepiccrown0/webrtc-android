package com.epiccrown.directstream.showcase.ui

import androidx.navigation3.runtime.NavKey
import com.epiccrown.directstream.api.StreamSessionState

/** Navigation policy only: permission requests, media and commands belong to their owners. */
internal fun MutableList<NavKey>.openOnce(route: CallRoute) {
    if (lastOrNull() != route) add(route)
}

internal fun MutableList<NavKey>.showHome() {
    clear()
    add(CallRoute.Home)
}

/** Returns whether this live call has already been presented, preserving Back-to-Home behavior. */
internal fun MutableList<NavKey>.reconcileSession(
    session: StreamSessionState,
    presentedCall: Boolean,
): Boolean {
    fun replaceCall(route: CallRoute) {
        showHome()
        openOnce(route)
    }
    return when (session) {
        StreamSessionState.RequestingConnection -> {
            replaceCall(CallRoute.Waiting("submitting"))
            false
        }
        is StreamSessionState.AwaitingApproval -> {
            replaceCall(CallRoute.Waiting(session.request.requestId))
            false
        }
        is StreamSessionState.IncomingRequest -> {
            replaceCall(CallRoute.IncomingCall(session.request.requestId))
            false
        }
        StreamSessionState.Negotiating -> {
            replaceCall(CallRoute.InCall)
            true
        }
        is StreamSessionState.Connected,
        StreamSessionState.Reconnecting -> {
            if (!presentedCall) replaceCall(CallRoute.InCall)
            true
        }
        else -> {
            if (
                any {
                    it is CallRoute.Waiting ||
                        it is CallRoute.IncomingCall ||
                        it == CallRoute.InCall
                }
            )
                showHome()
            false
        }
    }
}
