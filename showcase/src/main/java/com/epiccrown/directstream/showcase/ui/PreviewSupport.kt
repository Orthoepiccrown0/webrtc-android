package com.epiccrown.directstream.showcase.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.epiccrown.directstream.ui.presentation.theme.DirectStreamTheme

@Preview(
    name = "Light",
    widthDp = 393,
    heightDp = 850,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Preview(
    name = "Dark",
    widthDp = 393,
    heightDp = 850,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
internal annotation class ScreenPreviews

@Preview(
    name = "Light",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Preview(
    name = "Dark",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
internal annotation class ComponentPreviews

@Composable fun PreviewTheme(content: @Composable () -> Unit) = DirectStreamTheme(content = content)

@Composable
fun PreviewComponent(content: @Composable () -> Unit) = PreviewTheme {
    Surface(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { content() }
    }
}
