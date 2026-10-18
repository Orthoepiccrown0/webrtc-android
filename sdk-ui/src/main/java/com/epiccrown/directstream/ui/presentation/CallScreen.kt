package com.epiccrown.directstream.ui.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticRole
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epiccrown.directstream.api.CallMediaState
import com.epiccrown.directstream.api.CallSnapshot
import com.epiccrown.directstream.api.CallVideoRenderer
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.api.VideoStream as VideoFeed
import com.epiccrown.directstream.ui.R
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    state: CallSnapshot,
    rtc: CallVideoRenderer,
    onEnd: () -> Unit,
    onFlip: () -> Unit,
    onMute: (Boolean) -> Unit,
    onOutput: (Int) -> Unit,
    onRefreshOutputs: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val remote by rtc.remoteAvailable.collectAsStateWithLifecycle()
    val local by rtc.localAvailable.collectAsStateWithLifecycle()
    val front by rtc.frontCamera.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bluetooth =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            onRefreshOutputs()
        }
    CallContent(
        state,
        remoteAvailable = remote,
        localAvailable = local,
        front = front,
        onEnd = onEnd,
        onFlip = onFlip,
        onMute = onMute,
        onOutput = onOutput,
        onRefreshOutputs = onRefreshOutputs,
        onBack = onBack,
        showBluetoothPermission =
            Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED,
        onBluetoothPermission = { bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) },
    ) { feed, modifier, mirror ->
        VideoSurface(rtc, feed, modifier, mirror)
    }
}

@Composable
fun CallContent(
    state: CallSnapshot,
    remoteAvailable: Boolean = true,
    localAvailable: Boolean = true,
    front: Boolean = true,
    onEnd: () -> Unit = {},
    onFlip: () -> Unit = {},
    onMute: (Boolean) -> Unit = {},
    onOutput: (Int) -> Unit = {},
    onRefreshOutputs: () -> Unit = {},
    showBluetoothPermission: Boolean = false,
    onBluetoothPermission: () -> Unit = {},
    initiallyExpanded: Boolean = false,
    onBack: (() -> Unit)? = null,
    video: @Composable (VideoFeed, Modifier, Boolean) -> Unit,
) {
    var details by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var outputs by remember { mutableStateOf(false) }
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    var now by remember {
        mutableLongStateOf(if (inspection) 1L else SystemClock.elapsedRealtime())
    }
    LaunchedEffect(inspection) {
        if (!inspection)
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(2_000)
            }
    }
    val view = LocalView.current
    if (!inspection)
        DisposableEffect(view, MaterialTheme.colorScheme.background) {
            val controller = view.windowInsetsController
            val previous = controller?.systemBarsAppearance ?: 0
            val mask =
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller?.setSystemBarsAppearance(0, mask)
            onDispose { controller?.setSystemBarsAppearance(previous, mask) }
        }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("call-screen")) {
        if (state.mode == com.epiccrown.directstream.api.CallMode.Audio)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.ds_ui_audio_call),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        else
            RemoteVideo(
                state.media.remoteVideoPaused,
                remoteAvailable,
                Modifier.fillMaxSize(),
                video,
            )
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            topBar = {
                CallTopBar(
                    state,
                    details,
                    onToggle = { details = !details },
                    onBack = onBack,
                    onFlip = onFlip,
                )
            },
            bottomBar = {
                Box(
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .8f)),
                            ),
                        )
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                            ),
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CallControls(
                        state,
                        onEnd = onEnd,
                        onMute = onMute,
                        onShowOutputs = {
                            outputs = true
                            onRefreshOutputs()
                        },
                    )
                }
            },
        ) { padding ->
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                val previewHeight = minOf(120.dp, maxHeight * .65f)
                val previewWidth = previewHeight * .75f
                val panelHeight = minOf(320.dp, maxHeight)
                if (state.mode == com.epiccrown.directstream.api.CallMode.Video)
                    LocalVideoPreview(
                        state.media.localVideoPaused,
                        localAvailable,
                        front,
                        Modifier.align(Alignment.BottomStart)
                            .height(previewHeight)
                            .width(previewWidth),
                        video,
                    )
                if (details)
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .width(
                                minOf(
                                    320.dp,
                                    (maxWidth - previewWidth - 16.dp).coerceAtLeast(48.dp),
                                ),
                            ),
                    ) {
                        ExpandedCallDiagnostics(
                            state,
                            panelHeight,
                            stale =
                                state.stats.sampledAtMs == 0L ||
                                    now - state.stats.sampledAtMs > 5_000,
                        )
                    }
            }
        }
    }
    if (outputs)
        AudioOutputDialog(
            state.media,
            showBluetoothPermission,
            onOutput,
            onBluetoothPermission,
            onDismiss = { outputs = false },
        )
}

