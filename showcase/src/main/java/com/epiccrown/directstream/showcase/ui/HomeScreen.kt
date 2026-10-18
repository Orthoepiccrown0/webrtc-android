package com.epiccrown.directstream.showcase.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epiccrown.directstream.api.CallDirection
import com.epiccrown.directstream.api.CallOutcome
import com.epiccrown.directstream.api.Device
import com.epiccrown.directstream.api.DeviceCode
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.showcase.R
import com.epiccrown.directstream.ui.presentation.CameraGlyph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HomeScreen(
    state: ShowcaseState,
    message: String?,
    notificationsAllowed: Boolean = true,
    highPriorityIncomingChannel: Boolean = true,
    fullScreenAllowed: Boolean = true,
    onCall: () -> Unit = {},
    onRecentCall: (RecentDevice) -> Unit = {},
    onUpdate: (RecentDevice) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onSettings: () -> Unit = {},
    onGallery: () -> Unit = {},
    onReturn: () -> Unit = {},
    onEnd: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val active =
        state.session is StreamSessionState.Connected ||
            state.session is StreamSessionState.Negotiating ||
            state.session is StreamSessionState.Reconnecting
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BrandHeader()
            OutlinedButton(
                onClick = onGallery,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.ds_ui_screen_gallery))
            }
            ReadinessCard(state, notificationsAllowed)
            if (!notificationsAllowed || !highPriorityIncomingChannel || !fullScreenAllowed) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.ds_ui_incoming_alert_setup),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                when {
                                    !notificationsAllowed ->
                                        R.string.ds_ui_enable_notifications_for_background_alerts
                                    !highPriorityIncomingChannel ->
                                        R.string.ds_ui_incoming_channel_silent
                                    else -> R.string.ds_ui_enable_full_screen_for_wake
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = onSettings,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.ds_ui_configure_call_alerts))
                        }
                    }
                }
            }
            state.error?.let { Text(it, color = colors.error) }
            state.outcome?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            message?.let { Text(it, color = colors.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.ready)
                Button(onClick = onRetry, enabled = !state.busy) {
                    Text(stringResource(R.string.ds_ui_retry_setup))
                }
            if (active) OngoingCallCard(onReturn, onEnd)
            state.device?.let {
                DeviceCodeCard(
                    it,
                    state.busy,
                    onCopy,
                    onShare,
                    onRefresh,
                    canRegenerate = state.ready && state.online,
                )
            }
            Button(
                onClick = onCall,
                enabled = state.ready && state.online && !state.busy && !active,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.ds_ui_call_a_device))
            }
            RecentCallsCard(
                state.recents,
                canCall = state.ready && !active && !state.busy && state.online,
                onRecentCall,
                onUpdate,
                onRemove,
            )
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ds_ui_settings))
            }
        }
    }
}

@Composable
internal fun BrandHeader() {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(color = colors.primaryContainer, shape = MaterialTheme.shapes.large) {
            CameraGlyph(Modifier.padding(12.dp).size(40.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.ds_ui_direct_stream),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                stringResource(R.string.ds_ui_private_video_calls_by_device_code),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ReadinessCard(state: ShowcaseState, notificationsAllowed: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val active =
        state.session is StreamSessionState.Connected ||
            state.session is StreamSessionState.Negotiating ||
            state.session is StreamSessionState.Reconnecting
    Card(colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (active) "↔" else if (!state.online) "!" else "●",
                color = colors.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge,
            )
            Column {
                Text(
                    when {
                        active -> stringResource(R.string.ds_ui_call_in_progress)
                        !state.online -> stringResource(R.string.ds_ui_network_unavailable)
                        !state.ready -> stringResource(R.string.ds_ui_connecting)
                        else -> stringResource(R.string.ds_ui_ready_to_receive_requests)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (!notificationsAllowed)
                        stringResource(R.string.ds_ui_enable_notifications_for_background_alerts)
                    else stringResource(R.string.ds_ui_every_call_requires_your_acceptance),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun OngoingCallCard(onReturn: () -> Unit = {}, onEnd: () -> Unit = {}) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ds_ui_your_call_continues_with_audio_video_pauses_away))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReturn) { Text(stringResource(R.string.ds_ui_return_to_call)) }
                OutlinedButton(onClick = onEnd) { Text(stringResource(R.string.ds_ui_end_call)) }
            }
        }
    }
}

