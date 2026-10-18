package com.epiccrown.directstream.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epiccrown.directstream.*
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.ui.presentation.*
import kotlinx.coroutines.launch

/**
 * Call-state presentation only. The host owns users, device lookup, navigation and permission
 * requests. Register/request calls from host code. onAccept must obtain permissions/unlock before
 * Accept. The idle slot belongs to the host (e.g. contacts); no code entry, QR or sample settings
 * are included.
 */
@Composable
fun DirectStreamCallFlow(
    client: DirectStreamClient,
    onAccept: () -> Unit,
    onIdle: @Composable () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when (state.session) {
        is StreamSessionState.IncomingRequest ->
            DirectStreamIncoming(
                client,
                onAccept,
                onDecline = {
                    state.latestAttempt?.requestId?.let { id ->
                        scope.launch { client.decline(id) }
                    }
                },
            )
        is StreamSessionState.AwaitingApproval ->
            DirectStreamWaiting(
                client,
                onCancel = {
                    state.latestAttempt?.requestId?.let { id -> scope.launch { client.cancel(id) } }
                },
            )
        is StreamSessionState.Connected,
        StreamSessionState.Negotiating,
        StreamSessionState.Reconnecting -> DirectStreamInCall(client, onBack)
        else -> onIdle()
    }
}

@Composable
fun DirectStreamVideo(
    client: DirectStreamClient,
    stream: VideoStream,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        factory = { client.video.createView(it, stream, mirror) },
        update = { client.video.updateView(it, mirror) },
        onRelease = { client.video.releaseView(it) },
    )
}

/**
 * For custom navigation; hosts perform permission requests before issuing Accept/Request commands.
 */
@Composable
fun DirectStreamInCall(client: DirectStreamClient, onBack: (() -> Unit)? = null) {
    DisposableEffect(client) {
        client.setCallScreenVisible(true)
        onDispose { client.setCallScreenVisible(false) }
    }
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val callId = state.latestAttempt?.requestId
    fun action(block: suspend (String) -> Unit) {
        callId?.let { id -> scope.launch { block(id) } }
    }
    CallScreen(
        state,
        client.video,
        onEnd = { action { client.hangup(it) } },
        onFlip = { action { client.flipCamera(it) } },
        onMute = { muted -> action { client.setMuted(it, muted) } },
        onOutput = { output -> action { client.selectAudioOutput(it, output) } },
        onRefreshOutputs = { action { client.refreshAudioOutputs(it) } },
        onBack = onBack,
    )
}

@Composable
fun DirectStreamIncoming(client: DirectStreamClient, onAccept: () -> Unit, onDecline: () -> Unit) {
    val state by client.state.collectAsStateWithLifecycle()
    IncomingCallScreen(
        state,
        canAccept = !state.busy,
        onAccept = onAccept,
        onDecline = onDecline,
        onBack = onDecline,
    )
}

@Composable
fun DirectStreamWaiting(client: DirectStreamClient, onCancel: () -> Unit) {
    val state by client.state.collectAsStateWithLifecycle()
    WaitingScreen(state, onCancel = onCancel, onBack = onCancel)
}

/** Optional sample palette; production hosts may supply their own MaterialTheme. */
@Composable
fun DirectStreamSampleTheme(content: @Composable () -> Unit) {
    com.epiccrown.directstream.ui.presentation.theme.DirectStreamTheme(content = content)
}