@Composable
internal fun RemoteVideo(
    paused: Boolean,
    available: Boolean,
    modifier: Modifier = Modifier,
    video: @Composable (VideoFeed, Modifier, Boolean) -> Unit,
) {
    Box(modifier) {
        video(VideoFeed.REMOTE, Modifier.fillMaxSize().testTag("remote-video"), false)
        if (paused || !available)
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Text(
                    if (paused) stringResource(R.string.ds_ui_other_video_paused)
                    else stringResource(R.string.ds_ui_waiting_for_the_other_camera),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
    }
}

@Composable
internal fun LocalVideoPreview(
    paused: Boolean,
    available: Boolean,
    front: Boolean,
    modifier: Modifier = Modifier,
    video: @Composable (VideoFeed, Modifier, Boolean) -> Unit,
) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.DarkGray)
            .testTag("local-preview"),
    ) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val compactPlaceholder = maxHeight < (100 * fontScale).dp
        video(VideoFeed.LOCAL, Modifier.fillMaxSize(), front)
        if (paused || !available)
            Box(
                Modifier.fillMaxSize()
                    .background(Color.DarkGray)
                    .padding(bottom = (16 * fontScale + 12).dp),
                contentAlignment = Alignment.Center,
            ) {
                val description =
                    stringResource(
                        if (paused) R.string.ds_ui_video_paused else R.string.ds_ui_starting_camera,
                    )
                if (compactPlaceholder)
                    Icon(
                        painterResource(R.drawable.ds_ic_videocam),
                        description,
                        tint = Color.White,
                    )
                else
                    Text(
                        description,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
            }
        Text(
            stringResource(R.string.ds_ui_you),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = .55f))
                    .padding(6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallTopBar(
    state: CallSnapshot,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onFlip: () -> Unit = {},
) {
    CenterAlignedTopAppBar(
        expandedHeight =
            maxOf(
                64.dp,
                (96 * androidx.compose.ui.platform.LocalDensity.current.fontScale + 16).dp,
            ),
        modifier =
            Modifier.testTag("call-top-bar")
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = .7f), Color.Transparent),
                    ),
                ),
        colors =
            TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Transparent,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White,
            ),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PeerLabel(
                    state.peerDisplayName,
                    state.peerCode,
                    state.showDebugIdentifiers,
                    Color.White,
                )
                CallDiagnostics(state, expanded, onToggle = onToggle)
            }
        },
        navigationIcon = {
            if (onBack != null)
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ds_ui_back))
                }
        },
        actions = {
            if (state.mode == com.epiccrown.directstream.api.CallMode.Video)
                IconButton(
                    onClick = onFlip,
                    modifier = Modifier.size(48.dp),
                    enabled = !state.busy && !state.media.localVideoPaused,
                ) {
                    Icon(
                        painterResource(R.drawable.ds_ic_flip_camera_android),
                        stringResource(R.string.ds_ui_flip_camera),
                    )
                }
        },
    )
}

