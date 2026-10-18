@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.epiccrown.directstream.ui.presentation

import androidx.compose.runtime.Composable

/**
 * Host-owned, debug-only rendering of the pinned SDK's internal Studio previews. These preview
 * entry points are test fixtures, not supported SDK integration APIs, and may change with upgrades.
 */
object CallPreviewCatalog {
    @Composable
    fun Content(name: String) {
        when (name) {
            "CodecDiagnosticsPreview" -> CodecDiagnosticsPreview()
            "InCallPreview" -> InCallPreview()
            "PausedInCallPreview" -> PausedInCallPreview()
            "LandscapeInCallPreview" -> LandscapeInCallPreview()
            "ExpandedInCallPreview" -> ExpandedInCallPreview()
            "CallDiagnosticsPreview" -> CallDiagnosticsPreview()
            "ExpandedCallDiagnosticsPreview" -> ExpandedCallDiagnosticsPreview()
            "CallControlsPreview" -> CallControlsPreview()
            "AudioOutputDialogPreview" -> AudioOutputDialogPreview()
            "RemoteVideoSurfacePreview" -> RemoteVideoSurfacePreview()
            "LocalVideoSurfacePreview" -> LocalVideoSurfacePreview()
            "PausedLocalVideoPreview" -> PausedLocalVideoPreview()
            "VideoPlaceholderPreview" -> VideoPlaceholderPreview()
            "CallTopBarPreview" -> CallTopBarPreview()
            "ReconnectingCallPreview" -> ReconnectingCallPreview()
            "MicrophoneDeniedCallPreview" -> MicrophoneDeniedCallPreview()
            "LargeTextCallPreview" -> LargeTextCallPreview()
            "WaitingPreview" -> WaitingPreview()
            "IncomingCallPreview" -> IncomingCallPreview()
            "IncomingCallerCardPreview" -> IncomingCallerCardPreview()
            "IncomingCallActionsPreview" -> IncomingCallActionsPreview()
            "RequestCardPreview" -> RequestCardPreview()
            "PeerNamePreview" -> PeerNamePreview()
            "CameraGlyphPreview" -> CameraGlyphPreview()
            "PagePreview" -> PagePreview()
            else -> error("Unknown SDK preview")
        }
    }
}
