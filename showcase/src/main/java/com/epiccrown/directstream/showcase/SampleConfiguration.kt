package com.epiccrown.directstream.showcase

import android.content.Context
import com.epiccrown.directstream.DirectStreamConfiguration
import com.epiccrown.directstream.firebase.FirebaseWorkerConfiguration
import com.epiccrown.directstream.firebase.FirebaseWorkerProvider

/** Demo-host configuration, passed to the independent SDK in Application.onCreate. */
fun sampleConfiguration(context: Context) =
    DirectStreamConfiguration(
        FirebaseWorkerProvider(
            context,
            FirebaseWorkerConfiguration(
                BuildConfig.PROJECT_ID,
                BuildConfig.APPLICATION_ID,
                BuildConfig.API_KEY,
                BuildConfig.DATABASE_URL,
                BuildConfig.SENDER_ID,
                BuildConfig.WORKER_URL,
            ),
        ),
        participantName = ShowcaseParticipant.name(context),
        showDebugIdentifiers = BuildConfig.DEBUG,
        forceRelay = BuildConfig.FORCE_RELAY,
        notificationIcon = R.drawable.ic_stream_notification,
        returnActivity = MainActivity::class.java,
        codecPreferences =
            com.epiccrown.directstream.showcase.ui.CodecSettingsStore(context).load(),
    )
