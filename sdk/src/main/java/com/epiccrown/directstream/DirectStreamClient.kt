package com.epiccrown.directstream

import android.content.Context
import android.content.Intent
import androidx.annotation.RestrictTo
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.internal.media.WebRtcClient
import com.epiccrown.directstream.internal.media.WebRtcVideoRenderer
import com.epiccrown.directstream.internal.session.StreamController
import com.epiccrown.directstream.internal.session.StreamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val DIRECT_STREAM_VERSION = BuildConfig.SDK_VERSION

data class DirectStreamConfiguration(
    val provider: CallProvider,
    val appName: String = "DirectStream SDK",
    val forceRelay: Boolean = false,
    val relayProvider: RelayProvider? = null,
    val notificationIcon: Int = android.R.drawable.presence_video_online,
    val returnActivity: Class<out android.app.Activity>? = null,
    val codecPreferences: CallCodecPreferences = CallCodecPreferences(),
    /**
     * Initial host-owned display name. Prefer activate(name) after login; later updates use
     * updateParticipantName. Logout clears this seed for subsequent activations.
     */
    val participantName: String? = null,
    /** Explicitly include labeled device codes alongside names in default UI. */
    val showDebugIdentifiers: Boolean = false,
)

enum class InitializationStatus {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
    DISPOSED,
}

data class SetupStatus(
    val provider: String,
    val configuration: ConfigurationCheck,
    val initialization: InitializationStatus,
    val relay: RelayReadiness = RelayReadiness.Unknown,
    val push: PushReadiness = PushReadiness.MissingToken,
    val remoteCleanupPending: Boolean = false,
)

sealed interface CallResult {
    data object Success : CallResult

    data class Failure(val error: CallFailure) : CallResult
}

sealed interface CallEvent {
    data class SessionChanged(val session: StreamSessionState) : CallEvent

    data class Failed(val error: CallFailure) : CallEvent

    data class CodecChanged(val state: CallCodecState) : CallEvent

    data class Outcome(val attempt: CallAttempt) : CallEvent
}

sealed interface CallCommand {
    /** Defaults affect only the next submitted/accepted call. */
    data class SetNextCallCodecs(val preferences: CallCodecPreferences) : CallCommand

    /** Success means accepted; observe codecs/events for commit, rejection or rollback. */
    data class SwitchCodecs(val preferences: CallCodecPreferences) : CallCommand

    data object Register : CallCommand

    /**
     * Replace the registered device code, revoke the old code and cancel incoming pending requests.
     * Already approved calls continue. Equivalent to DirectStreamClient.regenerateCode().
     */
    data object ResetCode : CallCommand

    data class Request(
        val code: String,
        val microphone: Boolean = false,
        val mode: CallMode = CallMode.Video,
    ) : CallCommand

    data class Accept(val microphone: Boolean = false) : CallCommand

    data object Decline : CallCommand

    data object Cancel : CallCommand

    data object Hangup : CallCommand

    data class Mute(val muted: Boolean) : CallCommand

    data object FlipCamera : CallCommand

    data class AudioOutput(val id: Int) : CallCommand

    data object RefreshAudioOutputs : CallCommand

    data class ReviewIncoming(val requestId: String) : CallCommand
}

/**
 * Process-owned calling client. Initialize once in Application, then [activate] when the host
 * authorizes calling. Initialization alone neither registers the installation nor starts capture.
 *
 * Call operations suspend and return [CallResult]; success acknowledges the operation, not a media
 * connection. Collect [callState] for the authoritative session and [registration] for activation.
 * [events] are transient. Permission failures describe requirements for the host to satisfy before
 * retrying. Bind actions to the call ID displayed by the UI; stale IDs fail without affecting a
 * replacement call. Own coroutines above transient UI when an operation must survive navigation.
 */
