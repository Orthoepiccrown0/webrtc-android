package com.epiccrown.directstream.showcase.integration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.ui.DirectStreamHost

/** Debug-only host for isolated SDK integration tests; never activates the live provider. */
class SdkTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DirectStreamHost(DirectStreamClient.current()) {} } }
    }
}
