package com.epiccrown.directstream.ui.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.epiccrown.directstream.api.CallSnapshot
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.ui.R

@Composable
fun WaitingScreen(state: CallSnapshot, onCancel: () -> Unit = {}, onBack: () -> Unit = {}) {
    Page(stringResource(R.string.ds_ui_calling), onBack) {
        val request = (state.session as? StreamSessionState.AwaitingApproval)?.request
        if (request != null) {
            PeerName(state, request)
            RequestCard(
                stringResource(R.string.ds_ui_waiting_for_the_other_device_to_accept),
                request,
            )
            Text(
                stringResource(
                    if (state.mode == com.epiccrown.directstream.api.CallMode.Audio)
                        R.string.ds_ui_compare_audio_value
                    else R.string.ds_ui_compare_this_verification_value_camera_and_audio_start,
                ),
            )
        } else LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = onCancel) { Text(stringResource(R.string.ds_ui_cancel_request)) }
    }
}

@ScreenPreviews
@Composable
internal fun WaitingPreview() = PreviewTheme { WaitingScreen(CallPreviewData.waiting) }

@ScreenPreviews
@Composable
internal fun WaitingAudioPreview() = PreviewTheme {
    WaitingScreen(
        CallPreviewData.waiting.copy(mode = com.epiccrown.directstream.api.CallMode.Audio),
    )
}
