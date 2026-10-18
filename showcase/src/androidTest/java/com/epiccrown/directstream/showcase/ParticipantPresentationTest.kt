package com.epiccrown.directstream.showcase

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.ui.presentation.IncomingCallScreen
import org.junit.Rule
import org.junit.Test

class ParticipantPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun namedParticipantHidesDeviceCodeOutsideExplicitDebugMode() {
        val request =
            PairingRequest(
                "named-request-123456789",
                "654321",
                System.currentTimeMillis() + 300_000,
                role = Role.SENDER,
                peerCode = "ABC234",
                peerName = "River Otter",
            )
        compose.setContent {
            MaterialTheme {
                IncomingCallScreen(
                    CallSnapshot(session = StreamSessionState.IncomingRequest(request)),
                )
            }
        }
        compose.onNodeWithText("River Otter").assertExists()
        compose.onNodeWithText("ABC-234", substring = true).assertDoesNotExist()
        compose.onNodeWithText("654321").assertExists()
    }

    @Test
    fun unnamedParticipantFallsBackToCallableCode() {
        val request =
            PairingRequest(
                "unnamed-request-123456789",
                "654321",
                System.currentTimeMillis() + 300_000,
                role = Role.SENDER,
                peerCode = "ABC234",
            )
        compose.setContent {
            MaterialTheme {
                IncomingCallScreen(
                    CallSnapshot(session = StreamSessionState.IncomingRequest(request)),
                )
            }
        }
        compose.onNodeWithText("ABC-234").assertExists()
    }

    @Test
    fun explicitDebugModeLabelsDeviceCodeForNamedParticipant() {
        val request =
            PairingRequest(
                "debug-request-123456789",
                "654321",
                System.currentTimeMillis() + 300_000,
                role = Role.SENDER,
                peerCode = "ABC234",
                peerName = "River Otter",
            )
        compose.setContent {
            MaterialTheme {
                IncomingCallScreen(
                    CallSnapshot(
                        session = StreamSessionState.IncomingRequest(request),
                        showDebugIdentifiers = true,
                    ),
                )
            }
        }
        compose.onNodeWithText("River Otter").assertExists()
        compose.onNodeWithText("Debug device code: ABC-234").assertExists()
    }
}
