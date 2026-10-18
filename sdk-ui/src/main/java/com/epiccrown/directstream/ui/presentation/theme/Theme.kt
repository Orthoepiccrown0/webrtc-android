package com.epiccrown.directstream.ui.presentation.theme

import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

@Composable
fun DirectStreamTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    if (!inspection)
        DisposableEffect(view, darkTheme) {
            val controller = view.windowInsetsController
            val mask =
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller?.setSystemBarsAppearance(if (darkTheme) 0 else mask, mask)
            onDispose {}
        }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes =
            Shapes(
                RoundedCornerShape(4.dp),
                RoundedCornerShape(8.dp),
                RoundedCornerShape(16.dp),
                RoundedCornerShape(20.dp),
                RoundedCornerShape(28.dp),
            ),
        content = content,
    )
}
