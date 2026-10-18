package com.epiccrown.directstream.showcase.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.epiccrown.directstream.SetupStatus
import com.epiccrown.directstream.api.CallVideoRenderer
import com.epiccrown.directstream.api.DeviceCode
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.StreamPermissions
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.showcase.R
import com.epiccrown.directstream.ui.presentation.CallScreen
import com.epiccrown.directstream.ui.presentation.IncomingCallScreen
import com.epiccrown.directstream.ui.presentation.WaitingScreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    rtc: CallVideoRenderer,
    initialCode: String?,
    notificationRequestId: String? = null,
    notificationEvent: Int = 0,
    returnToCallEvent: Int = 0,
    unlockForCall: ((Boolean) -> Unit) -> Unit = { completed -> completed(true) },
    lockScreenOnly: Boolean = false,
    setup: SetupStatus? = null,
    sampleVersion: String = "—",
    scannerAvailable: Boolean = true,
    scan: ((String) -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val initialIncoming = (state.session as? StreamSessionState.IncomingRequest)?.request
    val backStack =
        rememberNavBackStack(
            CallRoute.Home,
            *if (initialIncoming != null) arrayOf(CallRoute.IncomingCall(initialIncoming.requestId))
            else emptyArray(),
        )
    val destinationName = backStack.lastOrNull()?.javaClass?.simpleName ?: "Home"
    LaunchedEffect(destinationName) {
        timber.log.Timber.tag("Navigation").d("Destination: %s", destinationName)
    }
    var presentedCall by rememberSaveable { mutableStateOf(false) }
    fun open(route: CallRoute) = backStack.openOnce(route)
    fun home() = backStack.showHome()
    var input by rememberSaveable { mutableStateOf("") }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<PendingCallAction?>(null) }
    var selectedMode by rememberSaveable {
        mutableStateOf(com.epiccrown.directstream.api.CallMode.Video)
    }
    var pendingMode by remember { mutableStateOf(com.epiccrown.directstream.api.CallMode.Video) }
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var pendingRequestId by remember { mutableStateOf<String?>(null) }
    var withoutMic by rememberSaveable { mutableStateOf(false) }
    var resetDialog by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val receiverCard = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val active =
        state.session is StreamSessionState.Negotiating ||
            state.session is StreamSessionState.Connected ||
            state.session is StreamSessionState.Reconnecting
    fun acceptInput(value: String) {
        open(CallRoute.CallEntry)
        runCatching { DeviceCode.format(DeviceLinks.fromInput(value)) }
            .onSuccess {
                input = it
                inputError = null
            }
            .onFailure {
                inputError =
                    resources.getString(
                        R.string.ds_ui_enter_a_valid_device_code_or_scan_a_direct_stream_pairing_qr,
                    )
            }
    }
    fun commit(microphone: Boolean) {
        val action = pendingAction
        val code = pendingCode
        val requestId = pendingRequestId
        pendingAction = null
        pendingCode = null
        pendingRequestId = null
        withoutMic = false
        if (action == PendingCallAction.ACCEPT) {
            val request =
                (viewModel.state.value.session as? StreamSessionState.IncomingRequest)?.request
            if (
                request == null ||
                    request.requestId != requestId ||
                    request.expiresAt <= System.currentTimeMillis()
            ) {
                permissionMessage =
                    resources.getString(R.string.ds_ui_this_call_request_is_no_longer_available)
                return
            }
            viewModel.dispatch(PairingIntent.Approve(microphone))
        } else if (action == PendingCallAction.CALL && code != null)
            viewModel.dispatch(PairingIntent.Connect(code, microphone, pendingMode))
    }
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) commit(true) else withoutMic = true
        }
    fun requestMic() {
        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
            commit(true)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val requiredPermissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (
                StreamPermissions.required(Build.VERSION.SDK_INT, Role.SENDER, pendingMode).all {
                    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }
            )
                requestMic()
            else {
                pendingAction = null
                pendingCode = null
                pendingRequestId = null
                permissionMessage =
                    resources.getString(R.string.ds_ui_camera_access_is_required_on_android_17_also)
            }
        }
    fun start(accept: Boolean) {
        if (state.busy || pendingAction != null) return
        if (!accept) {
            val normalized = runCatching {
                DeviceLinks.fromInput(input)
            }
                .getOrElse {
                    inputError =
                        resources.getString(
                            R.string
                                .ds_ui_enter_a_valid_device_code_or_scan_a_direct_stream_pairing_qr,
                        )
                    return
                }
            if (normalized == state.device?.code) {
                inputError = resources.getString(R.string.ds_ui_enter_the_other_device_s_code)
                return
            }
            pendingCode = normalized
        } else {
            val request = (state.session as? StreamSessionState.IncomingRequest)?.request
            if (request == null || request.expiresAt <= System.currentTimeMillis()) {
                permissionMessage =
                    resources.getString(R.string.ds_ui_this_call_request_is_no_longer_available)
                return
            }
            pendingRequestId = request.requestId
        }
        pendingMode =
            if (accept)
                (state.session as? StreamSessionState.IncomingRequest)?.request?.mode
                    ?: com.epiccrown.directstream.api.CallMode.Video
            else selectedMode
        pendingAction = if (accept) PendingCallAction.ACCEPT else PendingCallAction.CALL
        permissionMessage = null
        inputError = null
        focus.clearFocus()
        keyboard?.hide()
        val missing =
            StreamPermissions.required(Build.VERSION.SDK_INT, Role.SENDER, pendingMode).filter {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) requestMic() else requiredPermissions.launch(missing.toTypedArray())
    }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val alertPermissions = rememberSamplePermissions(permissionRevision)
    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            permissionRevision++
            if (!allowed)
                permissionMessage =
                    resources.getString(
                        R.string.ds_ui_notifications_are_disabled_open_the_app_to_check,
                    )
        }
    LaunchedEffect(Unit) { viewModel.dispatch(PairingIntent.Initialize) }
    LaunchedEffect(initialCode) { if (initialCode != null && !active) acceptInput(initialCode) }
    LaunchedEffect(state.ready, notificationRequestId, notificationEvent) {
        if (state.ready && notificationRequestId != null)
            viewModel.dispatch(PairingIntent.ReviewNotification(notificationRequestId))
    }
    // Session identity drives navigation, not polling frequency or media-stat updates.
    LaunchedEffect(
        state.session::class,
        (state.session as? StreamSessionState.AwaitingApproval)?.request?.requestId,
        (state.session as? StreamSessionState.IncomingRequest)?.request?.requestId,
    ) {
        presentedCall = backStack.reconcileSession(state.session, presentedCall)
    }
    LaunchedEffect(returnToCallEvent) {
        if (active && returnToCallEvent > 0) open(CallRoute.InCall)
    }
    val callDisplayed = active && backStack.lastOrNull() == CallRoute.InCall
    DisposableEffect(callDisplayed) {
        viewModel.setCallScreenVisible(callDisplayed)
        onDispose { viewModel.setCallScreenVisible(false) }
    }
    LaunchedEffect(imeBottom, focused) {
        if (imeBottom > 0 && focused) receiverCard.bringIntoView()
    }
    fun back() {
        when (backStack.lastOrNull()) {
            is CallRoute.Waiting ->
                viewModel.dispatch(if (active) PairingIntent.Disconnect else PairingIntent.Cancel)
            is CallRoute.IncomingCall ->
                viewModel.dispatch(if (active) PairingIntent.Disconnect else PairingIntent.Reject)
            CallRoute.InCall -> home()
            else -> if (backStack.size > 1) backStack.removeLastOrNull()
        }
    }
    // Never animate Home or recents behind the incoming UI on a secure lock screen.
    if (lockScreenOnly && state.session is StreamSessionState.IncomingRequest) {
        androidx.activity.compose.BackHandler { viewModel.dispatch(PairingIntent.Reject) }
        IncomingCallScreen(
            state.toCallSnapshot(),
            permissionMessage,
            canAccept = !state.busy && pendingAction == null,
            onAccept = {
                unlockForCall { unlocked ->
                    if (unlocked) start(true)
                    else
                        permissionMessage =
                            resources.getString(
                                R.string.ds_ui_unlock_the_device_to_accept_this_call,
                            )
                }
            },
            onDecline = { viewModel.dispatch(PairingIntent.Reject) },
            onBack = { viewModel.dispatch(PairingIntent.Reject) },
        )
    } else
        NavDisplay(
            backStack = backStack,
            onBack = ::back,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider =
                entryProvider {
                    entry<CallRoute.InCall> {
                        if (active)
                            CallScreen(
                                state.toCallSnapshot(),
                                rtc,
                                onBack = ::back,
                                onEnd = { viewModel.dispatch(PairingIntent.Disconnect) },
                                onFlip = { viewModel.dispatch(PairingIntent.SwitchCamera) },
                                onMute = { viewModel.dispatch(PairingIntent.Mute(it)) },
                                onOutput = { viewModel.dispatch(PairingIntent.AudioOutput(it)) },
                                onRefreshOutputs = {
                                    viewModel.dispatch(PairingIntent.RefreshAudioOutputs)
                                },
                            )
                    }
                    entry<CallRoute.Home> {
                        HomeScreen(
                            state,
                            permissionMessage,
                            notificationsAllowed = alertPermissions.notifications,
                            highPriorityIncomingChannel =
                                alertPermissions.highPriorityIncomingChannel,
                            fullScreenAllowed = alertPermissions.fullScreen,
                            onCall = {
                                input = ""
                                inputError = null
                                open(CallRoute.CallEntry)
                            },
                            onRecentCall = { row ->
                                row.code?.let {
                                    input = DeviceCode.format(it)
                                    start(false)
                                } ?: open(CallRoute.CallEntry)
                            },
                            onUpdate = { row ->
                                input = row.code?.let(DeviceCode::format).orEmpty()
                                open(CallRoute.CallEntry)
                            },
                            onRemove = { viewModel.dispatch(PairingIntent.RemoveRecent(it)) },
                            onCopy = { code ->
                                (context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager)
                                    .setPrimaryClip(
                                        ClipData.newPlainText(
                                            resources.getString(R.string.ds_ui_device_code),
                                            DeviceCode.format(code),
                                        ),
                                    )
                            },
                            onShare = { code ->
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                resources.getString(
                                                    R.string.ds_ui_share_message,
                                                    code,
                                                ),
                                            )
                                        },
                                        resources.getString(R.string.ds_ui_share_device_code),
                                    ),
                                )
                            },
                            onGallery = { open(CallRoute.ScreenGallery) },
                            onRefresh = { resetDialog = true },
                            onSettings = { open(CallRoute.Settings) },
                            onReturn = { open(CallRoute.InCall) },
                            onEnd = { viewModel.dispatch(PairingIntent.Disconnect) },
                            onRetry = { viewModel.dispatch(PairingIntent.Initialize) },
                        )
                    }
                    entry<CallRoute.CallEntry> {
                        CallEntryScreen(
                            input,
                            onInputChange = {
                                input = it
                                inputError = null
                            },
                            onSubmit = { start(false) },
                            onScan = {
                                focus.clearFocus()
                                keyboard?.hide()
                                scan(::acceptInput)
                            },
                            onBack = ::back,
                            scannerAvailable = scannerAvailable,
                            inputError = inputError,
                            error = state.error,
                            permissionMessage = permissionMessage,
                            busy = state.busy,
                            requestEnabled =
                                !state.busy &&
                                    input.isNotBlank() &&
                                    inputError == null &&
                                    pendingAction == null,
                            cardModifier = Modifier.bringIntoViewRequester(receiverCard),
                            inputModifier = Modifier.onFocusChanged { focused = it.isFocused },
                            mode = selectedMode,
                            availableModes =
                                state.device?.supportedCallModes
                                    ?: setOf(com.epiccrown.directstream.api.CallMode.Video),
                            onModeChange = { selectedMode = it },
                        )
                    }
                    entry<CallRoute.Waiting> {
                        WaitingScreen(
                            state.toCallSnapshot(),
                            onCancel = { viewModel.dispatch(PairingIntent.Cancel) },
                            onBack = ::back,
                        )
                    }
                    entry<CallRoute.IncomingCall> {
                        IncomingCallScreen(
                            state.toCallSnapshot(),
                            permissionMessage,
                            canAccept = !state.busy && pendingAction == null,
                            onAccept = {
                                unlockForCall { unlocked ->
                                    if (unlocked) start(true)
                                    else
                                        permissionMessage =
                                            resources.getString(
                                                R.string
                                                    .ds_ui_unlock_the_device_to_accept_this_call,
                                            )
                                }
                            },
                            onDecline = { viewModel.dispatch(PairingIntent.Reject) },
                            onBack = ::back,
                        )
                    }
                    entry<CallRoute.Settings> {
                        SettingsRoute(
                            permissionRevision = permissionRevision,
                            codecState = state.codecs,
                            onCodecPreferences = {
                                viewModel.dispatch(PairingIntent.CodecPreferences(it))
                            },
                            hasRecents = state.recents.isNotEmpty(),
                            setup = setup,
                            sampleVersion = sampleVersion,
                            onEnableNotifications = {
                                if (
                                    Build.VERSION.SDK_INT >= 33 &&
                                        context.checkSelfPermission(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED
                                )
                                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else
                                    context.startActivity(
                                        Intent(
                                                android.provider.Settings
                                                    .ACTION_APP_NOTIFICATION_SETTINGS,
                                            )
                                            .putExtra(
                                                android.provider.Settings.EXTRA_APP_PACKAGE,
                                                context.packageName,
                                            ),
                                    )
                            },
                            onClearRecents = { viewModel.dispatch(PairingIntent.ClearRecents) },
                            onIntegration = { open(CallRoute.IntegrationGuide) },
                            onBack = ::back,
                        )
                    }
                    entry<CallRoute.IntegrationGuide> {
                        IntegrationGuideScreen(
                            onBack = ::back,
                            onCopy = { code ->
                                (context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager)
                                    .setPrimaryClip(
                                        ClipData.newPlainText(
                                            resources.getString(R.string.ds_ui_sdk_snippet),
                                            code,
                                        ),
                                    )
                            },
                        )
                    }
                    entry<CallRoute.RetiredAlias> {
                        LaunchedEffect(Unit) { home() }
                    }
                    entry<CallRoute.ScreenGallery> {
                        ScreenGalleryScreen(
                            onSelect = { open(CallRoute.ScreenExample(it)) },
                            onBack = ::back,
                        )
                    }
                    entry<CallRoute.ScreenExample> { route ->
                        ScreenExampleScreen(route.screen, onBack = ::back)
                    }
                },
        )
    if (withoutMic)
        MicrophoneDialog(
            onDismiss = {
                withoutMic = false
                pendingAction = null
            },
            onContinue = { commit(false) },
        )
    if (resetDialog)
        ResetCodeDialog(
            onDismiss = { resetDialog = false },
            onConfirm = {
                resetDialog = false
                viewModel.dispatch(PairingIntent.ResetCode)
            },
        )
}