@Composable
internal fun DeviceCodeCard(
    device: Device,
    busy: Boolean = false,
    onCopy: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    canRegenerate: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            device.participantName?.let {
                Text(
                    stringResource(R.string.ds_ui_participant_name, it),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                stringResource(R.string.ds_ui_your_device_code),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stack = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.15f

                if (stack)
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        DeviceCodeControls(device.code, onCopy, onShare, Modifier.fillMaxWidth())
                        QrCodeCard(device.code, 144.dp)
                    }
                else
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DeviceCodeControls(device.code, onCopy, onShare, Modifier.weight(1f))
                        QrCodeCard(device.code, 104.dp)
                    }
            }
            RegenerateCodeCta(enabled = canRegenerate && !busy, onRegenerate = onRefresh)
            HorizontalDivider()
            Text(
                stringResource(R.string.ds_ui_share_your_code_or_qr_to_receive_a_request),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DeviceCodeControls(
    code: String,
    onCopy: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val codeDescription =
            stringResource(R.string.ds_ui_device_code_description, DeviceCode.format(code))
        Text(
            DeviceCode.format(code),
            style =
                MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 34.sp,
                    lineHeight = 44.sp,
                ),
            modifier = Modifier.semantics { contentDescription = codeDescription },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onCopy(code) }) { Text(stringResource(R.string.ds_ui_copy)) }
            OutlinedButton(onClick = { onShare(code) }) {
                Text(stringResource(R.string.ds_ui_share))
            }
        }
    }
}

@Composable
internal fun RegenerateCodeCta(enabled: Boolean = true, onRegenerate: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onRegenerate,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ds_ui_regenerate_code))
        }
        Text(
            stringResource(R.string.ds_ui_regenerate_code_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun QrCodeCard(code: String, size: androidx.compose.ui.unit.Dp = 144.dp) {
    Surface(color = Color.White, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PairingQr(code, Modifier.size(size))
            Text(
                stringResource(R.string.ds_ui_scan_to_call),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF172B2B),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
internal fun RecentCallsCard(
    rows: List<RecentDevice>,
    canCall: Boolean = true,
    onRecentCall: (RecentDevice) -> Unit = {},
    onUpdate: (RecentDevice) -> Unit = {},
    onRemove: (String) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.ds_ui_recent_calls),
                style = MaterialTheme.typography.titleLarge,
            )
            if (rows.isEmpty())
                Text(
                    stringResource(
                        R.string.ds_ui_recent_devices_names,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            rows.forEach { row ->
                RecentDeviceRow(row, canCall, onRecentCall, onUpdate, onRemove)
            }
        }
    }
}

@Composable
internal fun RecentDeviceRow(
    row: RecentDevice,
    canCall: Boolean = true,
    onRecentCall: (RecentDevice) -> Unit = {},
    onUpdate: (RecentDevice) -> Unit = {},
    onRemove: (String) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var menu by remember(row.id) { mutableStateOf(false) }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(recentName(row), style = MaterialTheme.typography.titleMedium)
                    if (
                        com.epiccrown.directstream.showcase.BuildConfig.DEBUG &&
                            row.peerName != null &&
                            row.code != null
                    )
                        Text(
                            stringResource(
                                R.string.ds_ui_debug_device_code,
                                DeviceCode.format(requireNotNull(row.code)),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription =
                                stringResource(R.string.ds_ui_device_options, recentName(row)),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ds_ui_update_code)) },
                            onClick = {
                                menu = false
                                onUpdate(row)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ds_ui_remove)) },
                            onClick = {
                                menu = false
                                onRemove(row.id)
                            },
                        )
                    }
                }
            }
            Text(
                stringResource(
                    R.string.ds_ui_recent_summary,
                    stringResource(
                        if (row.direction == CallDirection.INCOMING)
                            R.string.ds_ui_incoming_direction
                        else R.string.ds_ui_outgoing_direction,
                    ),
                    callOutcomeLabel(row.outcome),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                DateTimeFormatter.ofPattern("dd MMM · HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(row.at)),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onRecentCall(row) }, enabled = canCall) {
                    Text(
                        if (row.code == null) stringResource(R.string.ds_ui_update_code)
                        else stringResource(R.string.ds_ui_call),
                    )
                }
                if (row.code != null && row.outcome == CallOutcome.FAILED)
                    TextButton(onClick = { onUpdate(row) }) {
                        Text(stringResource(R.string.ds_ui_update_code))
                    }
            }
        }
    }
}

