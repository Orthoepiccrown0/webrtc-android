package com.epiccrown.directstream.showcase.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.showcase.R
import com.epiccrown.directstream.ui.presentation.Page

@Composable
internal fun CallEntryScreen(
    input: String,
    onInputChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onScan: () -> Unit = {},
    onBack: () -> Unit = {},
    inputError: String? = null,
    error: String? = null,
    permissionMessage: String? = null,
    busy: Boolean = false,
    requestEnabled: Boolean = input.isNotBlank() && inputError == null,
    cardModifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    scannerAvailable: Boolean = true,
    mode: com.epiccrown.directstream.api.CallMode = com.epiccrown.directstream.api.CallMode.Video,
    onModeChange: (com.epiccrown.directstream.api.CallMode) -> Unit = {},
    availableModes: Set<com.epiccrown.directstream.api.CallMode> =
        com.epiccrown.directstream.api.CallMode.entries.toSet(),
) {
    Page(stringResource(R.string.ds_ui_call_a_device), onBack) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.epiccrown.directstream.api.CallMode.entries.forEach { choice ->
                androidx.compose.material3.FilterChip(
                    selected = choice == mode,
                    onClick = { onModeChange(choice) },
                    enabled = !busy && choice in availableModes,
                    label = {
                        Text(
                            stringResource(
                                if (choice == com.epiccrown.directstream.api.CallMode.Audio)
                                    R.string.ds_ui_audio_call
                                else R.string.ds_ui_video_call,
                            ),
                        )
                    },
                )
            }
        }
        if (com.epiccrown.directstream.api.CallMode.Audio !in availableModes)
            Text(stringResource(R.string.ds_ui_existing_service_video_audio))
        DeviceCodeEntryCard(
            input,
            onInputChange,
            onSubmit,
            onScan,
            inputError,
            busy,
            requestEnabled,
            cardModifier,
            inputModifier,
            scannerAvailable,
        )
    }
}

@Composable
internal fun DeviceCodeEntryCard(
    input: String,
    onInputChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onScan: () -> Unit = {},
    inputError: String? = null,
    busy: Boolean = false,
    requestEnabled: Boolean = input.isNotBlank() && inputError == null,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    scannerAvailable: Boolean = true,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text(stringResource(R.string.ds_ui_device_code)) },
                placeholder = { Text(stringResource(R.string.ds_ui_example_code)) },
                singleLine = true,
                isError = inputError != null,
                supportingText = inputError?.let { message -> { Text(message) } },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = inputModifier.fillMaxWidth().testTag("device-code-input"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSubmit, enabled = requestEnabled) {
                    Text(stringResource(R.string.ds_ui_request_connection))
                }
                if (scannerAvailable)
                    OutlinedButton(onClick = onScan, enabled = !busy) {
                        Text(stringResource(R.string.ds_ui_scan_qr))
                    }
            }
        }
    }
}

@ScreenPreviews
@Composable
internal fun CallEntryPreview() = PreviewTheme { CallEntryScreen("DEF-567") }

@ScreenPreviews
@Composable
internal fun InvalidCallEntryPreview() = PreviewTheme {
    CallEntryScreen(
        "bad",
        inputError =
            stringResource(
                R.string.ds_ui_enter_a_valid_device_code_or_scan_a_direct_stream_pairing_qr,
            ),
    )
}

@ComponentPreviews
@Composable
internal fun DeviceCodeEntryCardPreview() = PreviewComponent { DeviceCodeEntryCard("DEF-567") }
