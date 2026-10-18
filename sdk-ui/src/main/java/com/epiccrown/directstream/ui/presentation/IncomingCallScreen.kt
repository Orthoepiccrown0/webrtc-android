package com.epiccrown.directstream.ui.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.api.CallSnapshot
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.ui.R

@Composable
fun IncomingCallScreen(
    state: CallSnapshot,
    message: String? = null,
    canAccept: Boolean = !state.busy,
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val request = (state.session as? StreamSessionState.IncomingRequest)?.request
    Page(
        stringResource(
            if (state.mode == com.epiccrown.directstream.api.CallMode.Audio)
                R.string.ds_ui_incoming_audio_call
            else R.string.ds_ui_incoming_video_call,
        ),
        onBack,
        bottomBar = {
            if (request != null)
                IncomingCallActions(
                    canAccept,
                    !state.busy,
                    message ?: state.error,
                    onAccept,
                    onDecline,
                )
        },
    ) {
        if (request != null) {
            IncomingCallerCard(state, request)
            RequestCard(stringResource(R.string.ds_ui_compare_with_the_caller), request)
            Text(
                stringResource(R.string.ds_ui_only_accept_if_you_expected_this_call_a),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun IncomingCallerCard(state: CallSnapshot, request: PairingRequest) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = colors.primaryContainer) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                if (state.mode == com.epiccrown.directstream.api.CallMode.Audio)
                    Icon(Icons.Default.Phone, null, Modifier.size(56.dp))
                else CameraGlyph(Modifier.size(56.dp))
            }
        }
        PeerName(state, request)
        Text(
            stringResource(
                if (state.mode == com.epiccrown.directstream.api.CallMode.Audio)
                    R.string.ds_ui_wants_to_audio_call_you
                else R.string.ds_ui_wants_to_video_call_you,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun IncomingCallActions(
    canAccept: Boolean = true,
    canDecline: Boolean = true,
    message: String? = null,
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            message?.let {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(
                    R.string.ds_ui_camera_and_microphone_start_after_you_accept_unlocking,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onDecline,
                    enabled = canDecline,
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = colors.onError,
                        ),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text(stringResource(R.string.ds_ui_decline))
                    }
                }
                Button(
                    onClick = onAccept,
                    enabled = canAccept,
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Phone, contentDescription = null)
                        Text(stringResource(R.string.ds_ui_accept))
                    }
                }
            }
        }
    }
}

@ScreenPreviews
@Composable
internal fun IncomingCallPreview() = PreviewTheme { IncomingCallScreen(CallPreviewData.incoming) }

@ComponentPreviews
@Composable
internal fun IncomingCallerCardPreview() = PreviewComponent {
    IncomingCallerCard(CallPreviewData.home, CallPreviewData.request)
}

@ComponentPreviews
@Composable
internal fun IncomingCallActionsPreview() = PreviewComponent { IncomingCallActions() }

@ScreenPreviews
@Composable
internal fun IncomingAudioCallPreview() = PreviewTheme {
    IncomingCallScreen(
        CallPreviewData.incoming.copy(mode = com.epiccrown.directstream.api.CallMode.Audio),
    )
}

@ScreenPreviews
@Composable
internal fun NamedIncomingPreview() = PreviewTheme {
    IncomingCallScreen(CallPreviewData.incoming.copy(peerDisplayName = "River Otter"))
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Named incoming large text",
    fontScale = 1.8f,
    widthDp = 320,
    heightDp = 720,
)
@Composable
internal fun NamedIncomingLargeTextPreview() = PreviewTheme {
    IncomingCallScreen(CallPreviewData.incoming.copy(peerDisplayName = "River Otter"))
}
