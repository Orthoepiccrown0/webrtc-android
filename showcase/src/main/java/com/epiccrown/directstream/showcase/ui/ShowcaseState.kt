package com.epiccrown.directstream.showcase.ui

import com.epiccrown.directstream.api.*

data class ShowcaseState(
    val codecs: CallCodecState = CallCodecState(),
    val ready: Boolean = false,
    val mode: CallMode = CallMode.Video,
    val latestAttempt: CallAttempt? = null,
    val device: Device? = null,
    val session: StreamSessionState = StreamSessionState.Idle,
    val stats: StreamStats = StreamStats(),
    val busy: Boolean = false,
    val failure: CallFailure? = null,
    val error: String? = null,
    val outcome: String? = null,
    val media: CallMediaState = CallMediaState(),
    val recents: List<RecentDevice> = emptyList(),
    val online: Boolean = true,
    val peerDisplayName: String? = null,
    val peerCode: String? = null,
    val showDebugIdentifiers: Boolean = false,
)

fun ShowcaseState.toCallSnapshot(): CallSnapshot =
    CallSnapshot(
        codecs,
        ready,
        mode,
        latestAttempt,
        device,
        session,
        stats,
        busy,
        failure,
        error,
        outcome,
        media,
        online,
        peerDisplayName,
        peerCode,
        showDebugIdentifiers,
    )

fun CallSnapshot.toShowcaseState(recents: List<RecentDevice>) =
    ShowcaseState(
        codecs,
        ready,
        mode,
        latestAttempt,
        device,
        session,
        stats,
        busy,
        failure,
        error,
        outcome,
        media,
        recents,
        online,
        peerDisplayName,
        peerCode,
        showDebugIdentifiers,
    )
