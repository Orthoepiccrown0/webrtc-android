package com.epiccrown.directstream.firebase.internal

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber

data class FirebaseConfiguration(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val databaseUrl: String,
    val senderId: String,
) {
    val missingSettings
        get() =
            mapOf(
                    "PROJECT_ID" to projectId,
                    "APPLICATION_ID" to applicationId,
                    "API_KEY" to apiKey,
                    "DATABASE_URL" to databaseUrl,
                    "SENDER_ID" to senderId,
                )
                .filterValues { it.isBlank() }
                .keys

    val configured
        get() = missingSettings.isEmpty()
}

class FirebaseClients(private val context: Context, val configuration: FirebaseConfiguration) {
    private val app: FirebaseApp by lazy {
        check(configuration.configured) {
            "Missing Firebase settings: ${configuration.missingSettings.joinToString()}. Update stream.properties and rebuild."
        }
        FirebaseApp.getApps(context)
            .firstOrNull { it.name == "directstream-${configuration.projectId}" }
            ?.also { existing ->
                val options = existing.options
                check(
                    options.projectId == configuration.projectId &&
                        options.applicationId == configuration.applicationId &&
                        options.apiKey == configuration.apiKey &&
                        options.databaseUrl?.trimEnd('/') ==
                            configuration.databaseUrl.trimEnd('/') &&
                        options.gcmSenderId == configuration.senderId,
                ) {
                    "The SDK Firebase app does not match the supplied configuration."
                }
            }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(configuration.projectId)
                    .setApplicationId(configuration.applicationId)
                    .setApiKey(configuration.apiKey)
                    .setDatabaseUrl(configuration.databaseUrl)
                    .setGcmSenderId(configuration.senderId)
                    .build(),
                "directstream-${configuration.projectId}",
            )
    }
    val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance(app)

    val database: FirebaseDatabase
        get() = FirebaseDatabase.getInstance(app)

    val messaging: FirebaseMessaging
        get() {
            app
            return FirebaseMessaging.getInstance()
        }

    private val signInMutex = Mutex()

    suspend fun signIn(): String = signInMutex.withLock {
        auth.currentUser?.let {
            return@withLock it.uid
        }
        Timber.tag("StreamAuth").d("Signing in anonymously")
        val user = networkOperation { auth.signInAnonymously().await().user!! }
        Timber.tag("StreamAuth").i("Anonymous sign-in completed")
        user.uid
    }

    suspend fun ownershipProof(): com.epiccrown.directstream.api.DeviceOwnershipProof {
        signIn()
        val result = networkOperation { auth.currentUser!!.getIdToken(false).await() }
        return com.epiccrown.directstream.api.DeviceOwnershipProof(
            result.token!!,
            "firebase:${configuration.projectId}",
            result.expirationTimestamp * 1000,
        )
    }

    suspend fun idToken(forceRefresh: Boolean = false): String {
        signIn()
        return networkOperation { auth.currentUser!!.getIdToken(forceRefresh).await().token!! }
    }
}

private suspend fun <T> networkOperation(block: suspend () -> T): T =
    try {
        block()
    } catch (error: com.google.firebase.FirebaseNetworkException) {
        throw java.io.IOException("Identity connection unavailable.", error)
    } catch (error: com.google.firebase.FirebaseTooManyRequestsException) {
        throw com.epiccrown.directstream.api.BackendException(
            429,
            "Identity temporarily rate limited.",
        )
    } catch (error: com.google.firebase.auth.FirebaseAuthException) {
        throw com.epiccrown.directstream.api.BackendException(
            401,
            "Identity authentication rejected.",
        )
    }
