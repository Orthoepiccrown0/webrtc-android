package com.epiccrown.directstream.showcase

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.epiccrown.directstream.showcase.ui.theme.DarkColors
import com.epiccrown.directstream.showcase.ui.theme.LightColors
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }

    @Test
    fun primaryAndSurfaceTextMeetReadableContrastInBothThemes() {
        for (scheme in listOf(LightColors, DarkColors)) {
            for ((text, background) in
                listOf(
                    scheme.onPrimary to scheme.primary,
                    scheme.onPrimaryContainer to scheme.primaryContainer,
                    scheme.onSecondary to scheme.secondary,
                    scheme.onSurface to scheme.surface,
                    scheme.onSurfaceVariant to scheme.surfaceContainerLow,
                    scheme.onSurfaceVariant to scheme.surfaceVariant,
                    scheme.onError to scheme.error,
                )) {
                assertTrue(
                    "Text contrast must be at least 4.5:1",
                    contrast(text, background) >= 4.5f,
                )
            }
        }
    }
}