@Composable
internal fun CallDiagnostics(
    state: CallSnapshot,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val status =
        when (state.session) {
            is StreamSessionState.Connected -> stringResource(R.string.ds_ui_connected)
            StreamSessionState.Reconnecting -> stringResource(R.string.ds_ui_reconnecting)
            else -> stringResource(R.string.ds_ui_connecting)
        }
    val action =
        stringResource(if (expanded) R.string.ds_ui_hide_debug else R.string.ds_ui_more_debug)
    AssistChip(
        onClick = onToggle,
        modifier =
            modifier.heightIn(min = 48.dp).testTag("call-debug").semantics {
                stateDescription = action
            },
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .95f),
                labelColor = MaterialTheme.colorScheme.onSurface,
            ),
        label = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(status, style = MaterialTheme.typography.labelMedium)
                Text(
                    stringResource(
                        R.string.ds_ui_call_duration,
                        state.stats.elapsedMs / 60_000,
                        state.stats.elapsedMs / 1000 % 60,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

@Composable
internal fun CallControls(
    state: CallSnapshot,
    onEnd: () -> Unit = {},
    onMute: (Boolean) -> Unit = {},
    onShowOutputs: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("call-controls"),
    ) {
        if (state.media.interrupted || !state.media.microphoneAvailable)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .95f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    stringResource(
                        if (state.media.interrupted) R.string.ds_ui_audio_interrupted_by_another_app
                        else R.string.ds_ui_microphone_unavailable_receiving_audio_only,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconToggleButton(
                checked = state.media.muted,
                onCheckedChange = onMute,
                enabled = state.media.microphoneAvailable && !state.busy,
                shape = CircleShape,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painterResource(
                        if (state.media.muted || !state.media.microphoneAvailable)
                            R.drawable.ds_ic_mic_off
                        else R.drawable.ds_ic_mic,
                    ),
                    stringResource(
                        if (!state.media.microphoneAvailable) R.string.ds_ui_mic_off
                        else if (state.media.muted) R.string.ds_ui_unmute else R.string.ds_ui_mute,
                    ),
                )
            }
            FilledIconButton(
                onClick = onEnd,
                enabled = !state.busy,
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) {
                Icon(
                    painterResource(R.drawable.ds_ic_call_end),
                    stringResource(R.string.ds_ui_end_call),
                    Modifier.size(32.dp),
                )
            }
            FilledTonalIconButton(
                onClick = onShowOutputs,
                shape = CircleShape,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painterResource(
                        if (state.media.output?.bluetooth == true) R.drawable.ds_ic_headset
                        else R.drawable.ds_ic_volume_up,
                    ),
                    stringResource(
                        R.string.ds_ui_current_audio_output,
                        state.media.output?.label ?: stringResource(R.string.ds_ui_output_pending),
                    ),
                )
            }
        }
    }
}

@Composable
internal fun AudioOutputDialog(
    media: CallMediaState,
    showBluetoothPermission: Boolean = false,
    onOutput: (Int) -> Unit = {},
    onBluetoothPermission: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ds_ui_audio_output)) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                media.outputs.forEach { output ->
                    val selected = media.output?.id == output.id
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = selected,
                                role = SemanticRole.RadioButton,
                                onClick = {
                                    onOutput(output.id)
                                    onDismiss()
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(output.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (showBluetoothPermission)
                    TextButton(onClick = { onBluetoothPermission() }) {
                        Text(stringResource(R.string.ds_ui_enable_bluetooth_routing))
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_ui_done)) }
        },
    )
}

@Composable
internal fun VideoSurface(
    rtc: CallVideoRenderer,
    feed: VideoFeed,
    modifier: Modifier,
    mirror: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> rtc.createView(context, feed, mirror) },
        update = { rtc.updateView(it, mirror) },
        onRelease = { rtc.releaseView(it) },
    )
}

/**
 * Layoutlib-safe stand-in for the native renderer; never creates EGL, camera or audio resources.
 */
@Composable
internal fun PreviewVideo(feed: VideoFeed, modifier: Modifier = Modifier, mirror: Boolean = false) {
    Box(
        modifier.background(if (feed == VideoFeed.LOCAL) Color(0xFF345E58) else Color(0xFF203D39)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ds_ic_videocam),
                null,
                Modifier.size(if (feed == VideoFeed.LOCAL) 32.dp else 64.dp),
                tint = Color.White,
            )
            if (feed == VideoFeed.REMOTE)
                Text(
                    stringResource(R.string.ds_ui_remote_video),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
        }
    }
}

@ScreenPreviews
@Composable
internal fun InCallPreview() = PreviewTheme {
    CallContent(
        CallPreviewData.call,
        onBack = {},
        video = { feed, modifier, mirror -> PreviewVideo(feed, modifier, mirror) },
    )
}

