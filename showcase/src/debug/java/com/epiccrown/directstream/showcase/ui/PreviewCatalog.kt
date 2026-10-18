package com.epiccrown.directstream.showcase.ui

import androidx.compose.runtime.Composable

/** Debug-only entry points for rendering the actual Studio previews in instrumentation. */
object PreviewCatalog {
    val names =
        listOf(
            "CodecDiagnosticsPreview",
            "CodecPolicySelectorPreview",
            "LargeCodecSettingsPreview",
            "CodecSettingsPreview",
            "UnsupportedCodecSettingsPreview",
            "CodecChoicePreview",
            "ScreenGalleryPreview",
            "ScreenExamplePreview",
            "LargeIntegrationGuidePreview",
            "RegenerateCodeCtaPreview",
            "CallTopBarPreview",
            "ReconnectingCallPreview",
            "MicrophoneDeniedCallPreview",
            "LargeTextCallPreview",
            "MissingSetupSettingsPreview",
            "DeniedPermissionsSettingsPreview",
            "LargeSettingsPreview",
            "IntegrationGuidePreview",
            "SettingsGroupPreview",
            "SdkOverviewPreview",
            "SetupStatusPreview",
            "SettingsActionRowPreview",
            "SdkLimitationsPreview",
            "IntegrationSnippetPreview",
            "IncomingCallerCardPreview",
            "IncomingCallActionsPreview",
            "HomePreview",
            "EmptyHomePreview",
            "OfflineHomePreview",
            "OngoingCallHomePreview",
            "NarrowHomePreview",
            "BrandHeaderPreview",
            "ReadinessCardPreview",
            "OngoingCallCardPreview",
            "DeviceCodeCardPreview",
            "DeviceCodeControlsPreview",
            "QrCodeCardPreview",
            "RecentCallsCardPreview",
            "EmptyRecentCallsCardPreview",
            "RecentDeviceRowPreview",
            "FailedRecentDeviceRowPreview",
            "InCallPreview",
            "PausedInCallPreview",
            "LandscapeInCallPreview",
            "ExpandedInCallPreview",
            "CallDiagnosticsPreview",
            "ExpandedCallDiagnosticsPreview",
            "CallControlsPreview",
            "AudioOutputDialogPreview",
            "RemoteVideoSurfacePreview",
            "LocalVideoSurfacePreview",
            "PausedLocalVideoPreview",
            "VideoPlaceholderPreview",
            "RequestCardPreview",
            "PairingQrPreview",
            "PeerNamePreview",
            "CameraGlyphPreview",
            "PagePreview",
            "ResetCodeDialogPreview",
            "ClearRecentsDialogPreview",
            "MicrophoneDialogPreview",
            "ConfirmationDialogPreview",
            "CallEntryPreview",
            "InvalidCallEntryPreview",
            "WaitingPreview",
            "IncomingCallPreview",
            "SettingsPreview",
            "DeviceCodeEntryCardPreview",
        )

    @Composable
    fun Content(name: String) {
        when (name) {
            "CodecPolicySelectorPreview" -> CodecPolicySelectorPreview()
            "LargeCodecSettingsPreview" -> LargeCodecSettingsPreview()
            "CodecSettingsPreview" -> CodecSettingsPreview()
            "UnsupportedCodecSettingsPreview" -> UnsupportedCodecSettingsPreview()
            "CodecChoicePreview" -> CodecChoicePreview()
            "ScreenGalleryPreview" -> ScreenGalleryPreview()
            "ScreenExamplePreview" -> ScreenExamplePreview()
            "LargeIntegrationGuidePreview" -> LargeIntegrationGuidePreview()
            "RegenerateCodeCtaPreview" -> RegenerateCodeCtaPreview()

            "MissingSetupSettingsPreview" -> MissingSetupSettingsPreview()
            "DeniedPermissionsSettingsPreview" -> DeniedPermissionsSettingsPreview()
            "LargeSettingsPreview" -> LargeSettingsPreview()
            "IntegrationGuidePreview" -> IntegrationGuidePreview()
            "SettingsGroupPreview" -> SettingsGroupPreview()
            "SdkOverviewPreview" -> SdkOverviewPreview()
            "SetupStatusPreview" -> SetupStatusPreview()
            "SettingsActionRowPreview" -> SettingsActionRowPreview()
            "SdkLimitationsPreview" -> SdkLimitationsPreview()
            "IntegrationSnippetPreview" -> IntegrationSnippetPreview()

            "HomePreview" -> HomePreview()
            "EmptyHomePreview" -> EmptyHomePreview()
            "OfflineHomePreview" -> OfflineHomePreview()
            "OngoingCallHomePreview" -> OngoingCallHomePreview()
            "NarrowHomePreview" -> NarrowHomePreview()
            "BrandHeaderPreview" -> BrandHeaderPreview()
            "ReadinessCardPreview" -> ReadinessCardPreview()
            "OngoingCallCardPreview" -> OngoingCallCardPreview()
            "DeviceCodeCardPreview" -> DeviceCodeCardPreview()
            "DeviceCodeControlsPreview" -> DeviceCodeControlsPreview()
            "QrCodeCardPreview" -> QrCodeCardPreview()
            "RecentCallsCardPreview" -> RecentCallsCardPreview()
            "EmptyRecentCallsCardPreview" -> EmptyRecentCallsCardPreview()
            "RecentDeviceRowPreview" -> RecentDeviceRowPreview()
            "FailedRecentDeviceRowPreview" -> FailedRecentDeviceRowPreview()
            "PairingQrPreview" -> PairingQrPreview()
            "ResetCodeDialogPreview" -> ResetCodeDialogPreview()
            "ClearRecentsDialogPreview" -> ClearRecentsDialogPreview()
            "MicrophoneDialogPreview" -> MicrophoneDialogPreview()
            "ConfirmationDialogPreview" -> ConfirmationDialogPreview()
            "CallEntryPreview" -> CallEntryPreview()
            "InvalidCallEntryPreview" -> InvalidCallEntryPreview()
            "SettingsPreview" -> SettingsPreview()
            "DeviceCodeEntryCardPreview" -> DeviceCodeEntryCardPreview()

            else -> com.epiccrown.directstream.ui.presentation.CallPreviewCatalog.Content(name)
        }
    }
}
