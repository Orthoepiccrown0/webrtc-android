package com.epiccrown.directstream.ui.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.ui.R

@Composable
internal fun CodecDiagnostics(state: CallSnapshot) {
    @Composable
    fun policy(value: CodecPolicy?): String =
        when (value) {
            null -> "—"
            CodecPolicy.Auto -> stringResource(R.string.ds_ui_codec_auto)
            is CodecPolicy.Prefer -> stringResource(R.string.ds_ui_codec_prefer, value.codec.name)
            is CodecPolicy.Enforce ->
                stringResource(R.string.ds_ui_codec_policy_enforce, value.codec.name)
        }
    val status =
        stringResource(
            when (state.codecs.phase) {
                CodecPhase.IDLE -> R.string.ds_ui_codec_idle
                CodecPhase.PREPARING -> R.string.ds_ui_codec_preparing
                CodecPhase.APPLYING -> R.string.ds_ui_codec_applying
                CodecPhase.ACTIVE -> R.string.ds_ui_codec_active
                CodecPhase.ROLLING_BACK -> R.string.ds_ui_codec_rolling_back
                CodecPhase.FAILED -> R.string.ds_ui_codec_failed
            },
        )
    if (state.mode == CallMode.Video) {
        DiagnosticRow(
            stringResource(R.string.ds_ui_debug_requested_video),
            policy(state.codecs.requested?.video ?: state.codecs.active?.video),
        )
        DiagnosticRow(
            stringResource(R.string.ds_ui_debug_selected_video),
            state.codecs.selection?.video?.name ?: "—",
        )
    }
    DiagnosticRow(
        stringResource(R.string.ds_ui_debug_requested_audio),
        policy(state.codecs.requested?.audio ?: state.codecs.active?.audio),
    )
    DiagnosticRow(
        stringResource(R.string.ds_ui_debug_selected_audio),
        state.codecs.selection?.audio?.name ?: "—",
    )
    DiagnosticRow(stringResource(R.string.ds_ui_debug_phase), status)
    state.codecs.failure?.let {
        val failure =
            when (it) {
                CodecFailureReason.UNSUPPORTED_LOCAL ->
                    com.epiccrown.directstream.api.R.string.ds_codec_unsupported_local
                CodecFailureReason.INCOMPATIBLE_PEER ->
                    com.epiccrown.directstream.api.R.string.ds_codec_incompatible_peer
                CodecFailureReason.PEER_UNAVAILABLE ->
                    com.epiccrown.directstream.api.R.string.ds_codec_peer_unavailable
                CodecFailureReason.BUSY -> com.epiccrown.directstream.api.R.string.ds_codec_busy
                CodecFailureReason.NO_ACTIVE_CALL ->
                    com.epiccrown.directstream.api.R.string.ds_codec_no_active_call
                CodecFailureReason.NEGOTIATION_FAILED ->
                    com.epiccrown.directstream.api.R.string.ds_codec_negotiation_failed
                CodecFailureReason.TIMEOUT ->
                    com.epiccrown.directstream.api.R.string.ds_codec_timeout
                CodecFailureReason.SESSION_ENDED ->
                    com.epiccrown.directstream.api.R.string.ds_codec_session_ended
            }
        Text(
            stringResource(failure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@ComponentPreviews
@Composable
internal fun CodecDiagnosticsPreview() = PreviewComponent {
    CodecDiagnostics(
        CallPreviewData.call.copy(
            codecs =
                CallCodecState(
                    active = CallCodecPreferences(video = CodecPolicy.Enforce(CodecId.VP9)),
                    selection = CodecSelection(CodecId.VP9, CodecId.OPUS),
                    phase = CodecPhase.ACTIVE,
                ),
        ),
    )
}
