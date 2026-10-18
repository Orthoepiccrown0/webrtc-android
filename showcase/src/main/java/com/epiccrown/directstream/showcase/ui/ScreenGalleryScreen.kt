package com.epiccrown.directstream.showcase.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.showcase.R
import com.epiccrown.directstream.ui.presentation.Page
import kotlinx.serialization.Serializable

/** Stable, saved gallery destinations. These never represent an SDK session. */
@Serializable
enum class ShowcaseScreen(@get:StringRes val title: Int, @get:StringRes val description: Int) {
    HOME(R.string.ds_ui_gallery_home, R.string.ds_ui_gallery_home_description),
    CALL_ENTRY(R.string.ds_ui_call_a_device, R.string.ds_ui_gallery_entry_description),
    WAITING(R.string.ds_ui_gallery_waiting, R.string.ds_ui_gallery_waiting_description),
    INCOMING(R.string.ds_ui_gallery_incoming, R.string.ds_ui_gallery_incoming_description),
    IN_CALL(R.string.ds_ui_gallery_call, R.string.ds_ui_gallery_call_description),
    RECONNECTING(
        R.string.ds_ui_gallery_reconnecting,
        R.string.ds_ui_gallery_reconnecting_description,
    ),
    PAUSED(R.string.ds_ui_gallery_paused, R.string.ds_ui_gallery_paused_description),
    MICROPHONE_DENIED(
        R.string.ds_ui_gallery_microphone,
        R.string.ds_ui_gallery_microphone_description,
    ),
    SETTINGS(R.string.ds_ui_settings, R.string.ds_ui_gallery_settings_description),
    MISSING_SETUP(R.string.ds_ui_gallery_missing_setup, R.string.ds_ui_gallery_missing_description),
    INTEGRATION(R.string.ds_ui_integration_guide, R.string.ds_ui_gallery_integration_description),
    @kotlinx.serialization.SerialName("ALIAS")
    RETIRED_ALIAS(R.string.ds_ui_screen_gallery, R.string.ds_ui_gallery_explanation),
}

/** Sample-only catalog; available even when cloud configuration or permissions are missing. */
@Composable
internal fun ScreenGalleryScreen(onSelect: (ShowcaseScreen) -> Unit, onBack: () -> Unit) {
    Page(stringResource(R.string.ds_ui_screen_gallery), onBack) {
        Text(
            stringResource(R.string.ds_ui_gallery_explanation),
            style = MaterialTheme.typography.bodyLarge,
        )
        ShowcaseScreen.entries
            .filterNot { it == ShowcaseScreen.RETIRED_ALIAS }
            .forEach { screen ->
                Card(
                    onClick = { onSelect(screen) },
                    modifier = Modifier.fillMaxWidth().testTag("gallery-${screen.name}"),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            stringResource(screen.title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(screen.description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
    }
}

@ScreenPreviews
@Composable
internal fun ScreenGalleryPreview() = PreviewTheme { ScreenGalleryScreen({}, {}) }
