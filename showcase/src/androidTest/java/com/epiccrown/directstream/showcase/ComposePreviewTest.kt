package com.epiccrown.directstream.showcase

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.epiccrown.directstream.showcase.ui.PreviewCatalog
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Renders the actual preview entry points without a ViewModel, Firebase or a WebRTC client. */
class ComposePreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyPreviewComposesWithSampleDataInLightAndDark() {
        val selected = mutableStateOf(PreviewCatalog.names.first())
        val dark = mutableStateOf(false)
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    uiMode =
                        (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark.value) Configuration.UI_MODE_NIGHT_YES
                            else Configuration.UI_MODE_NIGHT_NO
                }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalInspectionMode provides true,
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(
                        androidx.compose.ui.platform.LocalDensity.current.density,
                        if (selected.value == "LargeTextCallPreview") 2f
                        else if (selected.value == "LargeIntegrationGuidePreview") 1.6f else 1f,
                    ),
            ) {
                PreviewCatalog.Content(selected.value)
            }
        }
        for (night in listOf(false, true)) {
            for (name in PreviewCatalog.names) {
                compose.runOnIdle {
                    selected.value = name
                    dark.value = night
                }
                compose.waitForIdle()
                if (
                    name in
                        listOf(
                            "IntegrationGuidePreview",
                            "LargeIntegrationGuidePreview",
                            "HomePreview",
                            "InCallPreview",
                            "CallEntryPreview",
                            "SettingsPreview",
                            "IncomingCallPreview",
                            "ExpandedInCallPreview",
                            "LargeTextCallPreview",
                            "MicrophoneDeniedCallPreview",
                            "ReconnectingCallPreview",
                        )
                ) {
                    File(
                            compose.activity.cacheDir,
                            "preview-$name-${if (night) "dark" else "light"}.png",
                        )
                        .outputStream()
                        .use {
                            compose
                                .onRoot()
                                .captureToImage()
                                .asAndroidBitmap()
                                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                }
            }
        }
    }
}
