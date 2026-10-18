package com.epiccrown.directstream.ui.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.ui.R
import java.text.NumberFormat

/**
 * Presentation only: reads the existing stats snapshot without changing sampling or media state.
 */
@Composable
internal fun ExpandedCallDiagnostics(
    state: CallSnapshot,
    maxHeight: Dp = 320.dp,
    stale: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .97f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.testTag("expanded-call-debug"),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.ds_ui_call_diagnostics),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(
                    when {
                        state.stats.sampledAtMs == 0L -> R.string.ds_ui_debug_waiting
                        stale -> R.string.ds_ui_debug_stale
                        else -> R.string.ds_ui_debug_live
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DiagnosticSection(
                stringResource(R.string.ds_ui_debug_connection),
                initiallyExpanded = true,
            ) {
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_route),
                    state.stats.transport.name,
                )
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_duration),
                    stringResource(
                        R.string.ds_ui_call_duration,
                        state.stats.elapsedMs / 60_000,
                        state.stats.elapsedMs / 1000 % 60,
                    ),
                )
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_ice),
                    state.stats.iceState.ifBlank { "—" },
                )
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_peer),
                    state.stats.peerState.ifBlank { "—" },
                )
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_rtt),
                    metric(state.stats.rttMs, R.string.ds_ui_debug_ms),
                )
            }
            if (state.mode == CallMode.Video) {
                MediaDiagnosticSection(
                    stringResource(R.string.ds_ui_video_in),
                    state.stats.videoIn,
                    true,
                )
                MediaDiagnosticSection(
                    stringResource(R.string.ds_ui_video_out),
                    state.stats.videoOut,
                    true,
                )
            }
            MediaDiagnosticSection(
                stringResource(R.string.ds_ui_audio_in),
                state.stats.audioIn,
                false,
            )
            MediaDiagnosticSection(
                stringResource(R.string.ds_ui_audio_out),
                state.stats.audioOut,
                false,
            )
            DiagnosticSection(stringResource(R.string.ds_ui_debug_negotiation)) {
                CodecDiagnostics(state)
            }
            DiagnosticSection(stringResource(R.string.ds_ui_debug_device)) {
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_microphone),
                    stringResource(
                        when {
                            !state.media.microphoneAvailable -> R.string.ds_ui_mic_unavailable_state
                            state.media.muted -> R.string.ds_ui_mic_muted_state
                            else -> R.string.ds_ui_media_on
                        },
                    ),
                )
                DiagnosticRow(
                    stringResource(R.string.ds_ui_audio_output),
                    state.media.output?.label ?: "—",
                )
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_interruption),
                    booleanState(state.media.interrupted),
                )
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_system_mute),
                    booleanState(state.media.systemMuted),
                )
                DiagnosticRow(
                    stringResource(R.string.ds_ui_debug_remote_mute),
                    state.media.remoteMuted?.let { booleanState(it) } ?: "—",
                )
                if (state.mode == CallMode.Video) {
                    DiagnosticRow(
                        stringResource(R.string.ds_ui_debug_local_pause),
                        booleanState(state.media.localVideoPaused),
                    )
                    DiagnosticRow(
                        stringResource(R.string.ds_ui_debug_remote_pause),
                        booleanState(state.media.remoteVideoPaused),
                    )
                }
            }
        }
    }
}

@Composable
private fun booleanState(value: Boolean) =
    stringResource(if (value) R.string.ds_ui_debug_yes else R.string.ds_ui_debug_no)

private fun number(value: Double?, decimals: Int = 0): String =
    value
        ?.takeIf { it.isFinite() && it >= 0 }
        ?.let {
            NumberFormat.getNumberInstance().apply { maximumFractionDigits = decimals }.format(it)
        } ?: "—"

@Composable
private fun metric(value: Double?, unit: Int, decimals: Int = 0): String =
    if (value == null || !value.isFinite() || value < 0) "—"
    else stringResource(unit, number(value, decimals))

@Composable
internal fun DiagnosticSection(
    title: String,
    initiallyExpanded: Boolean = false,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val description =
        stringResource(
            if (expanded) R.string.ds_ui_debug_expanded else R.string.ds_ui_debug_collapsed,
        )
    Column {
        Surface(
            onClick = { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = description },
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    summary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
        }
        if (expanded)
            Column(
                Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
    }
}

@Composable
internal fun DiagnosticRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        val stacked = maxWidth < 180.dp || LocalDensity.current.fontScale >= 1.4f
        if (stacked)
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        else
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                )
            }
    }
}

@Composable
private fun MediaDiagnosticSection(title: String, media: MediaMetrics, video: Boolean) {
    DiagnosticSection(
        title,
        summary =
            stringResource(
                R.string.ds_ui_debug_stream_summary,
                media.codec ?: "—",
                metric(media.bitrateKbps, R.string.ds_ui_debug_kbps),
            ),
    ) {
        DiagnosticRow(stringResource(R.string.ds_ui_debug_codec), media.codec ?: "—")
        DiagnosticRow(
            stringResource(R.string.ds_ui_debug_bitrate),
            metric(media.bitrateKbps, R.string.ds_ui_debug_kbps),
        )
        if (video) {
            val resolution =
                if ((media.width ?: 0) > 0 && (media.height ?: 0) > 0)
                    stringResource(
                        R.string.ds_ui_debug_resolution_value,
                        media.width!!,
                        media.height!!,
                    )
                else "—"
            DiagnosticRow(stringResource(R.string.ds_ui_debug_resolution), resolution)
            DiagnosticRow(
                stringResource(R.string.ds_ui_debug_fps),
                metric(media.fps, R.string.ds_ui_debug_fps_value, 1),
            )
            DiagnosticRow(
                stringResource(R.string.ds_ui_debug_dropped),
                media.droppedFrames?.takeIf { it >= 0 }?.toString() ?: "—",
            )
        } else
            DiagnosticRow(stringResource(R.string.ds_ui_debug_level), number(media.audioLevel, 3))
        DiagnosticRow(
            stringResource(R.string.ds_ui_debug_loss),
            media.packetsLost?.takeIf { it >= 0 }?.toString() ?: "—",
        )
        DiagnosticRow(stringResource(R.string.ds_ui_debug_profile), media.codecProfile ?: "—")
        DiagnosticRow(
            stringResource(R.string.ds_ui_debug_implementation),
            media.implementation ?: "—",
        )
    }
}

@ComponentPreviews
@Composable
internal fun DiagnosticRowPreview() = PreviewComponent {
    DiagnosticRow("Encoder implementation", "c2.android.vp9.encoder")
}

@ComponentPreviews
@Composable
internal fun DiagnosticSectionPreview() = PreviewComponent {
    DiagnosticSection("Video received", true, "VP9 · 850 kbps") {
        DiagnosticRow("Resolution", "1280 × 720")
        DiagnosticRow("Frame rate", "30 fps")
    }
}

@ComponentPreviews
@Composable
internal fun StaleDiagnosticsPreview() = PreviewComponent {
    ExpandedCallDiagnostics(CallPreviewData.call, stale = true)
}
