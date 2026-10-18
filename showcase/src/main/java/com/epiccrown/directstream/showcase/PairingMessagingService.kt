package com.epiccrown.directstream.showcase

import com.epiccrown.directstream.DirectStreamClient
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/** Host owns push delivery; the core SDK dispatcher validates and presents requests. */
@AndroidEntryPoint
class PairingMessagingService : FirebaseMessagingService() {
    @Inject lateinit var client: DirectStreamClient

    override fun onNewToken(token: String) {
        runBlocking { client.updatePushToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            val result = runBlocking { client.handlePush(message.data) }
            Timber.tag("Push").d("Incoming push result: %s", result.javaClass.simpleName)
        } catch (error: Exception) {
            Timber.tag("Push")
                .w(
                    "Incoming alert unavailable (%s); foreground reconciliation remains available",
                    error.javaClass.simpleName,
                )
        }
    }
}
