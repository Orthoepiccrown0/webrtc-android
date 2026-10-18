package com.epiccrown.directstream.ui.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.api.CallSnapshot
import com.epiccrown.directstream.api.DeviceCode
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.ui.R
import kotlinx.coroutines.delay

@Composable
internal fun RequestCard(title: String, request: PairingRequest) {
    var remaining by
        remember(request.requestId) {
            mutableLongStateOf(
                ((request.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0),
            )
        }
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    LaunchedEffect(request.expiresAt, inspection) {
        if (!inspection)
            while (true) {
                remaining =
                    ((request.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                delay(1000)
            }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(request.verificationValue, style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.ds_ui_request_countdown, remaining / 60, remaining % 60))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ds_ui_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun PeerName(state: CallSnapshot, request: PairingRequest) {
    PeerLabel(
        state.peerDisplayName ?: request.peerName,
        request.peerCode,
        state.showDebugIdentifiers,
    )
}

@Composable
fun CameraGlyph(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    val description = stringResource(R.string.ds_ui_video_calls)
    Canvas(modifier.semantics { contentDescription = description }) {
        drawRoundRect(
            color,
            Offset(size.width * .12f, size.height * .25f),
            Size(size.width * .48f, size.height * .5f),
            CornerRadius(size.width * .08f),
        )
        drawPath(
            Path().apply {
                moveTo(size.width * .64f, size.height * .36f)
                lineTo(size.width * .87f, size.height * .22f)
                lineTo(size.width * .87f, size.height * .78f)
                lineTo(size.width * .64f, size.height * .64f)
                close()
            },
            color,
        )
    }
}

@ComponentPreviews
@Composable
internal fun RequestCardPreview() = PreviewComponent {
    RequestCard("Compare the verification value", CallPreviewData.request)
}

@ComponentPreviews
@Composable
internal fun PeerNamePreview() = PreviewComponent {
    PeerName(CallPreviewData.home, CallPreviewData.request)
}

@ComponentPreviews
@Composable
internal fun CameraGlyphPreview() = PreviewComponent { CameraGlyph(Modifier.size(48.dp)) }

@ScreenPreviews
@Composable
internal fun PagePreview() = PreviewTheme { Page("Call", {}) { Text("Call setup") } }

@Composable
internal fun PeerLabel(
    name: String?,
    code: String?,
    debug: Boolean,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            name ?: code?.let(DeviceCode::format) ?: stringResource(R.string.ds_ui_unknown_device),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (debug && name != null && code != null)
            Text(
                stringResource(R.string.ds_ui_debug_device_code, DeviceCode.format(code)),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
    }
}

@ComponentPreviews
@Composable
internal fun NamedPeerPreview() = PreviewComponent { PeerLabel("River Otter", "ABC234", false) }

@ComponentPreviews
@Composable
internal fun DebugPeerPreview() = PreviewComponent { PeerLabel("River Otter", "ABC234", true) }
