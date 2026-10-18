package com.epiccrown.directstream.showcase.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.api.DeviceCode
import com.epiccrown.directstream.showcase.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
internal fun PairingQr(code: String, modifier: Modifier = Modifier.size(160.dp)) {
    val bitmap =
        remember(code) {
            val matrix =
                QRCodeWriter().encode("webrtcstream://pair/$code", BarcodeFormat.QR_CODE, 384, 384)
            Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
                .apply {
                    val pixels =
                        IntArray(384 * 384) { i ->
                            if (matrix[i % 384, i / 384]) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE
                        }
                    setPixels(pixels, 0, 384, 0, 0, 384, 384)
                }
                .asImageBitmap()
        }
    Image(
        bitmap,
        contentDescription = stringResource(R.string.ds_ui_qr_description, DeviceCode.format(code)),
        modifier = modifier,
    )
}

@Composable
internal fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_ui_cancel)) }
        },
    )
}

@Composable
internal fun ResetCodeDialog(onDismiss: () -> Unit = {}, onConfirm: () -> Unit = {}) =
    ConfirmationDialog(
        stringResource(R.string.ds_ui_regenerate_device_code_title),
        stringResource(R.string.ds_ui_regenerate_code_confirmation),
        stringResource(R.string.ds_ui_regenerate_code),
        onDismiss,
        onConfirm,
    )

@Composable
internal fun ClearRecentsDialog(onDismiss: () -> Unit = {}, onConfirm: () -> Unit = {}) =
    ConfirmationDialog(
        stringResource(R.string.ds_ui_clear_recent_devices),
        stringResource(R.string.ds_ui_clear_recent_history_description),
        stringResource(R.string.ds_ui_clear),
        onDismiss,
        onConfirm,
    )

@Composable
internal fun MicrophoneDialog(onDismiss: () -> Unit = {}, onContinue: () -> Unit = {}) =
    ConfirmationDialog(
        stringResource(R.string.ds_ui_microphone_unavailable),
        stringResource(R.string.ds_ui_continue_without_sending_audio_you_can_still_hear),
        stringResource(R.string.ds_ui_continue_without_microphone),
        onDismiss,
        onContinue,
    )

@ComponentPreviews
@Composable
internal fun PairingQrPreview() = PreviewComponent { PairingQr("ABC234") }

@ScreenPreviews
@Composable
internal fun ResetCodeDialogPreview() = PreviewTheme { ResetCodeDialog() }

@ScreenPreviews
@Composable
internal fun ClearRecentsDialogPreview() = PreviewTheme { ClearRecentsDialog() }

@ScreenPreviews
@Composable
internal fun MicrophoneDialogPreview() = PreviewTheme { MicrophoneDialog() }

@ScreenPreviews
@Composable
internal fun ConfirmationDialogPreview() = PreviewTheme {
    ConfirmationDialog(
        stringResource(R.string.ds_ui_clear_recent_devices),
        stringResource(R.string.ds_ui_clear_recent_history_description),
        stringResource(R.string.ds_ui_clear),
    )
}
