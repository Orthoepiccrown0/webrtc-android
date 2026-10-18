package com.epiccrown.directstream.showcase.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.DIRECT_STREAM_VERSION
import com.epiccrown.directstream.InitializationStatus
import com.epiccrown.directstream.SetupStatus
import com.epiccrown.directstream.api.ConfigurationCheck
import com.epiccrown.directstream.showcase.R
import com.epiccrown.directstream.ui.presentation.CameraGlyph
import com.epiccrown.directstream.ui.presentation.Page

data class SamplePermissions(
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val notifications: Boolean = false,
    val fullScreen: Boolean = false,
    val fullScreenSupported: Boolean = false,
    val highPriorityIncomingChannel: Boolean = true,
)

@Composable
internal fun SettingsScreen(
    hasRecents: Boolean,
    setup: SetupStatus? = null,
    sampleVersion: String = "—",
    permissions: SamplePermissions = SamplePermissions(),
    codecState: com.epiccrown.directstream.api.CallCodecState =
        com.epiccrown.directstream.api.CallCodecState(),
    onCodecPreferences: (com.epiccrown.directstream.api.CallCodecPreferences) -> Unit = {},
    onEnableNotifications: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
    onAppSettings: () -> Unit = {},
    onFullScreenSettings: () -> Unit = {},
    onClearRecents: () -> Unit = {},
    onIntegration: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    var clear by remember { mutableStateOf(false) }
    Page(stringResource(R.string.ds_ui_settings), onBack) {
        SdkOverviewCard(sampleVersion)
        SettingsGroup(stringResource(R.string.ds_ui_build_with_direct_stream)) {
            SettingsActionRow(
                stringResource(R.string.ds_ui_integration_guide),
                stringResource(
                    R.string.ds_ui_add_the_sdk_initialize_your_provider_and_display_a_call_flow,
                ),
                stringResource(R.string.ds_ui_view_guide),
                onIntegration,
            )
        }
        SetupStatusCard(setup)
        CodecSettingsCard(codecState, onCodecPreferences)
        SettingsGroup(stringResource(R.string.ds_ui_permissions_call_alerts)) {
            SettingsActionRow(
                stringResource(R.string.ds_ui_camera),
                if (permissions.camera) stringResource(R.string.ds_ui_allowed)
                else stringResource(R.string.ds_ui_required_before_starting_video),
                stringResource(R.string.ds_ui_app_settings),
                onAppSettings,
                permissions.camera,
            )
            HorizontalDivider()
            SettingsActionRow(
                stringResource(R.string.ds_ui_microphone),
                if (permissions.microphone) stringResource(R.string.ds_ui_allowed)
                else
                    stringResource(
                        R.string.ds_ui_not_allowed_incoming_audio_still_works_changes_apply,
                    ),
                stringResource(R.string.ds_ui_app_settings),
                onAppSettings,
                permissions.microphone,
            )
            HorizontalDivider()
            SettingsActionRow(
                stringResource(R.string.ds_ui_call_notifications),
                if (permissions.notifications && !permissions.highPriorityIncomingChannel)
                    stringResource(R.string.ds_ui_incoming_channel_silent)
                else if (permissions.notifications)
                    stringResource(R.string.ds_ui_enabled_delivery_is_best_effort)
                else
                    stringResource(
                        R.string.ds_ui_disabled_or_blocked_open_the_sample_to_check_requests,
                    ),
                if (permissions.notifications) stringResource(R.string.ds_ui_notification_settings)
                else stringResource(R.string.ds_ui_enable_notifications),
                if (permissions.notifications) onNotificationSettings else onEnableNotifications,
                permissions.notifications,
            )
            if (!permissions.notifications)
                TextButton(onClick = onNotificationSettings) {
                    Text(stringResource(R.string.ds_ui_open_notification_settings))
                }
            if (permissions.fullScreenSupported) {
                HorizontalDivider()
                SettingsActionRow(
                    stringResource(R.string.ds_ui_full_screen_call_alerts),
                    if (permissions.fullScreen)
                        stringResource(
                            R.string.ds_ui_allowed_android_controls_lock_screen_presentation,
                        )
                    else
                        stringResource(R.string.ds_ui_not_allowed_incoming_call_helper_is_optional),
                    stringResource(R.string.ds_ui_manage_alerts),
                    onFullScreenSettings,
                    permissions.fullScreen,
                )
            }
            Text(
                stringResource(R.string.ds_ui_all_devices_can_receive_requests_you_choose_whether),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        SettingsGroup(stringResource(R.string.ds_ui_sample_data)) {
            SettingsActionRow(
                stringResource(R.string.ds_ui_recent_calls),
                stringResource(
                    R.string.ds_ui_stored_privately_on_this_device_clearing_history_does,
                ),
                stringResource(R.string.ds_ui_clear_recents),
                { clear = true },
                enabled = hasRecents,
            )
        }
        SdkLimitationsCard()
    }
    if (clear)
        ClearRecentsDialog(
            onDismiss = { clear = false },
            onConfirm = {
                clear = false
                onClearRecents()
            },
        )
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedCard(Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable
internal fun SdkOverviewCard(sampleVersion: String) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CameraGlyph(Modifier.size(32.dp))
            Text(
                stringResource(R.string.ds_ui_direct_stream_sdk_sample),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.ds_ui_two_way_video_and_audio_with_explicit_acceptance))
            Text(
                stringResource(R.string.ds_ui_sdk_versions, DIRECT_STREAM_VERSION, sampleVersion),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit = {},
    positive: Boolean? = null,
    enabled: Boolean = true,
) {
    val actionDescription =
        stringResource(R.string.ds_ui_settings_action_description, action, title)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (positive != null)
                Icon(
                    if (positive) Icons.Default.Check else Icons.Default.Info,
                    contentDescription = null,
                    tint =
                        if (positive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = actionDescription },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(action)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
internal fun SetupStatusCard(setup: SetupStatus?) {
    SettingsGroup(stringResource(R.string.ds_ui_setup_status)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                setup?.provider ?: stringResource(R.string.ds_ui_provider_not_initialized),
                style = MaterialTheme.typography.titleSmall,
            )
            val check = setup?.configuration
            Text(
                when {
                    check == null -> stringResource(R.string.ds_ui_configuration_not_checked)
                    check.valid -> stringResource(R.string.ds_ui_configuration_valid_locally)
                    else -> stringResource(R.string.ds_ui_configuration_needs_attention)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (check != null && !check.valid) {
                if (check.missing.isNotEmpty())
                    Text(
                        stringResource(
                            R.string.ds_ui_configuration_missing,
                            check.missing.joinToString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (check.invalid.isNotEmpty())
                    Text(
                        stringResource(
                            R.string.ds_ui_configuration_invalid,
                            check.invalid.joinToString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            Text(
                stringResource(
                    R.string.ds_ui_services_status,
                    when (setup?.initialization) {
                        InitializationStatus.READY ->
                            stringResource(R.string.ds_ui_registered_successfully)
                        InitializationStatus.INITIALIZING ->
                            stringResource(R.string.ds_ui_initializing)
                        InitializationStatus.FAILED ->
                            stringResource(R.string.ds_ui_initialization_failed_retry_from_home)
                        InitializationStatus.DISPOSED ->
                            stringResource(R.string.ds_ui_client_disposed)
                        else -> stringResource(R.string.ds_ui_not_initialized)
                    },
                ),
            )
            Text(
                stringResource(R.string.ds_ui_configuration_is_supplied_by_the_host_app_local),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SdkLimitationsCard() {
    SettingsGroup(stringResource(R.string.ds_ui_about_this_sample)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.ds_ui_android_11_api_30_and_newer),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    R.string.ds_ui_default_provider_firebase_spark_and_cloudflare_workers_free,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.ds_ui_turn_is_disabled_by_default_restrictive_networks_may),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.ds_ui_names_do_not_verify_identity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun IntegrationGuideScreen(onBack: () -> Unit = {}, onCopy: (String) -> Unit = {}) {
    Page(stringResource(R.string.ds_ui_integration_guide), onBack) {
        Text(
            stringResource(R.string.ds_ui_install_guide_intro_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.ds_ui_install_guide_intro_body))
        SettingsGroup(stringResource(R.string.ds_ui_install_ownership_title)) {
            Text(stringResource(R.string.ds_ui_install_ownership_body), Modifier.padding(16.dp))
        }
        IntegrationSnippet(
            stringResource(R.string.ds_ui_install_dependencies_title),
            stringResource(R.string.ds_ui_install_dependencies_body),
            stringResource(R.string.ds_ui_install_dependencies_code),
            onCopy,
        )
        IntegrationSnippet(
            stringResource(R.string.ds_ui_install_initialize_title),
            stringResource(R.string.ds_ui_install_initialize_body),
            stringResource(R.string.ds_ui_install_initialize_code),
            onCopy,
        )
        IntegrationSnippet(
            stringResource(R.string.ds_ui_install_register_title),
            stringResource(R.string.ds_ui_install_register_body),
            stringResource(R.string.ds_ui_install_register_code),
            onCopy,
        )
        IntegrationSnippet(
            stringResource(R.string.ds_ui_install_mapping_title),
            stringResource(R.string.ds_ui_install_mapping_body),
            stringResource(R.string.ds_ui_install_mapping_code),
            onCopy,
        )
        IntegrationSnippet(
            stringResource(R.string.ds_ui_install_request_title),
            stringResource(R.string.ds_ui_install_request_body),
            stringResource(R.string.ds_ui_install_request_code),
            onCopy,
        )
        IntegrationSnippet(
            stringResource(R.string.ds_ui_install_call_ui_title),
            stringResource(R.string.ds_ui_install_call_ui_body),
            stringResource(R.string.ds_ui_install_call_ui_code),
            onCopy,
        )
        IntegrationSnippet(
            stringResource(R.string.ds_ui_install_lifecycle_title),
            stringResource(R.string.ds_ui_install_lifecycle_body),
            stringResource(R.string.ds_ui_install_lifecycle_code),
            onCopy,
        )
        IntegrationSnippet(
            stringResource(R.string.ds_ui_install_regenerate_title),
            stringResource(R.string.ds_ui_install_regenerate_body),
            stringResource(R.string.ds_ui_install_regenerate_code),
            onCopy,
        )
        SettingsGroup(stringResource(R.string.ds_ui_install_showcase_title)) {
            Text(stringResource(R.string.ds_ui_install_showcase_body), Modifier.padding(16.dp))
        }
        SettingsGroup(stringResource(R.string.ds_ui_install_backend_title)) {
            Text(stringResource(R.string.ds_ui_install_backend_body), Modifier.padding(16.dp))
        }
    }
}

@Composable
internal fun IntegrationSnippet(
    title: String,
    description: String,
    code: String,
    onCopy: (String) -> Unit = {},
) {
    SettingsGroup(title) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            TextButton(onClick = { onCopy(code) }) {
                Text(stringResource(R.string.ds_ui_copy_snippet))
            }
        }
    }
}

private val sampleSetup =
    SetupStatus("Firebase + Cloudflare Worker", ConfigurationCheck(), InitializationStatus.READY)

@ScreenPreviews
@Composable
internal fun SettingsPreview() = PreviewTheme {
    SettingsScreen(true, sampleSetup, "1.0", SamplePermissions(true, true, true, true, true))
}

@ScreenPreviews
@Composable
internal fun MissingSetupSettingsPreview() = PreviewTheme {
    SettingsScreen(
        false,
        sampleSetup.copy(
            configuration = ConfigurationCheck(listOf("WORKER_URL")),
            initialization = InitializationStatus.FAILED,
        ),
    )
}

@ScreenPreviews
@Composable
internal fun DeniedPermissionsSettingsPreview() = PreviewTheme {
    SettingsScreen(true, sampleSetup, permissions = SamplePermissions(fullScreenSupported = true))
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Large settings",
    widthDp = 320,
    heightDp = 700,
    fontScale = 1.6f,
)
@Composable
internal fun LargeSettingsPreview() = PreviewTheme {
    SettingsScreen(true, sampleSetup, permissions = SamplePermissions(fullScreenSupported = true))
}

@ScreenPreviews
@Composable
internal fun IntegrationGuidePreview() = PreviewTheme { IntegrationGuideScreen() }

@ComponentPreviews
@Composable
internal fun SettingsGroupPreview() = PreviewComponent {
    SettingsGroup(stringResource(R.string.ds_ui_sample_data)) {
        Text("Private device history", Modifier.padding(16.dp))
    }
}

@ComponentPreviews
@Composable
internal fun SdkOverviewPreview() = PreviewComponent { SdkOverviewCard("1.0") }

@ComponentPreviews
@Composable
internal fun SetupStatusPreview() = PreviewComponent { SetupStatusCard(sampleSetup) }

@ComponentPreviews
@Composable
internal fun SettingsActionRowPreview() = PreviewComponent {
    SettingsActionRow(
        stringResource(R.string.ds_ui_camera),
        "Required for video",
        stringResource(R.string.ds_ui_app_settings),
        positive = false,
    )
}

@ComponentPreviews
@Composable
internal fun SdkLimitationsPreview() = PreviewComponent { SdkLimitationsCard() }

@ComponentPreviews
@Composable
internal fun IntegrationSnippetPreview() = PreviewComponent {
    IntegrationSnippet(
        "Initialize",
        "Use application context",
        "DirectStreamClient.initialize(this, configuration)",
    )
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Large installation guide",
    widthDp = 320,
    heightDp = 850,
    fontScale = 1.6f,
)
@Composable
internal fun LargeIntegrationGuidePreview() = PreviewTheme { IntegrationGuideScreen() }

@ScreenPreviews
@Composable
internal fun SilentIncomingChannelSettingsPreview() = PreviewTheme {
    SettingsScreen(
        false,
        sampleSetup,
        permissions = SamplePermissions(notifications = true, highPriorityIncomingChannel = false),
    )
}
