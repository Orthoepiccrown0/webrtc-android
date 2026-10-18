package com.epiccrown.directstream.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epiccrown.directstream.*
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.incoming.IncomingCallPresentation
import kotlinx.coroutines.launch

/** UI actions perform permission/unlock preflight; direct client operations never launch UI. */
class DirectStreamCallUi
internal constructor(
    val startCall: (String, CallMode) -> Unit,
    val accept: () -> Unit,
    val decline: () -> Unit,
    val cancel: () -> Unit,
    val end: () -> Unit,
    val returnToCall: () -> Unit,
    val backToApp: () -> Unit,
    val requestIncomingAlerts: () -> Unit,
)

private data class PendingCall(val code: String?, val callId: String?, val mode: CallMode)

/**
 * Embed once around the host's Compose navigation. The host activates the client and forwards push.
 * Custom call content replaces presentation only; permission and lifecycle handling remain shared.
 */
@Composable
fun DirectStreamHost(
    client: DirectStreamClient,
    modifier: Modifier = Modifier,
    callContent: (@Composable (DirectStreamCallUi) -> Unit)? = null,
    content: @Composable (DirectStreamCallUi) -> Unit,
) {
    val activity =
        LocalActivity.current as? ComponentActivity
            ?: error("DirectStreamHost requires a ComponentActivity.")
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val presentation = remember(activity, client) { IncomingCallPresentation(activity, client) }
    val locked by presentation.lockScreenOnly.collectAsStateWithLifecycle()
    var showCall by remember(client) { mutableStateOf(true) }
    var pending by remember(client) { mutableStateOf<PendingCall?>(null) }
    var microphoneDenied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val permissionError = stringResource(R.string.ds_ui_permission_required)
    val invalidCode = stringResource(R.string.ds_ui_invalid_recipient)
    val unsupportedMode = stringResource(R.string.ds_ui_unsupported_call_mode)

    fun finishPreflight(microphone: Boolean) {
        val action = pending ?: return
        pending = null
        microphoneDenied = false
        scope.launch {
            val result =
                if (action.callId != null) client.accept(action.callId, microphone)
                else client.startCall(requireNotNull(action.code), action.mode, microphone)
            if (result is CallResult.Failure) error = result.error.message
        }
    }
    val mic =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) finishPreflight(true) else microphoneDenied = true
        }
    fun requestMicrophone() {
        if (
            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
            finishPreflight(true)
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val action = pending
            if (
                action != null &&
                    StreamPermissions.required(Build.VERSION.SDK_INT, Role.SENDER, action.mode)
                        .all {
                            activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                        }
            )
                requestMicrophone()
            else {
                pending = null
                error = permissionError
            }
        }
    val alerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun prepare(action: PendingCall) {
        if (pending != null || state.busy) return
        pending = action
        error = null
        presentation.unlock { unlocked ->
            if (!unlocked) {
                pending = null
                return@unlock
            }
            val missing =
                StreamPermissions.required(Build.VERSION.SDK_INT, Role.SENDER, action.mode).filter {
                    activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
            if (missing.isEmpty()) requestMicrophone()
            else permissions.launch(missing.toTypedArray())
        }
    }
    val presentedCallId = state.latestAttempt?.requestId
    val ui =
        DirectStreamCallUi(
            startCall = { raw, mode ->
                val code = runCatching { DeviceCode.normalize(raw) }.getOrNull()
                if (state.device?.supportedCallModes?.contains(mode) == false)
                    error = unsupportedMode
                else if (code == null || code == state.device?.code) error = invalidCode
                else {
                    showCall = true
                    prepare(PendingCall(code, null, mode))
                }
            },
            accept = {
                (state.session as? StreamSessionState.IncomingRequest)?.request?.let {
                    prepare(PendingCall(null, it.requestId, it.mode))
                }
            },
            decline = { presentedCallId?.let { id -> scope.launch { client.decline(id) } } },
            cancel = { presentedCallId?.let { id -> scope.launch { client.cancel(id) } } },
            end = { presentedCallId?.let { id -> scope.launch { client.hangup(id) } } },
            returnToCall = { showCall = true },
            backToApp = { showCall = false },
            requestIncomingAlerts = {
                if (Build.VERSION.SDK_INT >= 33)
                    alerts.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    fun readIntent(intent: Intent) {
        val request = intent.getStringExtra("requestId")
        if (request != null) {
            intent.removeExtra("requestId")
            scope.launch { client.reviewIncoming(request) }
        }
        if (intent.getBooleanExtra("returnToCall", false)) {
            intent.removeExtra("returnToCall")
            showCall = true
        }
    }
    DisposableEffect(activity, client) {
        val intents = Consumer<Intent> { readIntent(it) }
        activity.addOnNewIntentListener(intents)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> {
                    client.setVisible(true)
                    readIntent(activity.intent)
                }
                Lifecycle.Event.ON_STOP ->
                    if (!activity.isChangingConfigurations) {
                        pending = null
                        microphoneDenied = false
                        client.setVisible(false)
                    }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            client.setVisible(true)
            readIntent(activity.intent)
        }
        onDispose {
            activity.removeOnNewIntentListener(intents)
            activity.lifecycle.removeObserver(observer)
            if (!activity.isChangingConfigurations) client.setVisible(false)
        }
    }
    LaunchedEffect(client) { if (client.isActivated) client.activate() }
    LaunchedEffect(state.latestAttempt?.requestId) {
        if (state.latestAttempt != null) showCall = true
    }
    val active =
        state.session is StreamSessionState.Connected ||
            state.session is StreamSessionState.Reconnecting ||
            state.session == StreamSessionState.Negotiating
    val incoming = state.session is StreamSessionState.IncomingRequest
    val waiting =
        state.session is StreamSessionState.AwaitingApproval ||
            state.session == StreamSessionState.RequestingConnection
    val displaying = incoming || waiting || active && showCall
    DisposableEffect(client, active, showCall, locked) {
        client.setCallScreenVisible(active && showCall && !locked)
        onDispose { client.setCallScreenVisible(false) }
    }
    BackHandler(displaying) {
        pending = null
        microphoneDenied = false
        when {
            incoming -> ui.decline()
            waiting -> ui.cancel()
            active -> ui.backToApp()
        }
    }
    Box(modifier.fillMaxSize()) {
        if (displaying) {
            if (callContent != null) callContent(ui)
            else
                when {
                    incoming -> DirectStreamIncoming(client, ui.accept, ui.decline)
                    waiting -> DirectStreamWaiting(client, ui.cancel)
                    active -> DirectStreamInCall(client, ui.backToApp)
                }
        } else if (locked) {
            Surface(Modifier.fillMaxSize()) {
                Text(
                    stringResource(R.string.ds_ui_unlock_to_continue),
                    Modifier.safeDrawingPadding().padding(24.dp),
                )
            }
        } else
            Column(Modifier.fillMaxSize()) {
                if (active)
                    AssistChip(
                        onClick = ui.returnToCall,
                        label = { Text(stringResource(R.string.ds_ui_return_to_call)) },
                        modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp),
                    )
                if (error != null)
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                if (!active && state.outcome != null)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(state.outcome!!, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { client.dismissOutcome() } }) {
                            Text(stringResource(R.string.ds_ui_dismiss))
                        }
                    }
                Box(Modifier.weight(1f)) { content(ui) }
            }
    }
    if (microphoneDenied)
        AlertDialog(
            onDismissRequest = {
                microphoneDenied = false
                pending = null
            },
            title = { Text(stringResource(R.string.ds_ui_microphone_denied)) },
            text = { Text(stringResource(R.string.ds_ui_receive_only_audio)) },
            confirmButton = {
                TextButton(onClick = { finishPreflight(false) }) {
                    Text(stringResource(R.string.ds_ui_continue_without_microphone))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        microphoneDenied = false
                        pending = null
                    },
                ) {
                    Text(stringResource(R.string.ds_ui_cancel_request))
                }
            },
        )
}