class DirectStreamClient
private constructor(context: Context, val configuration: DirectStreamConfiguration) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private val registrationCommands = Mutex()
    private val registrationStateLock = Any()
    @Volatile private var registrationGeneration = 0L
    private val pushCommands = Mutex()
    private var registeredPushToken: String? = null
    private val activation =
        this.context.getSharedPreferences("directstream-activation", Context.MODE_PRIVATE)
    @Volatile
    private var participantName: String? =
        if (activation.getBoolean("participant-name-set", false))
            activation.getString("participant-name", null)
        else configuration.participantName
    private val rtc = WebRtcClient(this.context)
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    internal val callBridge = ForegroundCallBridge(this.context)
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    internal val uiRepository: StreamRepository =
        StreamController(
            this.context,
            configuration.provider,
            rtc,
            configuration.forceRelay,
            callBridge,
            configuration.codecPreferences,
            com.epiccrown.directstream.internal.media.AndroidCallPlatform(
                this.context,
                rtc,
                configuration.appName,
            ),
            configuration.relayProvider,
            participantName = participantName,
            showDebugIdentifiers = configuration.showDebugIdentifiers,
        )
    val state: StateFlow<CallSnapshot> = uiRepository.state
    val codecs: StateFlow<CallCodecState> =
        state.map { it.codecs }.stateIn(scope, SharingStarted.Eagerly, state.value.codecs)
    val video: CallVideoRenderer = WebRtcVideoRenderer(rtc, scope)
    private val _setup =
        MutableStateFlow(
            SetupStatus(
                configuration.provider.name,
                configuration.provider.validate(),
                InitializationStatus.NOT_INITIALIZED,
            ),
        )
    val setup: StateFlow<SetupStatus> = _setup.asStateFlow()
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 32)
    /** Events are transient; use state for the current authoritative snapshot. */
    val events: SharedFlow<CallEvent> = _events.asSharedFlow()
    @Volatile private var disposed = false
    private val _registration =
        MutableStateFlow(
            RegistrationState(
                if (activation.getBoolean("cleanup", false)) RegistrationPhase.CleanupPending
                else RegistrationPhase.Inactive,
            ),
        )
    val registration: StateFlow<RegistrationState> = _registration.asStateFlow()
    val callState: StateFlow<CallState> =
        state
            .map { value ->
                CallState(
                    value.session,
                    value.mode,
                    value.latestAttempt?.requestId,
                    value.latestAttempt,
                    value.failure,
                    value.peerDisplayName,
                    value.peerCode,
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, CallState())
    val media: StateFlow<CallMediaState> =
        state.map { it.media }.stateIn(scope, SharingStarted.Eagerly, CallMediaState())
    val diagnostics: StateFlow<StreamStats> =
        state.map { it.stats }.stateIn(scope, SharingStarted.Eagerly, StreamStats())
    val isActivated: Boolean
        get() = activation.getBoolean("active", false) && !activation.getBoolean("cleanup", false)

    /** Activate after the host authorizes calling. Cold starts restore no media. */
    suspend fun activate(): CallResult {
        val generation = registrationGeneration
        return registrationCommands.withLock { activateRegistration(generation) }
    }

    /** Supply the current user's display name at login, then activate calling. Null clears it. */
    suspend fun activate(participantName: String?): CallResult {
        val generation = registrationGeneration
        return registrationCommands.withLock {
            val updated = updateParticipantNameLocked(participantName, generation)
            if (updated is CallResult.Failure) updated else activateRegistration(generation)
        }
    }

    /**
     * Set or clear host-owned display metadata without reinitializing or activating calling. Active
     * registration updates affect future requests; existing calls keep their name snapshot.
     * Inactive names are staged locally. Successful names survive process restarts until logout.
     * Failures can be retried; a lost response does not prove the server rejected the update. This
     * does not authenticate a user or manage the host backend's user/device association.
     */
    suspend fun updateParticipantName(participantName: String?): CallResult {
        val generation = registrationGeneration
        return registrationCommands.withLock {
            updateParticipantNameLocked(participantName, generation)
        }
    }

    private suspend fun updateParticipantNameLocked(value: String?, generation: Long): CallResult {
        if (generation != registrationGeneration) return staleCall()
        if (disposed)
            return CallResult.Failure(CallFailure(FailureKind.DISPOSED, "Client disposed."))
        val normalized =
            try {
                ParticipantName.normalize(value)
            } catch (error: IllegalArgumentException) {
                return CallResult.Failure(error.callFailure(context.resources))
            }
        return try {
            withTimeout(6_000) {
                uiRepository.updateParticipantName(normalized, publish = isActivated)
            }
            synchronized(registrationStateLock) {
                if (generation != registrationGeneration) return staleCall()
                if (disposed)
                    return CallResult.Failure(CallFailure(FailureKind.DISPOSED, "Client disposed."))
                participantName = normalized
                persistParticipantName()
                if (isActivated && state.value.ready)
                    _registration.value =
                        RegistrationState(RegistrationPhase.Active, state.value.device)
                CallResult.Success
            }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            CallResult.Failure(
                CallFailure(FailureKind.UNAVAILABLE, "Name update timed out; retry."),
            )
        } catch (error: Exception) {
            CallResult.Failure(error.callFailure(context.resources))
        }
    }

    private fun persistParticipantName() {
        activation
            .edit()
            .putBoolean("participant-name-set", true)
            .putString("participant-name", participantName)
            .commit()
    }

    private suspend fun activateRegistration(generation: Long): CallResult {
        synchronized(registrationStateLock) {
            if (generation != registrationGeneration) return staleCall()
            if (disposed)
                return CallResult.Failure(CallFailure(FailureKind.DISPOSED, "Client disposed."))
            _registration.value = RegistrationState(RegistrationPhase.Activating)
        }
        if (activation.getBoolean("cleanup", false)) {
            try {
                withTimeout(6_000) { configuration.provider.admission.revoke() }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                return pendingCleanup()
            } catch (_: Exception) {
                return pendingCleanup()
            }
            synchronized(registrationStateLock) {
                if (generation != registrationGeneration) return staleCall()
                activation.edit().putBoolean("cleanup", false).commit()
            }
        }
        if (generation != registrationGeneration) return staleCall()
        uiRepository.updateParticipantName(participantName, publish = false)
        synchronized(registrationStateLock) {
            if (generation != registrationGeneration) return staleCall()
            persistParticipantName()
            activation.edit().putBoolean("active", true).commit()
        }
        val result = execute(CallCommand.Register)
        synchronized(registrationStateLock) {
            if (generation != registrationGeneration || !isActivated) return staleCall()
            _registration.value =
                RegistrationState(
                    if (state.value.ready) RegistrationPhase.Active
                    else RegistrationPhase.Activating,
                    state.value.device,
                    state.value.failure,
                )
        }
        if (isActivated && state.value.ready)
            activation.getString("push-token", null)?.let { updatePushToken(it) }
        return result
    }

    private fun pendingCleanup(): CallResult.Failure {
        val failure =
            CallFailure(FailureKind.UNAVAILABLE, "Registration cleanup is pending connectivity.")
        _registration.value = RegistrationState(RegistrationPhase.CleanupPending, failure = failure)
        return CallResult.Failure(failure)
    }

    /**
     * Logout immediately clears the local display name and disables calling. Remote registration
     * revocation may remain pending offline; it must finish before a fresh activation/code. The
     * host must also remove its own authenticated user/device directory association.
     */
    suspend fun deactivate(): CallResult {
        synchronized(registrationStateLock) {
            if (disposed)
                return CallResult.Failure(CallFailure(FailureKind.DISPOSED, "Client disposed."))
            registrationGeneration++
            registeredPushToken = null
            participantName = null
            activation
                .edit()
                .putBoolean("active", false)
                .putBoolean("cleanup", true)
                .putBoolean("participant-name-set", true)
                .remove("participant-name")
                .commit()
            _registration.value = RegistrationState(RegistrationPhase.CleanupPending)
        }
        com.epiccrown.directstream.incoming.IncomingCallNotifications.cancel(context)
        uiRepository.suspendRegistration()
        return registrationCommands.withLock {
            try {
                withTimeout(6_000) { configuration.provider.admission.revoke() }
                activation.edit().putBoolean("cleanup", false).commit()
                _registration.value = RegistrationState()
                CallResult.Success
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                pendingCleanup()
            } catch (_: Exception) {
                pendingCleanup()
            }
        }
    }

    /** Reconcile a previous logout without discarding a later login's staged display name. */
    private suspend fun retryRegistrationCleanup(): CallResult {
        val generation = registrationGeneration
        return registrationCommands.withLock {
            if (generation != registrationGeneration) return@withLock staleCall()
            if (!activation.getBoolean("cleanup", false)) return@withLock CallResult.Success
            try {
                withTimeout(6_000) { configuration.provider.admission.revoke() }
                synchronized(registrationStateLock) {
                    if (generation != registrationGeneration) return@withLock staleCall()
                    activation.edit().putBoolean("cleanup", false).commit()
                    _registration.value = RegistrationState()
                    CallResult.Success
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                pendingCleanup()
            } catch (_: Exception) {
                pendingCleanup()
            }
        }
    }

    /** Request a connection after activation; media starts only after the peer accepts. */
    suspend fun startCall(code: String, mode: CallMode, microphone: Boolean = false): CallResult {
        if (!isActivated)
            return CallResult.Failure(
                CallFailure(FailureKind.UNAVAILABLE, "Activate calling first."),
            )
        return execute(CallCommand.Request(code, microphone, mode))
    }

    private fun matches(id: String) = state.value.latestAttempt?.requestId == id

    /** Accept the displayed incoming request; observe [callState] for media connection progress. */
    suspend fun accept(callId: String, microphone: Boolean = false): CallResult =
        if (
            (state.value.session as? StreamSessionState.IncomingRequest)?.request?.requestId !=
                callId
        )
            staleCall()
        else executeBound(CallCommand.Accept(microphone), callId)

    /** Reject the displayed incoming request, without accepting or starting media. */
    suspend fun decline(callId: String): CallResult =
        if (!matches(callId)) staleCall() else executeBound(CallCommand.Decline, callId)

    /** Cancel the displayed outgoing request before it becomes an established call. */
    suspend fun cancel(callId: String): CallResult =
        if (!matches(callId)) staleCall() else executeBound(CallCommand.Cancel, callId)

    /** Disconnect the displayed call and release its media/session resources. */
    suspend fun hangup(callId: String): CallResult =
        if (!matches(callId)) staleCall() else executeBound(CallCommand.Hangup, callId)

    /** Clear a terminal outcome without affecting an ongoing call or pending request. */
    suspend fun dismissOutcome(): CallResult = commands.withLock {
        if (disposed) CallResult.Failure(CallFailure(FailureKind.DISPOSED, "Client disposed."))
        else {
            uiRepository.dismissOutcome()
            CallResult.Success
        }
    }

    suspend fun ownershipProof(): ValueResult<DeviceOwnershipProof> {
        if (!isActivated)
            return ValueResult.Failure(CallFailure(FailureKind.UNAVAILABLE, "Calling is inactive."))
        return try {
            ValueResult.Success(configuration.provider.identity.ownershipProof())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ValueResult.Failure(error.callFailure())
        }
    }

    /** Revalidate a notification request before presenting it; this never accepts the call. */
    suspend fun reviewIncoming(requestId: String): CallResult {
        if (!requestId.matches(Regex("[a-zA-Z0-9_-]{16,100}")))
            return CallResult.Failure(
                CallFailure(FailureKind.INVALID_INPUT, "Invalid request identifier."),
            )
        return execute(CallCommand.ReviewIncoming(requestId))
    }

    suspend fun refreshAudioOutputs(callId: String) =
        executeBound(CallCommand.RefreshAudioOutputs, callId)

    suspend fun setMuted(callId: String, muted: Boolean) =
        executeBound(CallCommand.Mute(muted), callId)

    suspend fun selectAudioOutput(callId: String, output: Int) =
        executeBound(CallCommand.AudioOutput(output), callId)

    suspend fun flipCamera(callId: String): CallResult =
        if (state.value.mode == CallMode.Audio)
            CallResult.Failure(
                CallFailure(FailureKind.INVALID_INPUT, "Audio calls have no camera."),
            )
        else executeBound(CallCommand.FlipCamera, callId)

    suspend fun setNextCallCodecs(preferences: CallCodecPreferences) =
        execute(CallCommand.SetNextCallCodecs(preferences))

    suspend fun switchCodecs(callId: String, preferences: CallCodecPreferences) =
        executeBound(CallCommand.SwitchCodecs(preferences), callId)

    private fun staleCall() =
        CallResult.Failure(CallFailure(FailureKind.EXPIRED, "Call is no longer current."))

    /** Host forwarding can precede activation; queued tokens are sent only after activation. */
    suspend fun updatePushToken(token: String): PushTokenResult = pushCommands.withLock {
        updatePushTokenInternal(token)
    }

    private suspend fun updatePushTokenInternal(token: String): PushTokenResult {
        if (disposed)
            return PushTokenResult.Failure(CallFailure(FailureKind.DISPOSED, "Client disposed."))
        if (token.length !in 20..4096)
            return PushTokenResult.Failure(
                CallFailure(FailureKind.INVALID_INPUT, "Invalid push token."),
            )
        if (configuration.provider.push == null) {
            _setup.update { it.copy(push = PushReadiness.Unavailable) }
            return PushTokenResult.Failure(
                CallFailure(FailureKind.CONFIGURATION, "Push is not configured."),
            )
        }
        activation.edit().putString("push-token", token).commit()
        if (!isActivated || !state.value.ready) {
            _setup.update { it.copy(push = PushReadiness.Queued) }
            return PushTokenResult.Queued
        }
        if (registeredPushToken == token) return PushTokenResult.Registered
        return try {
            withTimeout(6_000) { configuration.provider.push!!.registerToken(token) }
            if (!isActivated) {
                scope.launch { deactivate() }
                return PushTokenResult.Queued
            }
            registeredPushToken = token
            _setup.update { it.copy(push = PushReadiness.Registered) }
            PushTokenResult.Registered
        } catch (timeout: TimeoutCancellationException) {
            _setup.update { it.copy(push = PushReadiness.Failed) }
            PushTokenResult.Failure(
                CallFailure(FailureKind.UNAVAILABLE, "Push registration timed out."),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _setup.update { it.copy(push = PushReadiness.Failed) }
            PushTokenResult.Failure(error.callFailure())
        }
    }

    /** Host forwarding never treats unrelated notifications as calls. */
    suspend fun handlePush(data: Map<String, String>): PushHandlingResult {
        val recognized =
            configuration.provider.push?.isIncomingMessage(data)
                ?: (data["type"] == "directstream.incoming" && data["protocolVersion"] == "1")
        if (!recognized) return PushHandlingResult.Unrelated
        if (!isActivated) return PushHandlingResult.Inactive
        return try {
            if (com.epiccrown.directstream.incoming.IncomingCalls.handlePush(context, data))
                PushHandlingResult.Handled
            else PushHandlingResult.Stale
        } catch (timeout: TimeoutCancellationException) {
            PushHandlingResult.RetryableFailure(
                CallFailure(FailureKind.UNAVAILABLE, "Incoming validation timed out."),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PushHandlingResult.RetryableFailure(error.callFailure())
        }
    }

    init {
        com.epiccrown.directstream.incoming.IncomingCalls.install(
            this.context,
            com.epiccrown.directstream.incoming.IncomingCallOptions(
                configuration.appName,
                configuration.notificationIcon,
            ),
        )
        scope.launch { uiRepository.discoverCodecs() }
        scope.launch {
            state
                .map { it.device }
                .distinctUntilChanged()
                .collect { device ->
                    synchronized(registrationStateLock) {
                        if (isActivated && device != null && device == state.value.device)
                            _registration.value =
                                RegistrationState(RegistrationPhase.Active, device)
                    }
                    _setup.update {
                        it.copy(
                            relay =
                                if (
                                    configuration.relayProvider != null ||
                                        device?.relayAvailable == true
                                )
                                    RelayReadiness.Configured
                                else if (device?.relayAvailable == false) RelayReadiness.DirectOnly
                                else RelayReadiness.Unknown,
                        )
                    }
                }
        }
        scope.launch { codecs.collect { _events.emit(CallEvent.CodecChanged(it)) } }
        scope.launch {
            state
                .map { (it.session as? StreamSessionState.IncomingRequest)?.request }
                .distinctUntilChanged()
                .collectLatest { incoming ->
                    if (incoming == null) return@collectLatest
                    try {
                        validateIncoming(incoming.requestId)?.let { verified ->
                            com.epiccrown.directstream.incoming.IncomingCalls.showValidated(
                                context,
                                this@DirectStreamClient,
                                verified,
                            )
                            delay(
                                (verified.expiresAt - System.currentTimeMillis()).coerceAtLeast(0),
                            )
                            com.epiccrown.directstream.incoming.IncomingCallNotifications.cancel(
                                context,
                                verified.requestId,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        timber.log.Timber.tag("Push")
                            .w("Incoming alert validation failed (%s)", error.javaClass.simpleName)
                    }
                }
        }

        scope.launch {
            state
                .map { it.ready }
                .distinctUntilChanged()
                .collect { ready ->
                    synchronized(registrationStateLock) {
                        if (ready && state.value.ready && isActivated) {
                            _registration.value =
                                RegistrationState(RegistrationPhase.Active, state.value.device)
                            scope.launch {
                                activation.getString("push-token", null)?.let {
                                    updatePushToken(it)
                                }
                            }
                        }
                    }
                    if (ready)
                        _setup.update { it.copy(initialization = InitializationStatus.READY) }
                }
        }
        scope.launch {
            state
                .map { it.session }
                .distinctUntilChanged()
                .collect { session ->
                    if (session !is StreamSessionState.IncomingRequest)
                        com.epiccrown.directstream.incoming.IncomingCalls.clear(context)
                    _events.emit(CallEvent.SessionChanged(session))
                    if (session is StreamSessionState.Failed)
                        _events.emit(
                            CallEvent.Failed(
                                state.value.failure
                                    ?: CallFailure(
                                        FailureKind.UNAVAILABLE,
                                        context.getString(
                                            R.string
                                                .ds_sdk_call_connection_failed_start_a_new_approved_request,
                                        ),
                                    ),
                            ),
                        )
                }
        }
        scope.launch {
            state
                .map { it.latestAttempt }
                .distinctUntilChanged()
                .filterNotNull()
                .filter {
                    it.outcome in
                        listOf(
                            CallOutcome.COMPLETED,
                            CallOutcome.MISSED,
                            CallOutcome.DECLINED,
                            CallOutcome.CANCELLED,
                            CallOutcome.FAILED,
                        )
                }
                .collect { _events.emit(CallEvent.Outcome(it)) }
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    suspend fun execute(command: CallCommand): CallResult = executeBound(command, null)

    private suspend fun executeBound(command: CallCommand, expectedCallId: String?): CallResult {
        val requestAtDispatch =
            when (val session = state.value.session) {
                is StreamSessionState.IncomingRequest -> session.request.requestId
                is StreamSessionState.AwaitingApproval -> session.request.requestId
                else -> null
            }
        return commands.withLock {
            if (expectedCallId != null && !matches(expectedCallId)) return@withLock staleCall()
            if (disposed)
                return@withLock CallResult.Failure(
                    CallFailure(
                        FailureKind.DISPOSED,
                        context.getString(R.string.ds_sdk_client_has_been_disposed),
                    ),
                )
            if (
                command != CallCommand.Register &&
                    command !is CallCommand.SetNextCallCodecs &&
                    !isActivated
            )
                return@withLock CallResult.Failure(
                    CallFailure(FailureKind.UNAVAILABLE, "Calling is inactive."),
                )
            val validation = configuration.provider.validate()
            val needsConfiguration =
                command == CallCommand.Register ||
                    command == CallCommand.ResetCode ||
                    command is CallCommand.Request ||
                    command is CallCommand.Accept ||
                    command is CallCommand.ReviewIncoming
            if (needsConfiguration && !validation.valid) {
                _setup.value =
                    SetupStatus(
                        configuration.provider.name,
                        validation,
                        InitializationStatus.FAILED,
                    )
                return@withLock fail(
                    CallFailure(
                        FailureKind.CONFIGURATION,
                        context.getString(R.string.ds_sdk_complete_the_host_provider_configuration),
                    ),
                )
            }
            try {
                if (command is CallCommand.Request) {
                    val code = DeviceCode.normalize(command.code)
                    if (state.value.device?.supportedCallModes?.contains(command.mode) == false)
                        throw BackendException(501, "Call mode is unsupported by this service.")
                    require(code != state.value.device?.code) {
                        context.getString(R.string.ds_sdk_self_calls_are_not_allowed)
                    }
                }
                if (command is CallCommand.Request || command is CallCommand.Accept) {
                    val mode =
                        if (command is CallCommand.Request) command.mode
                        else
                            (state.value.session as? StreamSessionState.IncomingRequest)
                                ?.request
                                ?.mode ?: CallMode.Video
                    if (
                        command is CallCommand.Accept &&
                            requestAtDispatch !=
                                (state.value.session as? StreamSessionState.IncomingRequest)
                                    ?.request
                                    ?.requestId
                    )
                        return@withLock staleCall()
                    val missingPermissions =
                        StreamPermissions.required(
                                android.os.Build.VERSION.SDK_INT,
                                Role.SENDER,
                                mode,
                            )
                            .filter {
                                context.checkSelfPermission(it) !=
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
                    if (missingPermissions.isNotEmpty()) {
                        return@withLock fail(
                            CallFailure(
                                FailureKind.PERMISSION,
                                context.getString(
                                    R.string
                                        .ds_sdk_grant_camera_and_applicable_local_network_permission_before,
                                ),
                                requiredPermissions = missingPermissions,
                            ),
                        )
                    }
                    if (
                        context
                            .getSystemService(android.app.KeyguardManager::class.java)
                            .isKeyguardLocked ||
                            !context
                                .getSystemService(android.os.PowerManager::class.java)
                                .isInteractive
                    ) {
                        return@withLock fail(
                            CallFailure(
                                FailureKind.PERMISSION,
                                context.getString(
                                    R.string
                                        .ds_sdk_open_and_unlock_the_call_ui_before_starting_media,
                                ),
                                requiresVisibleUnlockedUi = true,
                            ),
                        )
                    }
                }
                if (command == CallCommand.Register)
                    _setup.update { it.copy(initialization = InitializationStatus.INITIALIZING) }
                when (command) {
                    is CallCommand.SetNextCallCodecs ->
                        uiRepository.setNextCallCodecs(command.preferences)
                    is CallCommand.SwitchCodecs -> uiRepository.switchCodecs(command.preferences)
                    CallCommand.Register -> uiRepository.initialize()
                    CallCommand.ResetCode -> uiRepository.resetCode()
                    is CallCommand.Request -> {
                        uiRepository.configureMode(command.mode)
                        uiRepository.configureMicrophone(command.microphone)
                        uiRepository.requestConnection(command.code)
                    }
                    is CallCommand.Accept -> {
                        uiRepository.configureMicrophone(command.microphone)
                        uiRepository.approveConnection()
                    }
                    CallCommand.Decline -> uiRepository.rejectRequest(requestAtDispatch)
                    CallCommand.Cancel -> uiRepository.cancelRequest(requestAtDispatch)
                    CallCommand.Hangup -> uiRepository.disconnect()
                    is CallCommand.Mute -> uiRepository.setMuted(command.muted)
                    CallCommand.FlipCamera -> uiRepository.switchCamera()
                    is CallCommand.AudioOutput -> uiRepository.selectAudioOutput(command.id)
                    CallCommand.RefreshAudioOutputs -> uiRepository.refreshAudioOutputs()
                    is CallCommand.ReviewIncoming ->
                        uiRepository.reviewNotification(command.requestId)
                }
                if (command == CallCommand.Register)
                    _setup.update {
                        it.copy(
                            initialization =
                                if (state.value.ready) InitializationStatus.READY
                                else InitializationStatus.FAILED,
                        )
                    }
                state.value.failure?.let { fail(it) } ?: CallResult.Success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (command == CallCommand.Register)
                    _setup.update { it.copy(initialization = InitializationStatus.FAILED) }
                fail(error.callFailure(context.resources))
            }
        }
    }

    private fun fail(error: CallFailure): CallResult.Failure {
        uiRepository.reportFailure(error)
        _events.tryEmit(CallEvent.Failed(error))
        return CallResult.Failure(error)
    }

    /**
     * Regenerate the device code using the configured admission provider. Observe state.device for
     * the replacement code. The provider revokes the old code and pending incoming requests; an
     * approved call continues. Requires connectivity. A failure leaves the last locally confirmed
     * code visible; it does not imply server rollback.
     */
    suspend fun regenerateCode(): CallResult =
        if (!isActivated)
            CallResult.Failure(CallFailure(FailureKind.UNAVAILABLE, "Calling is inactive."))
        else execute(CallCommand.ResetCode)

    private var alertHostVisible = false

    /**
     * Does not start capture: approval, permissions and visible unlocked call UI are also required.
     */
    fun setVisible(visible: Boolean) {
        if (disposed) return
        uiRepository.setVisible(visible)
        val resumed = visible && !alertHostVisible
        alertHostVisible = visible
        if (resumed)
            scope.launch {
                // Returning from notification settings is an event, not a polling trigger.
                // A pending request whose first alert was blocked must be eligible to alert now.
                (state.value.session as? StreamSessionState.IncomingRequest)?.request?.let {
                    try {
                        validateIncoming(it.requestId)?.let { verified ->
                            com.epiccrown.directstream.incoming.IncomingCalls.showValidated(
                                context,
                                this@DirectStreamClient,
                                verified,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        timber.log.Timber.tag("Push")
                            .w(
                                "Incoming alert resume validation failed (%s)",
                                error.javaClass.simpleName,
                            )
                    }
                }
                if (activation.getBoolean("cleanup", false)) retryRegistrationCleanup()
                else if (isActivated && _setup.value.push != PushReadiness.Registered)
                    activation.getString("push-token", null)?.let { updatePushToken(it) }
            }
    }

    fun setCallScreenVisible(visible: Boolean) {
        if (!disposed) uiRepository.setCallScreenVisible(visible)
    }

    fun configureMicrophone(allowed: Boolean) {
        if (!disposed) uiRepository.configureMicrophone(allowed)
    }

    suspend fun refreshPushToken(token: String): CallResult = commands.withLock {
        if (!isActivated)
            return@withLock CallResult.Failure(
                CallFailure(FailureKind.UNAVAILABLE, "Calling is inactive."),
            )
        if (disposed)
            return@withLock fail(
                CallFailure(
                    FailureKind.DISPOSED,
                    context.getString(R.string.ds_sdk_client_has_been_disposed),
                ),
            )
        try {
            (configuration.provider.push ?: throw BackendException(501, "Push is not configured."))
                .registerToken(token)
            CallResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fail(error.callFailure(context.resources))
        }
    }

    /** Authenticated lookup only; does not navigate, record history, approve or start media. */
    suspend fun validateIncoming(requestId: String): PairingRequest? {
        if (!isActivated || disposed || !requestId.matches(Regex("[a-zA-Z0-9_-]{16,100}")))
            return null
        val request = withTimeout(6_000) { configuration.provider.admission.status(requestId, 3) }
        return request.takeIf {
            isActivated &&
                !disposed &&
                it.role == Role.SENDER &&
                it.status == "pending" &&
                it.expiresAt > System.currentTimeMillis()
        }
    }

    fun returnIntent(): Intent =
        configuration.returnActivity?.let { Intent(context, it) }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error(
                context.getString(
                    R.string.ds_sdk_configure_returnactivity_for_notification_actions,
                ),
            )

    /**
     * Call after removing all SDK video composables. Never dispose on rotation or ordinary
     * backgrounding.
     */
    suspend fun dispose() = commands.withLock {
        if (disposed) return@withLock
        disposed = true
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            try {
                try {
                    uiRepository.dispose()
                } finally {
                    try {
                        (video as WebRtcVideoRenderer).releaseAll()
                        rtc.dispose()
                    } finally {
                        withTimeout(3_000) { configuration.provider.dispose() }
                    }
                }
            } finally {
                scope.cancel()
                _setup.update {
                    it.copy(
                        initialization = InitializationStatus.DISPOSED,
                        remoteCleanupPending =
                            context
                                .getSharedPreferences("stream-session", Context.MODE_PRIVATE)
                                .let { prefs ->
                                    prefs.contains("room") || prefs.contains("pending-request")
                                } || activation.getBoolean("cleanup", false),
                    )
                }
                synchronized(Companion) {
                    if (instance === this@DirectStreamClient) instance = null
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: DirectStreamClient? = null

        @Synchronized
        fun initialize(
            context: Context,
            configuration: DirectStreamConfiguration,
        ): DirectStreamClient {
            val normalized =
                configuration.copy(
                    participantName = ParticipantName.normalize(configuration.participantName),
                )
            instance?.let { existing ->
                require(
                    existing.configuration.provider.configurationKey ==
                        configuration.provider.configurationKey &&
                        existing.configuration.copy(provider = configuration.provider) ==
                            normalized,
                ) {
                    "DirectStream SDK is already initialized with a different configuration."
                }
                return existing
            }
            return DirectStreamClient(context, normalized).also { instance = it }
        }

        fun currentOrNull(): DirectStreamClient? = instance

        fun current(): DirectStreamClient =
            checkNotNull(instance) { "Initialize DirectStream SDK in Application.onCreate first." }
    }
}
