package com.epiccrown.directstream.showcase.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface CallRoute : NavKey {
    @Serializable data object Home : CallRoute

    @Serializable data object CallEntry : CallRoute

    @Serializable data class Waiting(val requestId: String) : CallRoute

    @Serializable data class IncomingCall(val requestId: String) : CallRoute

    @Serializable data object InCall : CallRoute

    @Serializable data object ScreenGallery : CallRoute

    @Serializable data class ScreenExample(val screen: ShowcaseScreen) : CallRoute

    @Serializable data object Settings : CallRoute

    @Serializable data object IntegrationGuide : CallRoute

    // Saved navigation from older showcases can deserialize and return Home; no editor remains.
    @Serializable
    @kotlinx.serialization.SerialName("com.epiccrown.directstream.showcase.ui.CallRoute.EditAlias")
    data class RetiredAlias(val recentId: String) : CallRoute
}