@ScreenPreviews
@Composable
internal fun PausedInCallPreview() = PreviewTheme {
    CallContent(
        CallPreviewData.call.copy(
            media = CallPreviewData.media.copy(remoteVideoPaused = true, localVideoPaused = true),
        ),
        video = { feed, modifier, mirror -> PreviewVideo(feed, modifier, mirror) },
    )
}

@androidx.compose.ui.tooling.preview.Preview(name = "Landscape call", widthDp = 850, heightDp = 393)
@androidx.compose.ui.tooling.preview.Preview(
    name = "Landscape dark",
    widthDp = 850,
    heightDp = 393,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun LandscapeInCallPreview() = PreviewTheme {
    CallContent(
        CallPreviewData.call,
        onBack = {},
        video = { feed, modifier, mirror -> PreviewVideo(feed, modifier, mirror) },
    )
}

@ScreenPreviews
@Composable
internal fun ExpandedInCallPreview() = PreviewTheme {
    CallContent(
        CallPreviewData.call,
        initiallyExpanded = true,
        video = { feed, modifier, mirror -> PreviewVideo(feed, modifier, mirror) },
    )
}

@ComponentPreviews
@Composable
internal fun CallDiagnosticsPreview() = PreviewComponent { CallDiagnostics(CallPreviewData.call) }

@ComponentPreviews
@Composable
internal fun ExpandedCallDiagnosticsPreview() = PreviewComponent {
    ExpandedCallDiagnostics(CallPreviewData.call)
}

@ComponentPreviews
@Composable
internal fun CallControlsPreview() = PreviewComponent { CallControls(CallPreviewData.call) }

@ScreenPreviews
@Composable
internal fun AudioOutputDialogPreview() = PreviewTheme {
    AudioOutputDialog(CallPreviewData.media, showBluetoothPermission = true)
}

@ComponentPreviews
@Composable
internal fun RemoteVideoSurfacePreview() = PreviewComponent {
    RemoteVideo(false, true, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { feed, modifier, mirror
        ->
        PreviewVideo(feed, modifier, mirror)
    }
}

@ComponentPreviews
@Composable
internal fun LocalVideoSurfacePreview() = PreviewComponent {
    LocalVideoPreview(false, true, true, Modifier.width(110.dp).aspectRatio(3f / 4f)) {
        feed,
        modifier,
        mirror ->
        PreviewVideo(feed, modifier, mirror)
    }
}

@ComponentPreviews
@Composable
internal fun PausedLocalVideoPreview() = PreviewComponent {
    LocalVideoPreview(true, true, true, Modifier.width(110.dp).aspectRatio(3f / 4f)) {
        feed,
        modifier,
        mirror ->
        PreviewVideo(feed, modifier, mirror)
    }
}

@ComponentPreviews
@Composable
internal fun VideoPlaceholderPreview() = PreviewComponent {
    PreviewVideo(VideoFeed.REMOTE, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
}

@ComponentPreviews
@Composable
internal fun CallTopBarPreview() = PreviewTheme { CallTopBar(CallPreviewData.call, onBack = {}) }

@ScreenPreviews
@Composable
internal fun ReconnectingCallPreview() = PreviewTheme {
    CallContent(
        CallPreviewData.call.copy(session = StreamSessionState.Reconnecting),
        onBack = {},
        video = ::PreviewVideo,
    )
}

@ScreenPreviews
@Composable
internal fun MicrophoneDeniedCallPreview() = PreviewTheme {
    CallContent(
        CallPreviewData.call.copy(
            media = CallPreviewData.media.copy(microphoneAvailable = false, muted = true),
        ),
        onBack = {},
        video = ::PreviewVideo,
    )
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Large text light",
    widthDp = 393,
    heightDp = 850,
    fontScale = 2f,
)
@androidx.compose.ui.tooling.preview.Preview(
    name = "Large text dark",
    widthDp = 393,
    heightDp = 850,
    fontScale = 2f,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun LargeTextCallPreview() = PreviewTheme {
    CallContent(CallPreviewData.call, initiallyExpanded = true, onBack = {}, video = ::PreviewVideo)
}

@ScreenPreviews
@Composable
internal fun NamedCallPreview() = PreviewTheme {
    CallContent(CallPreviewData.call.copy(peerDisplayName = "River Otter", peerCode = "ABC234")) {
        _,
        _,
        _ ->
    }
}
