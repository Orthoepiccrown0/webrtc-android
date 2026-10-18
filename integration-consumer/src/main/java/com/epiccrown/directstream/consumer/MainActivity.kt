package com.epiccrown.directstream.consumer

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import com.epiccrown.directstream.*
import com.epiccrown.directstream.api.CallMode
import com.epiccrown.directstream.testing.InMemoryCallProvider
import com.epiccrown.directstream.ui.*
import kotlinx.coroutines.*

class ConsumerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val client =
            DirectStreamClient.initialize(
                this,
                DirectStreamConfiguration(
                    InMemoryCallProvider(),
                    returnActivity = MainActivity::class.java,
                ),
            )
        val actions =
            CallingActions(client) { failure ->
                android.util.Log.w("DirectStreamConsumer", "Calling failed: ${failure.kind}")
            }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { actions.activate() }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DirectStreamHost(DirectStreamClient.current()) { callUi ->
                    Button(onClick = { callUi.startCall("ABC234", CallMode.Audio) }) {
                        Text("Call a device")
                    }
                }
            }
        }
    }
}

// Compile probe: public generated serializers must be usable without a direct serialization
// dependency.
private val codecSerializer:
    kotlinx.serialization.KSerializer<com.epiccrown.directstream.api.CodecId> =
    com.epiccrown.directstream.api.CodecId.serializer()
