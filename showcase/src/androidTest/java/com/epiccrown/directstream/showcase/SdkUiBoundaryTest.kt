package com.epiccrown.directstream.showcase

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.epiccrown.directstream.CallCommand
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.DirectStreamConfiguration
import com.epiccrown.directstream.testing.InMemoryCallProvider
import com.epiccrown.directstream.testing.InMemoryCallServer
import com.epiccrown.directstream.ui.DirectStreamCallFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Production call UI must not register, expose demo addressing or accept on the host's behalf. */
class SdkUiBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hostOwnsIdleRegistrationAndAcceptanceWithoutPrototypeControls() = runBlocking {
        DirectStreamClient.currentOrNull()?.dispose()
        val server = InMemoryCallServer()
        val target = InMemoryCallProvider(server, "ui-target")
        val caller = InMemoryCallProvider(server, "ui-caller")
        val client =
            withContext(Dispatchers.Main) {
                DirectStreamClient.initialize(
                    compose.activity.applicationContext,
                    DirectStreamConfiguration(target),
                )
            }
        val shown = mutableStateOf(true)
        var accepts = 0
        try {
            compose.setContent {
                MaterialTheme {
                    if (shown.value)
                        DirectStreamCallFlow(
                            client,
                            onAccept = { accepts++ },
                            onIdle = { Text("Host contacts") },
                        )
                }
            }
            compose.onNodeWithText("Host contacts").assertExists()
            assertFalse(client.state.value.ready)
            assertNull(client.state.value.device)
            compose.onNodeWithText("Scan QR").assertDoesNotExist()
            compose.onNodeWithTag("device-code-input").assertDoesNotExist()
            compose.onNodeWithText("Settings").assertDoesNotExist()
            client.activate()
            val request =
                caller.admission.create(
                    client.state.value.device!!.code,
                    "ui-incoming-request-1234",
                )
            client.execute(CallCommand.ReviewIncoming(request.requestId))
            compose.onNodeWithText("Incoming video call").assertExists()
            compose.onNodeWithText("Accept").performClick()
            assertEquals(1, accepts)
            assertEquals("pending", caller.admission.status(request.requestId).status)
            assertNull(com.epiccrown.directstream.testing.SdkTestAccess.bridge(client).token)
            compose.onNodeWithText("Decline").performClick()
            compose.onNodeWithText("Host contacts").assertExists()
            assertEquals("rejected", caller.admission.status(request.requestId).status)
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            client.dispose()
            withContext(Dispatchers.Main) {
                DirectStreamClient.initialize(
                    compose.activity.applicationContext,
                    sampleConfiguration(compose.activity),
                )
            }
        }
    }
}
