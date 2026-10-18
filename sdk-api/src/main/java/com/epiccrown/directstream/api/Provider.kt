package com.epiccrown.directstream.api

import kotlinx.coroutines.flow.Flow

/** Provider errors use HTTP-like status categories, independent of a particular transport. */
class BackendException(val status: Int, message: String) : Exception(message)

data class ConfigurationCheck(
    val missing: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
) {
    val valid
        get() = missing.isEmpty() && invalid.isEmpty()
}

interface IdentityProvider {
    suspend fun signIn(): String

    suspend fun ownershipProof(): DeviceOwnershipProof =
        throw BackendException(501, "Ownership proof is unsupported.")
}

data class SessionStatus(val active: Boolean, val expiresAt: Long)

interface AdmissionProvider {
    /** Read-only participant-scoped snapshots, including initial state and subsequent changes. */
    fun incomingEvents(): Flow<PairingRequest?>

    fun requestEvents(id: String): Flow<PairingRequest>

    fun sessionEvents(roomId: String): Flow<SessionStatus>

    suspend fun register(): Device

    /** Register host-assigned metadata without publishing a device directory. */
    suspend fun register(participantName: String?): Device {
        if (ParticipantName.normalize(participantName) != null)
            throw BackendException(501, "Participant names are unsupported by this provider.")
        return register()
    }

    suspend fun reset(): Device

    suspend fun revoke() {
        throw BackendException(501, "Registration revocation is unsupported.")
    }

    suspend fun create(code: String, requestId: String, mode: CallMode): PairingRequest =
        if (mode == CallMode.Video) create(code, requestId)
        else throw BackendException(501, "Audio calls are unsupported.")

    suspend fun create(code: String, requestId: String): PairingRequest

    suspend fun incoming(): PairingRequest?

    suspend fun status(id: String, timeoutSeconds: Long = 20): PairingRequest

    suspend fun approve(id: String): String

    suspend fun reject(id: String)

    suspend fun cancel(id: String)

    suspend fun end(roomId: String)

    suspend fun sessionActive(roomId: String)
}

interface SignalingProvider {
    suspend fun publishCodecSignal(id: String, role: Role, signal: CodecSignal) {
        throw CodecException(CodecFailureReason.PEER_UNAVAILABLE)
    }

    fun codecSignals(id: String, role: Role): Flow<CodecSignal> =
        kotlinx.coroutines.flow.flow {
            throw CodecException(CodecFailureReason.PEER_UNAVAILABLE)
        }

    /**
     * Optional transport status; a restored connection revalidates the session and renews presence.
     */
    fun connectionStates(): Flow<Boolean> = kotlinx.coroutines.flow.emptyFlow()

    suspend fun getRoom(id: String): Room

    suspend fun publishDescription(id: String, role: Role, description: SignalDescription)

    fun descriptions(id: String, role: Role): Flow<SignalDescription>

    suspend fun publishCandidate(id: String, role: Role, candidate: SignalCandidate)

    fun candidates(id: String, role: Role): Flow<SignalCandidate>

    fun ended(id: String): Flow<Boolean>

    suspend fun presence(id: String, role: Role, connected: Boolean)
}

interface PushProvider {
    /** Recognize this provider's push envelope; authorization is always revalidated separately. */
    fun isIncomingMessage(data: Map<String, String>): Boolean =
        data["type"] == "directstream.incoming" && data["protocolVersion"] == "1"

    suspend fun currentToken(): String?

    suspend fun registerToken(token: String)
}

interface RelayProvider {
    suspend fun iceServers(roomId: String): TurnConfiguration
}

interface CallProvider {
    val name: String
    /**
     * Stable configuration identity; equality must include credentials/endpoints, never display or
     * log it.
     */
    val configurationKey: Any

    fun validate(): ConfigurationCheck

    val identity: IdentityProvider
    val admission: AdmissionProvider
    val signaling: SignalingProvider
    val push: PushProvider?
        get() = null

    val relay: RelayProvider?
        get() = null

    suspend fun dispose() {}
}

enum class FailureKind {
    CONFIGURATION,
    INVALID_INPUT,
    BUSY,
    UNAUTHORIZED,
    UNAVAILABLE,
    EXPIRED,
    PERMISSION,
    DISPOSED,
    INTERNAL,
    CODEC,
    UNSUPPORTED,
}

data class CallFailure(
    val kind: FailureKind,
    val message: String,
    val codecReason: CodecFailureReason? = null,
    val requiredPermissions: List<String> = emptyList(),
    val requiresVisibleUnlockedUi: Boolean = false,
)

fun Throwable.callFailure(): CallFailure =
    if (this is CodecException)
        CallFailure(FailureKind.CODEC, "Codec operation failed: ${reason.name}", reason)
    else
        CallFailure(
            when (this) {
                is SecurityException -> FailureKind.PERMISSION
                is IllegalArgumentException -> FailureKind.INVALID_INPUT
                is BackendException ->
                    when (status) {
                        426 -> FailureKind.CONFIGURATION
                        501 -> FailureKind.UNSUPPORTED
                        400 -> FailureKind.INVALID_INPUT
                        401,
                        403 -> FailureKind.UNAUTHORIZED
                        404,
                        410 -> FailureKind.EXPIRED
                        409 -> FailureKind.BUSY
                        else -> FailureKind.UNAVAILABLE
                    }
                is java.io.IOException -> FailureKind.UNAVAILABLE
                else -> FailureKind.INTERNAL
            },
            when (this) {
                is BackendException -> "Call service rejected the operation ($status)."
                is IllegalArgumentException -> "Check the device code and input."
                is SecurityException -> "Required permission is unavailable."
                else -> "Could not complete the operation. Please retry."
            },
        )

/**
 * Android hosts can localize presentation without changing the context-free failure classification
 * API.
 */
fun Throwable.callFailure(resources: android.content.res.Resources): CallFailure {
    val message =
        when (this) {
            is CodecException ->
                resources.getString(
                    when (reason) {
                        CodecFailureReason.UNSUPPORTED_LOCAL -> R.string.ds_codec_unsupported_local
                        CodecFailureReason.INCOMPATIBLE_PEER -> R.string.ds_codec_incompatible_peer
                        CodecFailureReason.PEER_UNAVAILABLE -> R.string.ds_codec_peer_unavailable
                        CodecFailureReason.BUSY -> R.string.ds_codec_busy
                        CodecFailureReason.NO_ACTIVE_CALL -> R.string.ds_codec_no_active_call
                        CodecFailureReason.TIMEOUT -> R.string.ds_codec_timeout
                        CodecFailureReason.SESSION_ENDED -> R.string.ds_codec_session_ended
                        CodecFailureReason.NEGOTIATION_FAILED ->
                            R.string.ds_codec_negotiation_failed
                    },
                )
            is BackendException ->
                if (status == 501) resources.getString(R.string.ds_failure_unsupported)
                else resources.getString(R.string.ds_failure_service_rejected, status)
            is IllegalArgumentException -> resources.getString(R.string.ds_failure_invalid_input)
            is SecurityException -> resources.getString(R.string.ds_failure_permission)
            else -> resources.getString(R.string.ds_failure_retry)
        }
    return callFailure().copy(message = message)
}