@ScreenPreviews
@Composable
internal fun HomePreview() = PreviewTheme { HomeScreen(ShowcaseData.home, null) }

@ScreenPreviews
@Composable
internal fun EmptyHomePreview() = PreviewTheme {
    HomeScreen(ShowcaseData.home.copy(recents = emptyList()), null)
}

@ScreenPreviews
@Composable
internal fun OfflineHomePreview() = PreviewTheme {
    HomeScreen(ShowcaseData.home.copy(online = false), null, notificationsAllowed = false)
}

@ScreenPreviews
@Composable
internal fun OngoingCallHomePreview() = PreviewTheme { HomeScreen(ShowcaseData.call, null) }

@androidx.compose.ui.tooling.preview.Preview(
    name = "Narrow / large text",
    widthDp = 320,
    heightDp = 850,
    fontScale = 1.5f,
)
@Composable
internal fun NarrowHomePreview() = PreviewTheme { HomeScreen(ShowcaseData.home, null) }

@ComponentPreviews
@Composable
internal fun BrandHeaderPreview() = PreviewComponent { BrandHeader() }

@ComponentPreviews
@Composable
internal fun ReadinessCardPreview() = PreviewComponent { ReadinessCard(ShowcaseData.home) }

@ComponentPreviews
@Composable
internal fun OngoingCallCardPreview() = PreviewComponent { OngoingCallCard() }

@ComponentPreviews
@Composable
internal fun DeviceCodeCardPreview() = PreviewComponent { DeviceCodeCard(Device("ABC234")) }

@ComponentPreviews
@Composable
internal fun DeviceCodeControlsPreview() = PreviewComponent { DeviceCodeControls("ABC234") }

@ComponentPreviews
@Composable
internal fun QrCodeCardPreview() = PreviewComponent { QrCodeCard("ABC234") }

@ComponentPreviews
@Composable
internal fun RecentCallsCardPreview() = PreviewComponent {
    RecentCallsCard(ShowcaseData.home.recents)
}

@ComponentPreviews
@Composable
internal fun EmptyRecentCallsCardPreview() = PreviewComponent { RecentCallsCard(emptyList()) }

@ComponentPreviews
@Composable
internal fun RecentDeviceRowPreview() = PreviewComponent { RecentDeviceRow(ShowcaseData.recent) }

@ComponentPreviews
@Composable
internal fun FailedRecentDeviceRowPreview() = PreviewComponent {
    RecentDeviceRow(ShowcaseData.recent.copy(outcome = CallOutcome.FAILED))
}

@Composable
private fun callOutcomeLabel(outcome: CallOutcome): String =
    stringResource(
        when (outcome) {
            CallOutcome.INCOMING -> R.string.ds_ui_outcome_incoming
            CallOutcome.CALLING -> R.string.ds_ui_outcome_calling
            CallOutcome.ACCEPTED -> R.string.ds_ui_outcome_accepted
            CallOutcome.CONNECTED -> R.string.ds_ui_outcome_connected
            CallOutcome.COMPLETED -> R.string.ds_ui_outcome_completed
            CallOutcome.DECLINED -> R.string.ds_ui_outcome_declined
            CallOutcome.CANCELLED -> R.string.ds_ui_outcome_cancelled
            CallOutcome.MISSED -> R.string.ds_ui_outcome_missed
            CallOutcome.FAILED -> R.string.ds_ui_outcome_failed
        },
    )

@Composable
internal fun recentName(row: RecentDevice): String =
    row.peerName
        ?: row.code?.let(DeviceCode::format)
        ?: stringResource(R.string.ds_ui_unknown_device)

@ComponentPreviews
@Composable
internal fun RegenerateCodeCtaPreview() = PreviewComponent { RegenerateCodeCta() }

@androidx.compose.ui.tooling.preview.Preview(
    name = "Call alert access missing",
    showBackground = true,
)
@androidx.compose.ui.tooling.preview.Preview(
    name = "Call alert access missing dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun CallAlertAccessHomePreview() = PreviewTheme {
    HomeScreen(ShowcaseData.home, null, notificationsAllowed = false, fullScreenAllowed = false)
}
