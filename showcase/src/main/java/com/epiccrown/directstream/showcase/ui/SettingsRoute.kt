package com.epiccrown.directstream.showcase.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.epiccrown.directstream.SetupStatus

/** Android permission/status adapter. SettingsScreen remains a stateless, previewable view. */
@Composable
internal fun SettingsRoute(
    permissionRevision: Int,
    hasRecents: Boolean,
    codecState: com.epiccrown.directstream.api.CallCodecState,
    onCodecPreferences: (com.epiccrown.directstream.api.CallCodecPreferences) -> Unit,
    setup: SetupStatus?,
    sampleVersion: String,
    onEnableNotifications: () -> Unit,
    onClearRecents: () -> Unit,
    onIntegration: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val permissions = rememberSamplePermissions(permissionRevision)
    SettingsScreen(
        hasRecents = hasRecents,
        setup = setup,
        sampleVersion = sampleVersion,
        permissions = permissions,
        codecState = codecState,
        onCodecPreferences = onCodecPreferences,
        onEnableNotifications = onEnableNotifications,
        onNotificationSettings = {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "incoming-calls"),
            )
        },
        onAppSettings = {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}"),
                ),
            )
        },
        onFullScreenSettings = {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(android.net.Uri.parse("package:${context.packageName}")),
            )
        },
        onClearRecents = { onClearRecents() },
        onIntegration = { onIntegration() },
        onBack = onBack,
    )
}

/** Refresh actual alert access after returning from Android settings; no automatic prompts. */
@Composable
internal fun rememberSamplePermissions(permissionRevision: Int): SamplePermissions {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(refresh, permissionRevision) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val declared =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
        SamplePermissions(
            camera =
                context.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            microphone =
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            notifications =
                manager.areNotificationsEnabled() &&
                    manager.getNotificationChannel("incoming-calls")?.importance !=
                        android.app.NotificationManager.IMPORTANCE_NONE,
            highPriorityIncomingChannel =
                (manager.getNotificationChannel("incoming-calls")?.importance
                    ?: android.app.NotificationManager.IMPORTANCE_HIGH) >=
                    android.app.NotificationManager.IMPORTANCE_HIGH,
            fullScreen = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent(),
            fullScreenSupported =
                Build.VERSION.SDK_INT >= 34 &&
                    Manifest.permission.USE_FULL_SCREEN_INTENT in declared,
        )
    }
}
