package com.epiccrown.directstream.showcase.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.showcase.R

@Composable
internal fun CodecSettingsCard(
    state: CallCodecState,
    onChange: (CallCodecPreferences) -> Unit = {},
) {
    SettingsGroup(stringResource(R.string.ds_ui_media_codecs)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.ds_ui_codecs_next_call),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.ds_ui_codecs_peer_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.capabilities == null)
                Text(
                    stringResource(
                        if (state.capabilitiesFailure == null) R.string.ds_ui_codecs_loading
                        else R.string.ds_ui_codecs_discovery_failed,
                    ),
                )
            CodecPolicySelector(true, state.nextCall.video, state.capabilities) {
                onChange(state.nextCall.copy(video = it))
            }
            HorizontalDivider()
            CodecPolicySelector(false, state.nextCall.audio, state.capabilities) {
                onChange(state.nextCall.copy(audio = it))
            }
        }
    }
}

@Composable
private fun CodecPolicySelector(
    video: Boolean,
    policy: CodecPolicy,
    capabilities: CodecCapabilities?,
    onSelect: (CodecPolicy) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val media =
        stringResource(if (video) R.string.ds_ui_video_codec else R.string.ds_ui_audio_codec)
    val selected = policy.codecOrNull()
    val enforceDescription = stringResource(R.string.ds_ui_codec_enforce_for, media)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(media, style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { choosing = true },
            enabled = capabilities != null,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(if (video) "video-codec-selector" else "audio-codec-selector"),
        ) {
            Text(selected?.name ?: stringResource(R.string.ds_ui_codec_auto))
        }
        selected
            ?.let { capabilities?.find(it) }
            ?.let { Text(codecAvailability(it), style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ds_ui_codec_enforce),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.ds_ui_codec_enforce_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = policy is CodecPolicy.Enforce,
                enabled = selected != null && capabilities?.find(selected)?.bidirectional == true,
                onCheckedChange = { enabled ->
                    selected?.let {
                        onSelect(if (enabled) CodecPolicy.Enforce(it) else CodecPolicy.Prefer(it))
                    }
                },
                modifier =
                    Modifier.semantics { contentDescription = enforceDescription }
                        .testTag(if (video) "video-codec-enforce" else "audio-codec-enforce"),
            )
        }
    }
    if (choosing)
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(media) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    CodecChoiceRow(
                        stringResource(R.string.ds_ui_codec_auto),
                        stringResource(R.string.ds_ui_codec_auto_description),
                        selected == null,
                        true,
                    ) {
                        choosing = false
                        onSelect(CodecPolicy.Auto)
                    }
                    val pinned =
                        if (video) listOf(CodecId.VP8, CodecId.H264, CodecId.VP9, CodecId.AV1)
                        else listOf(CodecId.OPUS)
                    val ids =
                        (pinned +
                                capabilities
                                    ?.codecs
                                    .orEmpty()
                                    .map { it.codec }
                                    .filter {
                                        it.mimeType.startsWith(if (video) "video/" else "audio/")
                                    })
                            .distinct()
                    ids.forEach { id ->
                        val capability = capabilities?.find(id)
                        CodecChoiceRow(
                            id.name,
                            capability?.let { codecAvailability(it) }
                                ?: stringResource(R.string.ds_ui_codec_not_bundled),
                            selected == id,
                            capability?.bidirectional == true,
                        ) {
                            choosing = false
                            onSelect(
                                if (policy is CodecPolicy.Enforce) CodecPolicy.Enforce(id)
                                else CodecPolicy.Prefer(id),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) {
                    Text(stringResource(R.string.ds_ui_close))
                }
            },
        )
}

@Composable
private fun CodecChoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                title,
                color =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun codecAvailability(capability: CodecCapability): String {
    @Composable
    fun label(available: Boolean, implementation: CodecImplementation) =
        stringResource(
            when {
                !available -> R.string.ds_ui_codec_unavailable
                implementation == CodecImplementation.HARDWARE -> R.string.ds_ui_codec_hardware
                implementation == CodecImplementation.SOFTWARE -> R.string.ds_ui_codec_software
                else -> R.string.ds_ui_codec_implementation_unknown
            },
        )
    return stringResource(
        R.string.ds_ui_codec_availability,
        label(capability.canEncode, capability.encoderImplementation),
        label(capability.canDecode, capability.decoderImplementation),
    )
}

@ScreenPreviews
@Composable
internal fun CodecSettingsPreview() = PreviewTheme { CodecSettingsCard(codecPreviewState()) }

@ScreenPreviews
@Composable
internal fun UnsupportedCodecSettingsPreview() = PreviewTheme {
    CodecSettingsCard(codecPreviewState().copy(capabilities = CodecCapabilities()))
}

@ComponentPreviews
@Composable
internal fun CodecChoicePreview() = PreviewComponent {
    CodecChoiceRow("AV1", stringResource(R.string.ds_ui_codec_not_bundled), false, false) {}
}

internal fun codecPreviewState(): CallCodecState =
    CallCodecState(
        capabilities =
            CodecCapabilities(
                listOf(CodecId.VP8, CodecId.VP9, CodecId.AV1, CodecId.OPUS).map {
                    val format =
                        CodecFormat(
                            if (it == CodecId.OPUS) 48000 else 90000,
                            if (it == CodecId.OPUS) 2 else null,
                        )
                    CodecCapability(
                        it,
                        listOf(format),
                        if (it == CodecId.AV1) emptyList() else listOf(format),
                        CodecImplementation.SOFTWARE,
                        CodecImplementation.SOFTWARE,
                    )
                },
            ),
    )

@ComponentPreviews
@Composable
internal fun CodecPolicySelectorPreview() = PreviewComponent {
    CodecPolicySelector(true, CodecPolicy.Auto, codecPreviewState().capabilities) {}
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Large codec settings",
    widthDp = 320,
    heightDp = 800,
    fontScale = 1.6f,
)
@Composable
internal fun LargeCodecSettingsPreview() = PreviewTheme { CodecSettingsCard(codecPreviewState()) }
