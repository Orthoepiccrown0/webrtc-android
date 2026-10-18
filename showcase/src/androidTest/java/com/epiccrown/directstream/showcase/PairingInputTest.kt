package com.epiccrown.directstream.showcase

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.rule.GrantPermissionRule
import com.epiccrown.directstream.api.AudioOutput
import com.epiccrown.directstream.api.CallDirection
import com.epiccrown.directstream.api.CallMediaState
import com.epiccrown.directstream.api.CallOutcome
import com.epiccrown.directstream.api.Device
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.StreamPermissions
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.internal.media.PreviewVideoRenderer
import com.epiccrown.directstream.internal.media.WebRtcClient
import com.epiccrown.directstream.showcase.ui.PairingScreen
import com.epiccrown.directstream.showcase.ui.PairingViewModel
import com.epiccrown.directstream.showcase.ui.RecentDevice
import com.epiccrown.directstream.showcase.ui.ShowcaseRepository
import com.epiccrown.directstream.showcase.ui.ShowcaseState
import com.epiccrown.directstream.showcase.ui.theme.DirectStreamShowcaseTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runs the real scanner callback UI without depending on camera scanning or cloud services. */
class PairingInputTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            *StreamPermissions.required(Build.VERSION.SDK_INT, Role.RECEIVER).toTypedArray(),
        )
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var repository: Repository
    private val restoration by lazy {
        androidx.compose.ui.test.junit4.StateRestorationTester(compose)
    }

    private class Repository : ShowcaseRepository {
        override val state =
            MutableStateFlow(ShowcaseState(ready = true, device = Device("ABC234", false)))

        override suspend fun initialize() = Unit

        var resets = 0

        override suspend fun resetCode() {
            resets++
            state.value = state.value.copy(device = Device("DEF567"))
        }

        var requests = 0
        var approvals = 0
        var cancels = 0
        var declines = 0
        var callVisible = false
        var microphone = false
        var flips = 0
        var hangups = 0

        override suspend fun setNextCallCodecs(
            preferences: com.epiccrown.directstream.api.CallCodecPreferences,
        ) {
            state.value = state.value.copy(codecs = state.value.codecs.copy(nextCall = preferences))
        }

        override suspend fun setMuted(muted: Boolean) {
            state.value = state.value.copy(media = state.value.media.copy(muted = muted))
        }

        override suspend fun selectAudioOutput(id: Int) {
            state.value =
                state.value.copy(
                    media =
                        state.value.media.copy(
                            output = state.value.media.outputs.first { it.id == id },
                        ),
                )
        }

        override fun configureMicrophone(allowed: Boolean) {
            microphone = allowed
        }

        override suspend fun requestConnection(code: String) {
            requests++
            state.value =
                state.value.copy(
                    session =
                        StreamSessionState.AwaitingApproval(
                            PairingRequest("test", "123456", System.currentTimeMillis() + 300_000),
                        ),
                )
        }

        override suspend fun approveConnection() {
            approvals++
        }

        override suspend fun rejectConnection() {
            declines++
            state.value = state.value.copy(session = StreamSessionState.Idle)
        }

        override suspend fun cancelConnection() {
            cancels++
            state.value = state.value.copy(session = StreamSessionState.Idle)
        }

        override suspend fun disconnect() {
            hangups++
            state.value = state.value.copy(session = StreamSessionState.Idle)
        }

        override suspend fun switchCamera() {
            flips++
        }

        override fun setVisible(visible: Boolean) = Unit

        override fun setCallScreenVisible(visible: Boolean) {
            callVisible = visible
        }

        override fun refreshPushToken(token: String) = Unit
    }

    private fun show(
        dark: Boolean = false,
        large: Boolean = false,
        recents: Boolean = false,
        connected: Boolean = false,
        client: WebRtcClient? = null,
        incoming: PairingRequest? = null,
        payload: () -> String,
    ) {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        repository = Repository()
        if (recents)
            repository.state.value =
                repository.state.value.copy(
                    recents =
                        listOf(
                            RecentDevice(
                                "kitchen",
                                "DEF567",
                                "peer",
                                "Kitchen",
                                "recent",
                                System.currentTimeMillis(),
                                CallDirection.OUTGOING,
                                CallOutcome.COMPLETED,
                            ),
                        ),
                )
        if (connected)
            repository.state.value =
                repository.state.value.copy(
                    session = StreamSessionState.Connected(Role.RECEIVER),
                    media =
                        CallMediaState(
                            microphoneAvailable = true,
                            muted = false,
                            output = AudioOutput(1, "Speaker"),
                            outputs =
                                listOf(AudioOutput(1, "Speaker"), AudioOutput(2, "Headset", true)),
                        ),
                )
        if (incoming != null)
            repository.state.value =
                repository.state.value.copy(session = StreamSessionState.IncomingRequest(incoming))
        val vm = PairingViewModel(repository)
        val rtc = client ?: WebRtcClient(compose.activity)
        restoration.setContent {
            DirectStreamShowcaseTheme(darkTheme = dark) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(
                            density.density,
                            if (large) 1.5f else density.fontScale,
                        ),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        PairingScreen(
                            vm,
                            com.epiccrown.directstream.internal.media.WebRtcVideoRenderer(
                                rtc,
                                kotlinx.coroutines.CoroutineScope(
                                    kotlinx.coroutines.Dispatchers.Main,
                                ),
                            ),
                            null,
                        ) { completed ->
                            completed(payload())
                        }
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        File(compose.activity.cacheDir, name).outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun galleryMakesCallScreensDiscoverableWithoutStartingASession() {
        show { "" }
        compose.onNodeWithText("Screen gallery").performScrollTo().performClick()
        compose.onNodeWithTag("gallery-INCOMING").performScrollTo().performClick()
        compose.onNodeWithText("Incoming video call").assertIsDisplayed()
        compose.onNodeWithText("Accept").performClick()
        compose.onNodeWithTag("gallery-IN_CALL").performScrollTo().performClick()
        compose.onNodeWithTag("call-screen").assertExists()
        screenshot("gallery-connected-call.png")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("gallery-WAITING").performScrollTo().performClick()
        compose.onNodeWithText("Cancel request").performClick()
        assertEquals(0, repository.requests)
        assertEquals(0, repository.approvals)
        assertEquals(0, repository.cancels)
        assertEquals(0, repository.hangups)
        assertTrue(repository.state.value.session is StreamSessionState.Idle)
        assertTrue(!repository.callVisible)
    }

    @Test
    fun codecSettingsShowUnavailableAv1AndChangeOnlyNextCall() {
        show(connected = true) { "" }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            val format = com.epiccrown.directstream.api.CodecFormat(90000)
            val ids =
                listOf(
                    com.epiccrown.directstream.api.CodecId.VP8,
                    com.epiccrown.directstream.api.CodecId.VP9,
                )
            repository.state.value =
                repository.state.value.copy(
                    codecs =
                        com.epiccrown.directstream.api.CallCodecState(
                            capabilities =
                                com.epiccrown.directstream.api.CodecCapabilities(
                                    ids.map {
                                        com.epiccrown.directstream.api.CodecCapability(
                                            it,
                                            listOf(format),
                                            listOf(format),
                                        )
                                    },
                                ),
                            active = com.epiccrown.directstream.api.CallCodecPreferences(),
                            selection =
                                com.epiccrown.directstream.api.CodecSelection(
                                    com.epiccrown.directstream.api.CodecId.VP8,
                                    com.epiccrown.directstream.api.CodecId.OPUS,
                                ),
                            phase = com.epiccrown.directstream.api.CodecPhase.ACTIVE,
                        ),
                )
        }
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithTag("video-codec-enforce").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("video-codec-selector").performScrollTo().performClick()
        compose.onNodeWithText("AV1").assertIsNotEnabled()
        compose.onNodeWithText("VP9").performClick()
        compose.onNodeWithTag("video-codec-enforce").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                com.epiccrown.directstream.api.CodecPolicy.Enforce(
                    com.epiccrown.directstream.api.CodecId.VP9,
                ),
                repository.state.value.codecs.nextCall.video,
            )
            assertEquals(
                com.epiccrown.directstream.api.CodecId.VP8,
                repository.state.value.codecs.selection!!.video,
            )
            assertEquals(0, repository.requests)
        }
        screenshot("codec-settings.png")
    }

    @Test
    fun homeCodeRegenerationRequiresConfirmationAndRefreshesCodeAndQr() {
        show { "" }
        compose.onNodeWithText("Regenerate code").performScrollTo().performClick()
        compose.onNodeWithText("Regenerate device code?").assertExists()
        assertEquals(0, repository.resets)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, repository.resets)
        compose.onNodeWithText("Regenerate code").performScrollTo().performClick()
        compose.onNode(hasText("Regenerate code") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, repository.resets)
        compose.onNodeWithText("DEF-567").assertExists()
        compose.onNodeWithText("ABC-234").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pairing QR for DEF-567").assertExists()
    }

    @Test
    fun homeUsesLightMaterialCardsAndInlineQr() {
        show(recents = true) { "" }
        compose.onNodeWithText("ABC-234").assertExists()
        compose.onNodeWithContentDescription("Pairing QR for ABC-234").assertExists()
        screenshot("home-light.png")
        compose.onNodeWithText("Kitchen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Options for Kitchen").performClick()
        compose.onNodeWithText("Edit alias").assertDoesNotExist()
        compose.onNodeWithText("Remove").assertExists()
    }

    @Test
    fun darkHomeSupportsEnlargedTextAndNoAvailabilityToggle() {
        show(dark = true, large = true) { "" }
        compose.onNodeWithText("ABC-234").performScrollTo().assertIsDisplayed()
        screenshot("home-dark-large.png")
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose
            .onNodeWithText(
                "All devices can receive requests. You choose whether to accept every call.",
            )
            .assertExists()
        compose.onNodeWithText("Allow incoming calls").assertDoesNotExist()
        compose.onNodeWithText("Clear recents").assertExists()
    }

    @Test
    fun settingsOpensIntegrationGuideAndCopiesRealSdkSnippet() {
        show { "" }
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithText("DirectStream SDK sample").assertExists()
        compose.onNodeWithText("View guide").performScrollTo().performClick()
        compose.onNodeWithText("Integration guide").assertExists()
        compose.onNodeWithText("Installation and user integration").assertExists()
        compose.onAllNodesWithText("Copy snippet")[0].performScrollTo().performClick()
        val clipboard =
            compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        assertTrue(
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString()
                .contains("implementation(project(\":sdk-ui\"))"),
        )
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("DirectStream SDK sample").assertExists()
        compose.onNodeWithText("Clear recents").performScrollTo().assertIsNotEnabled()
        screenshot("sdk-settings-light.png")
    }

    @Test
    fun navigationBackFromIncomingDeclinesAndCallBackKeepsSession() {
        show(
            incoming =
                PairingRequest(
                    "incoming",
                    "123456",
                    System.currentTimeMillis() + 300_000,
                    role = Role.SENDER,
                ),
        ) {
            ""
        }
        compose.onNodeWithText("Incoming video call").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("DirectStream SDK").assertExists()
        assertEquals(1, repository.declines)
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(session = StreamSessionState.Connected(Role.RECEIVER))
        }
        compose.onNodeWithTag("call-screen").assertExists()
        assertTrue(repository.callVisible)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Return to call").performScrollTo().assertIsDisplayed()
        assertTrue(repository.state.value.session is StreamSessionState.Connected)
        assertEquals(false, repository.callVisible)
        compose.onNodeWithText("Return to call").performClick()
        compose.onNodeWithTag("call-screen").assertExists()
    }

    @Test
    fun waitingBackCancelsExactlyOnce() {
        show { "" }
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    session =
                        StreamSessionState.AwaitingApproval(
                            PairingRequest(
                                "waiting",
                                "123456",
                                System.currentTimeMillis() + 300_000,
                            ),
                        ),
                )
        }
        compose.onNodeWithText("Calling…").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("DirectStream SDK").assertExists()
        assertEquals(1, repository.cancels)
    }

    @Test
    fun entryInputSurvivesStateRestoration() {
        show { "" }
        compose.onNodeWithText("Call a device").performClick()
        compose.onNodeWithTag("device-code-input").performTextInput("DEF-567")
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(
            "DEF-567",
            compose
                .onNodeWithTag("device-code-input")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        assertEquals(0, repository.requests)
    }

    @Test
    fun restoredLiveDestinationDoesNotRestartAfterProcessStateIsLost() {
        show(connected = true) { "" }
        compose.onNodeWithTag("call-screen").assertExists()
        // The new process has no native media owner, even though navigation has saved InCall.
        repository.state.value = repository.state.value.copy(session = StreamSessionState.Idle)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("DirectStream SDK").assertExists()
        compose.onNodeWithTag("call-screen").assertDoesNotExist()
        assertEquals(false, repository.callVisible)
        assertEquals(0, repository.requests)
    }

    @Test
    fun callHasLargeRemoteVideoAndSeparateSmallLocalPreview() {
        show(connected = true) { "" }
        compose.onNodeWithTag("call-screen").assertExists()
        val remote = compose.onNodeWithTag("remote-video").fetchSemanticsNode().boundsInRoot
        val local = compose.onNodeWithTag("local-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(remote.width > local.width * 2)
        assertTrue(remote.height > local.height * 2)
        compose.onNodeWithText("You").assertExists()
        compose.onNodeWithContentDescription("End call").assertExists()
        val debug = compose.onNodeWithTag("call-debug").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("call-controls").fetchSemanticsNode().boundsInRoot
        assertTrue("Status must be above the local preview", debug.bottom <= local.top)
        assertTrue("Local preview must be on the left", local.right < remote.center.x)
        assertTrue("Local preview must be above controls", local.bottom <= controls.top)
        compose.onNodeWithTag("call-debug").performClick()
        val expanded =
            compose.onNodeWithTag("expanded-call-debug").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Diagnostics must be to the right of the local preview",
            expanded.left >= local.right,
        )
        assertTrue("Diagnostics must remain above controls", expanded.bottom <= controls.top)
        assertTrue("Opening diagnostics must retain capture visibility", repository.callVisible)
        compose.onNodeWithTag("call-debug").performClick()
        compose.onNodeWithTag("expanded-call-debug").assertDoesNotExist()
        compose.onNodeWithText("Send from this device").assertDoesNotExist()
        File(compose.activity.cacheDir, "call-screen.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun nativeCallControlsDispatchAndDialogsKeepCameraVisible() {
        show(connected = true) { "" }
        compose.onNodeWithContentDescription("Mute").performClick()
        compose.onNodeWithContentDescription("Unmute").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Mute").assertIsOff()
        compose.onNodeWithContentDescription("Flip camera").performClick()
        assertEquals(1, repository.flips)
        compose.onNodeWithContentDescription("Audio output: Speaker").performClick()
        compose.onNodeWithText("Speaker").assertIsSelected()
        compose.onNodeWithText("Headset").performClick()
        compose.onNodeWithContentDescription("Audio output: Headset").assertExists()
        assertTrue(repository.callVisible)
        compose.onNodeWithTag("call-debug").performClick()
        assertTrue(repository.callVisible)
        compose.onNodeWithContentDescription("End call").performClick()
        compose.onNodeWithText("DirectStream SDK").assertExists()
        assertEquals(1, repository.hangups)
        assertEquals(false, repository.callVisible)
    }

    @Test
    fun largeTextCallKeepsTouchTargetsAndOverlaysSeparated() {
        show(connected = true, large = true, dark = true) { "" }
        compose.onNodeWithTag("call-debug").performClick()
        val local = compose.onNodeWithTag("local-preview").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("expanded-call-debug").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("call-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.left >= local.right)
        assertTrue(panel.bottom <= controls.top)
        compose
            .onNodeWithContentDescription("Back")
            .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose
            .onNodeWithContentDescription("End call")
            .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        screenshot("call-large-dark.png")
    }

    @Test
    fun outgoingPreviewRendersCameraPixelsWithCenterCropAndRoundedCorners() =
        runBlocking<Unit> {
            val rtc = WebRtcClient(compose.activity, TestColorCamera(borderPattern = true))
            show(connected = true, client = rtc) { "" }
            try {
                rtc.start(Role.RECEIVER, emptyList(), false, {}, {}, {})
                compose.waitUntil(10_000) {
                    var ready = false
                    compose.runOnIdle {
                        val renderer =
                            compose.activity.window.decorView.findViewWithTag<PreviewVideoRenderer>(
                                "outgoing-preview-renderer",
                            )
                        renderer?.bitmap?.let { bitmap ->
                            ready =
                                Color.red(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) >
                                    200
                            bitmap.recycle()
                        }
                    }
                    ready
                }
                val preview =
                    compose.onNodeWithTag("local-preview").captureToImage().asAndroidBitmap()
                assertEquals(3f / 4f, preview.width.toFloat() / preview.height, .02f)
                // The wide source has green/blue edges; the portrait tile must crop them out.
                for (x in listOf(preview.width / 10, preview.width / 2, preview.width * 9 / 10)) {
                    val pixel = preview.getPixel(x, preview.height / 2)
                    assertTrue(
                        "Outgoing camera pixels must be visible, not an opaque grey overlay",
                        Color.red(pixel) > 200 && Color.green(pixel) < 70 && Color.blue(pixel) < 70,
                    )
                }
                val corner = preview.getPixel(0, 0)
                assertTrue(
                    "Rounded corner must clip video",
                    Color.red(corner) < 30 && Color.green(corner) < 30 && Color.blue(corner) < 30,
                )
                File(compose.activity.cacheDir, "call-preview.png").outputStream().use {
                    compose
                        .onRoot()
                        .captureToImage()
                        .asAndroidBitmap()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                rtc.close()
            }
        }

    @Test
    fun invalidCodesNeverStartPermissionOrAdmissionFlow() {
        show { "" }
        compose.onNodeWithText("Call a device").performClick()
        compose.onNodeWithTag("device-code-input").performTextInput("bad")
        compose.onNodeWithText("Request connection").performClick()
        compose
            .onNodeWithText("Enter a valid device code or scan a DirectStream SDK pairing QR.")
            .assertExists()
        assertEquals(0, repository.requests)
    }

    @Test
    fun expiredIncomingCallsCannotBeAccepted() {
        show(
            incoming =
                PairingRequest(
                    "expired",
                    "123456",
                    System.currentTimeMillis() - 1000,
                    role = Role.SENDER,
                ),
        ) {
            ""
        }
        compose.onNodeWithText("Accept").performClick()
        compose.onNodeWithText("This call request is no longer available.").assertExists()
        assertEquals(0, repository.approvals)
    }

    @Test
    fun microphoneDenialOffersExplicitReceiveOnlyFallback() {
        // Run this test with RECORD_AUDIO revoked before instrumentation starts.
        val context = compose.activity
        org.junit.Assume.assumeTrue(
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        show { "" }
        compose.onNodeWithText("Call a device").performClick()
        compose.onNodeWithTag("device-code-input").performTextInput("ABC235")
        compose.onNodeWithText("Request connection").performClick()
        val automation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(5_000) {
            val buttons =
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(
                    "com.android.permissioncontroller:id/permission_deny_button",
                )
            buttons
                ?.firstOrNull()
                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) ==
                true
        }
        compose.onNodeWithText("Continue without microphone").performClick()
        compose.onNodeWithText("Calling…").assertExists()
        assertEquals(1, repository.requests)
        assertEquals(false, repository.microphone)
    }

    @Test
    fun scannedLinkShowsOnlyFormattedDeviceCode() {
        show { "webrtcstream://pair/abc234" }
        compose.onNodeWithText("Call a device").performClick()
        compose.onNodeWithText("Scan QR").performScrollTo().performClick()
        val field = compose.onNodeWithTag("device-code-input")
        assertEquals(
            "ABC-234",
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
    }

    @Test
    fun invalidQrPreservesCodeAndShowsUsefulError() {
        var payload = "webrtcstream://pair/ABC234"
        show { payload }
        compose.onNodeWithText("Call a device").performClick()
        compose.onNodeWithText("Scan QR").performScrollTo().performClick()
        payload = "https://example.com/ABC234"
        compose.onNodeWithText("Scan QR").performScrollTo().performClick()
        assertEquals(
            "ABC-234",
            compose
                .onNodeWithTag("device-code-input")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        compose
            .onNodeWithText("Enter a valid device code or scan a DirectStream SDK pairing QR.")
            .assertExists()
        compose.onNodeWithText("Request connection").assertIsNotEnabled()
    }
}
