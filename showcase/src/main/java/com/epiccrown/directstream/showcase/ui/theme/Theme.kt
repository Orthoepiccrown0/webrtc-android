package com.epiccrown.directstream.showcase.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.epiccrown.directstream.ui.presentation.theme.DirectStreamTheme

@Composable
fun DirectStreamShowcaseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    DirectStreamTheme(darkTheme, content)
}
