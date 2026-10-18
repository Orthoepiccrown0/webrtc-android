package com.epiccrown.directstream.showcase.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.showcase.R
import com.epiccrown.directstream.ui.presentation.CallContent
import com.epiccrown.directstream.ui.presentation.IncomingCallScreen
import com.epiccrown.directstream.ui.presentation.WaitingScreen

/**
 * Real presentation components with synthetic state. No client, provider, permissions or native
 * media.
 */
@Composable
internal fun ScreenExampleScreen(screen: ShowcaseScreen, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                stringResource(R.string.ds_ui_gallery_example_label, stringResource(screen.title)),
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Surface(Modifier.weight(1f)) {
            when (screen) {
                ShowcaseScreen.HOME ->
                    HomeScreen(ShowcaseData.home, null, onGallery = onBack, onSettings = onBack)
                ShowcaseScreen.CALL_ENTRY -> {
                    var code by rememberSaveable { mutableStateOf("DEF-567") }
                    CallEntryScreen(
                        code,
                        onInputChange = { code = it },
                        onBack = onBack,
                        scannerAvailable = false,
                    )
                }
                ShowcaseScreen.WAITING ->
                    WaitingScreen(
                        ShowcaseData.waiting.toCallSnapshot(),
                        onCancel = onBack,
                        onBack = onBack,
                    )
                ShowcaseScreen.INCOMING ->
                    IncomingCallScreen(
                        ShowcaseData.incoming.toCallSnapshot(),
                        onAccept = onBack,
                        onDecline = onBack,
                        onBack = onBack,
                    )
                ShowcaseScreen.IN_CALL,
                ShowcaseScreen.RECONNECTING,
                ShowcaseScreen.PAUSED,
                ShowcaseScreen.MICROPHONE_DENIED -> GalleryCall(screen, onBack)
                ShowcaseScreen.SETTINGS ->
                    SettingsScreen(
                        true,
                        setup =
                            com.epiccrown.directstream.SetupStatus(
                                "Sample provider",
                                com.epiccrown.directstream.api.ConfigurationCheck(),
                                com.epiccrown.directstream.InitializationStatus.READY,
                            ),
                        permissions = SamplePermissions(true, true, true),
                        codecState = codecPreviewState(),
                        onBack = onBack,
                    )
                ShowcaseScreen.MISSING_SETUP -> SettingsScreen(false, onBack = onBack)
                ShowcaseScreen.RETIRED_ALIAS ->
                    androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
                ShowcaseScreen.INTEGRATION -> IntegrationGuideScreen(onBack = onBack)
            }
        }
    }
}

@Composable
private fun GalleryCall(screen: ShowcaseScreen, onBack: () -> Unit) {
    var muted by rememberSaveable { mutableStateOf(false) }
    var outputId by rememberSaveable { mutableStateOf(1) }
    var front by rememberSaveable { mutableStateOf(true) }
    val base = ShowcaseData.call
    val state =
        base.copy(
            session =
                if (screen == ShowcaseScreen.RECONNECTING) StreamSessionState.Reconnecting
                else base.session,
            media =
                base.media.copy(
                    microphoneAvailable = screen != ShowcaseScreen.MICROPHONE_DENIED,
                    muted = muted || screen == ShowcaseScreen.MICROPHONE_DENIED,
                    remoteVideoPaused = screen == ShowcaseScreen.PAUSED,
                    output = base.media.outputs.firstOrNull { it.id == outputId },
                ),
        )
    CallContent(
        state.toCallSnapshot(),
        front = front,
        onBack = onBack,
        onEnd = onBack,
        onMute = { muted = it },
        onOutput = { outputId = it },
        onFlip = { front = !front },
        video = { feed, modifier, _ ->
            val local = feed == com.epiccrown.directstream.api.VideoStream.LOCAL
            Surface(
                modifier,
                color =
                    if (local) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.inverseSurface,
                contentColor =
                    if (local) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text(
                        stringResource(
                            if (local) R.string.ds_ui_gallery_local_video
                            else R.string.ds_ui_gallery_remote_video,
                        ),
                        style =
                            if (local) MaterialTheme.typography.labelSmall
                            else MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        },
    )
}

@ScreenPreviews
@Composable
internal fun ScreenExamplePreview() = PreviewTheme {
    ScreenExampleScreen(ShowcaseScreen.IN_CALL, {})
}
