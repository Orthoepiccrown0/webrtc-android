package com.epiccrown.directstream.showcase

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.epiccrown.directstream.DirectStreamClient
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import timber.log.Timber

@HiltAndroidApp
class StreamingApplication : Application() {
    @Inject lateinit var client: DirectStreamClient

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Timber.tag("App").i("Starting DirectStream SDK")
        // Hilt creates the process client from host configuration before incoming helpers are
        // installed.
        check(client === DirectStreamClient.current())
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            client.activate()
            if (
                client.configuration.provider
                    is com.epiccrown.directstream.firebase.FirebaseWorkerProvider
            ) {
                try {
                    val token =
                        withTimeout(10_000) {
                            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                                .token
                                .await()
                        }
                    client.updatePushToken(token)
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                    Timber.tag("Push").w("Initial push token unavailable")
                } catch (error: Exception) {
                    Timber.tag("Push")
                        .w("Initial push token unavailable (%s)", error.javaClass.simpleName)
                }
            }
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    "pairing",
                    getString(R.string.connection_requests),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
    }
}
