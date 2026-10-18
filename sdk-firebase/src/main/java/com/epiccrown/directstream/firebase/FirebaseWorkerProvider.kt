package com.epiccrown.directstream.firebase

import android.content.Context
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.firebase.internal.*
import com.epiccrown.directstream.firebase.network.PairingApi
import java.net.URI

data class FirebaseWorkerConfiguration(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val databaseUrl: String,
    val senderId: String,
    val workerUrl: String,
)

class FirebaseWorkerProvider(
    context: Context,
    private val configuration: FirebaseWorkerConfiguration,
) : CallProvider {
    private val clients =
        FirebaseClients(
            context.applicationContext,
            FirebaseConfiguration(
                configuration.projectId,
                configuration.applicationId,
                configuration.apiKey,
                configuration.databaseUrl,
                configuration.senderId,
            ),
        )
    private val api = PairingApi(clients, configuration.workerUrl)
    override val name = "Firebase + Cloudflare Worker"
    override val configurationKey: Any
        get() = configuration

    override fun validate(): ConfigurationCheck {
        val missing =
            clients.configuration.missingSettings.toList() +
                if (configuration.workerUrl.isBlank()) listOf("WORKER_URL") else emptyList()
        fun https(value: String) = runCatching {
            URI(value).let {
                it.scheme == "https" &&
                    !it.host.isNullOrBlank() &&
                    it.userInfo == null &&
                    it.rawQuery == null &&
                    it.rawFragment == null
            }
        }
            .getOrDefault(false)
        val invalid =
            listOf(
                    "DATABASE_URL" to configuration.databaseUrl,
                    "WORKER_URL" to configuration.workerUrl,
                )
                .filter { it.second.isNotBlank() && !https(it.second) }
                .map { it.first }
        return ConfigurationCheck(missing, invalid)
    }

    override val identity =
        object : IdentityProvider {
            override suspend fun signIn() = clients.signIn()

            override suspend fun ownershipProof() = clients.ownershipProof()
        }
    private val events = FirebaseAdmissionEvents(clients)
    override val admission =
        object : AdmissionProvider {
            override fun incomingEvents() = events.incoming()

            override fun requestEvents(id: String) = events.request(id)

            override fun sessionEvents(roomId: String) = events.session(roomId)

            override suspend fun register() = api.register()

            override suspend fun register(participantName: String?) = api.register(participantName)

            override suspend fun reset() = api.reset()

            override suspend fun revoke() = api.revoke()

            override suspend fun create(code: String, requestId: String, mode: CallMode) =
                api.create(code, requestId, mode)

            override suspend fun create(code: String, requestId: String) =
                api.create(code, requestId)

            override suspend fun incoming() = api.incoming()

            override suspend fun status(id: String, timeoutSeconds: Long) =
                api.status(id, timeoutSeconds)

            override suspend fun approve(id: String) = api.approve(id)

            override suspend fun reject(id: String) = api.reject(id)

            override suspend fun cancel(id: String) = api.cancel(id)

            override suspend fun end(roomId: String) = api.end(roomId)

            override suspend fun sessionActive(roomId: String) = api.sessionActive(roomId)
        }
    private val firebaseSignaling = FirebaseSignaling(clients)
    override val signaling =
        object : SignalingProvider {
            override suspend fun publishCodecSignal(id: String, role: Role, signal: CodecSignal) =
                firebaseSignaling.publishCodecSignal(id, role, signal)

            override fun codecSignals(id: String, role: Role) =
                firebaseSignaling.codecSignals(id, role)

            override fun connectionStates() = firebaseSignaling.connectionStates()

            override suspend fun getRoom(id: String) = firebaseSignaling.getRoom(id)

            override suspend fun publishDescription(
                id: String,
                role: Role,
                description: SignalDescription,
            ) = firebaseSignaling.publishDescription(id, role, description)

            override fun descriptions(id: String, role: Role) =
                firebaseSignaling.descriptions(id, role)

            override suspend fun publishCandidate(
                id: String,
                role: Role,
                candidate: SignalCandidate,
            ) = firebaseSignaling.publishCandidate(id, role, candidate)

            override fun candidates(id: String, role: Role) = firebaseSignaling.candidates(id, role)

            override fun ended(id: String) = firebaseSignaling.ended(id)

            override suspend fun presence(id: String, role: Role, connected: Boolean) =
                firebaseSignaling.presence(id, role, connected)
        }
    override val push =
        object : PushProvider {
            override suspend fun currentToken(): String? = null // Tokens are always host-forwarded.

            override suspend fun registerToken(token: String) {
                val app =
                    com.google.firebase.FirebaseApp.getApps(context).firstOrNull {
                        it.name == com.google.firebase.FirebaseApp.DEFAULT_APP_NAME
                    }
                if (
                    app?.options?.projectId != configuration.projectId ||
                        app.options.gcmSenderId != configuration.senderId
                )
                    throw BackendException(
                        426,
                        "FCM project configuration does not match the SDK provider.",
                    )
                api.pushToken(token)
            }
        }
    override val relay =
        object : RelayProvider {
            override suspend fun iceServers(roomId: String) = api.iceServers(roomId)
        }
}
