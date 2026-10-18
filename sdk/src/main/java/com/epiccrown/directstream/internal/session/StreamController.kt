package com.epiccrown.directstream.internal.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.epiccrown.directstream.R
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.api.BackendException
import com.epiccrown.directstream.api.CallAttempt
import com.epiccrown.directstream.api.CallDirection
import com.epiccrown.directstream.api.CallFailure
import com.epiccrown.directstream.api.CallMediaState
import com.epiccrown.directstream.api.CallOutcome
import com.epiccrown.directstream.api.CallProvider
import com.epiccrown.directstream.api.CallServiceBridge
import com.epiccrown.directstream.api.CallSnapshot
import com.epiccrown.directstream.api.DeviceCode
import com.epiccrown.directstream.api.FailureKind
import com.epiccrown.directstream.api.IceServer
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.SignalCandidate
import com.epiccrown.directstream.api.StreamSessionState
import com.epiccrown.directstream.api.StreamStats
import com.epiccrown.directstream.api.TurnConfiguration
import com.epiccrown.directstream.api.callFailure
import com.epiccrown.directstream.internal.media.WebRtcClient
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** Process-owned session; ViewModels only dispatch intents and observe state. */
class StreamController(
    private val context: Context,
    private val provider: CallProvider,
    private val rtc: WebRtcClient,
    private val forceRelay: Boolean = false,
    private val callService: CallServiceBridge,
    private val initialCodecs: CallCodecPreferences = CallCodecPreferences(),
    private val platform: com.epiccrown.directstream.internal.media.AndroidCallPlatform? = null,
    private val relayOverride: RelayProvider? = null,
    private var participantName: String? = null,
    private val showDebugIdentifiers: Boolean = false,
) : StreamRepository {
    private val api = provider.admission
    private val signaling = provider.signaling
    private var networkObserver: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var attempt: CallAttempt? = null
    private val prefs = context.getSharedPreferences("stream-session", Context.MODE_PRIVATE)
    private val _state =
        MutableStateFlow(
            CallSnapshot(
                codecs = CallCodecState(nextCall = initialCodecs),
                showDebugIdentifiers = showDebugIdentifiers,
            ),
        )
    override val state = _state.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var visible = false
    private var admissionJob: Job? = null
    private var reconciliation: Job? = null
    private val failedEventStreams = mutableSetOf<String>()
    private val terminalRequests = linkedSetOf<String>()
    private val discardedRequests = mutableMapOf<String, PairingRequest>()

    private fun retireRequest(id: String) {
        terminalRequests.add(id)
        if (terminalRequests.size > 256) terminalRequests.remove(terminalRequests.first())
    }

    private var sessionJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    @Volatile private var roomId: String? = null
    private var activeRole = Role.RECEIVER
    private var initialized = false
    private val recovery = ServerRecovery()
    @Volatile private var initializationRequested = false
    private var admissionError: String? = null
    private var starting = false
    private var retryRequestId: String? = null
    private var retryCode: String? = null
    private var failureInProgress = false
    private var callMode = CallMode.Video

    override fun configureMode(mode: CallMode) {
        callMode = mode
        _state.update { it.copy(mode = mode) }
    }

    private var microphoneAllowed = false
    private var codecNegotiator: CodecNegotiator? = null
    private var callCodecs = initialCodecs
    private var callScreenVisible = true
    private var established = false
    @Volatile private var ending = false

    init {
        fun updateOnline() {
            scope.launch {
                val network = connectivity.activeNetwork
                val online =
                    connectivity
                        .getNetworkCapabilities(network)
                        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ==
                        true
                val restored = online && !_state.value.online
                _state.update { it.copy(online = online) }
                if (restored) {
                    recovery.wake()
                    startAdmissionEvents()
                    reconcileOnEvent()
                }
            }
        }
        networkObserver =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    recovery.wake()
                    updateOnline()
                }

                override fun onLost(network: Network) {
                    updateOnline()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: android.net.NetworkCapabilities,
                ) {
                    updateOnline()
                }
            }
        connectivity.registerDefaultNetworkCallback(networkObserver!!)
        updateOnline()
        scope.launch {
            state
                .map { it.session.javaClass.simpleName }
                .distinctUntilChanged()
                .collect { name ->
                    Timber.tag("StreamSession").i("State: %s", name)
                }
        }
    }

    override fun reportFailure(failure: CallFailure) {
        _state.update {
            it.copy(
                failure = failure,
                error = failure.message,
                codecs =
                    if (failure.kind == FailureKind.CODEC)
                        it.codecs.copy(failure = failure.codecReason)
                    else it.codecs,
            )
        }
    }

    override suspend fun discoverCodecs() {
        if (_state.value.codecs.capabilities != null) return
        try {
            val capabilities = rtc.codecCapabilities()
            _state.update {
                it.copy(
                    codecs =
                        it.codecs.copy(capabilities = capabilities, capabilitiesFailure = null),
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.update {
                it.copy(
                    codecs =
                        it.codecs.copy(capabilitiesFailure = CodecFailureReason.UNSUPPORTED_LOCAL),
                )
            }
        }
    }

    override suspend fun setNextCallCodecs(preferences: CallCodecPreferences) {
        discoverCodecs()
        val capabilities =
            _state.value.codecs.capabilities
                ?: throw CodecException(CodecFailureReason.UNSUPPORTED_LOCAL)
        CodecSelector.validateLocal(capabilities, preferences)
        _state.update {
            it.copy(codecs = it.codecs.copy(nextCall = preferences), failure = null, error = null)
        }
    }

    override suspend fun switchCodecs(preferences: CallCodecPreferences) =
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            if (!established) throw CodecException(CodecFailureReason.NO_ACTIVE_CALL)
            (codecNegotiator ?: throw CodecException(CodecFailureReason.NO_ACTIVE_CALL)).request(
                preferences,
            )
            _state.update { it.copy(failure = null, error = null) }
        }

    private suspend fun snapshotCodecs() {
        discoverCodecs()
        val capabilities =
            _state.value.codecs.capabilities
                ?: throw CodecException(CodecFailureReason.UNSUPPORTED_LOCAL)
        callCodecs = _state.value.codecs.nextCall
        CodecSelector.validateLocal(capabilities, callCodecs, callMode)
    }

    private suspend fun requestIceRestart() {
        codecNegotiator?.restartIce() ?: rtc.restartIce()
    }

    private suspend fun rememberRequest(request: PairingRequest, track: Boolean = true) {
        if (track) configureMode(request.mode)
        if (request.status in listOf("rejected", "cancelled", "expired"))
            retireRequest(request.requestId)
        val code =
            request.peerCode?.let { runCatching { DeviceCode.normalize(it) }.getOrNull() }
                ?: if (track && request.role == Role.RECEIVER) attempt?.code else null
        val current = attempt?.takeIf { track && it.requestId == request.requestId }
        val outcome =
            when (request.status) {
                "approved" ->
                    if (current?.outcome == CallOutcome.CONNECTED) CallOutcome.CONNECTED
                    else CallOutcome.ACCEPTED
                "rejected" -> CallOutcome.DECLINED
                "cancelled" -> CallOutcome.CANCELLED
                "expired" -> CallOutcome.MISSED
                else ->
                    if (request.role == Role.SENDER) CallOutcome.INCOMING else CallOutcome.CALLING
            }
        val event =
            CallAttempt(
                code,
                request.requestId,
                request.createdAt ?: request.expiresAt - 300_000,
                if (request.role == Role.SENDER) CallDirection.INCOMING else CallDirection.OUTGOING,
                outcome,
                current?.peerUid,
                request.peerName,
            )
        if (track) {
            attempt = event
            _state.update { it.copy(peerDisplayName = request.peerName, peerCode = code) }
        }
        saveAttempt(event)
    }

    private suspend fun saveAttempt(event: CallAttempt) {
        _state.update { it.copy(latestAttempt = event) }
    }

    private suspend fun rememberOutcome(outcome: CallOutcome) {
        attempt = attempt?.copy(outcome = outcome)
        attempt?.let { saveAttempt(it) }
    }

    private suspend fun action(operation: String, block: suspend () -> Unit) = mutex.withLock {
        Timber.tag("StreamSession").d("Starting %s", operation)
        _state.update { it.copy(busy = true, error = null, failure = null) }
        try {
            block()
            Timber.tag("StreamSession").d("Completed %s", operation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag("StreamSession")
                .w(
                    "%s failed (%s; HTTP status=%s)",
                    operation,
                    error.javaClass.simpleName,
                    (error as? BackendException)?.status ?: "n/a",
                )
            _state.update {
                it.copy(
                    failure = error.callFailure(context.resources),
                    error = error.callFailure(context.resources).message,
                )
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    override suspend fun updateParticipantName(participantName: String?, publish: Boolean) {
        mutex.withLock {
            val name = com.epiccrown.directstream.api.ParticipantName.normalize(participantName)
            if (publish && initialized) {
                // Metadata refresh must not run initialization's orphan-room cleanup during a call.
                val device = api.register(name)
                _state.update { it.copy(device = device.copy(activeRoomId = null)) }
            }
            this.participantName = name
        }
    }

    override suspend fun initialize() {
        initializationRequested = true
        try {
            initializeServices()
        } finally {
            recovery.wake()
            startAdmissionEvents()
        }
    }

    private suspend fun initializeServices() =
        action("Initialize") {
            if (initialized || !initializationRequested) return@action
            provider.identity.signIn()
            check(provider.validate().valid) {
                context.getString(
                    com.epiccrown.directstream.R.string
                        .ds_session_check_host_supplied_provider_configuration,
                )
            }
            // A killed process must never silently resume a previously approved camera stream.
            val abandoned = prefs.getString("room", null)
            if (abandoned != null) {
                try {
                    api.end(abandoned)
                } catch (e: BackendException) {
                    if (e.status !in listOf(404, 410)) throw e
                }
                prefs.edit().remove("room").apply()
            }
            prefs.getString("pending-request", null)?.let { id ->
                try {
                    val request = api.status(id, 3)
                    if (request.status == "approved") request.roomId?.let { api.end(it) }
                    else if (request.status == "pending") {
                        if (request.role == Role.SENDER) api.reject(id) else api.cancel(id)
                    }
                    prefs.edit().remove("pending-request").commit()
                } catch (error: BackendException) {
                    if (error.status in listOf(404, 410))
                        prefs.edit().remove("pending-request").commit()
                    else throw error
                }
            }
            val registered = api.register(participantName)
            // Also recover approval committed just before a crash/lost HTTP response, before roomId
            // was saved locally.
            registered.activeRoomId?.let { orphan ->
                try {
                    api.end(orphan)
                } catch (error: BackendException) {
                    if (error.status !in listOf(404, 410)) throw error
                }
            }
            if (!initializationRequested) return@action
            val device = registered.copy(activeRoomId = null)
            initialized = true
            _state.update { it.copy(ready = true, device = device) }
        }

    override suspend fun resetCode() =
        action("Reset device code") {
            val device = api.reset()
            _state.update { it.copy(device = device) }
            val incoming = (_state.value.session as? StreamSessionState.IncomingRequest)?.request
            if (incoming != null) {
                rememberRequest(incoming.copy(status = "cancelled"))
                _state.update { it.copy(session = StreamSessionState.Idle) }
            }
            _state.update {
                it.copy(
                    outcome =
                        context.getString(
                            com.epiccrown.directstream.R.string.ds_session_code_regenerated,
                        ),
                )
            }
        }

    override suspend fun requestConnection(code: String) =
        action("Request connection") {
            check(
                roomId == null &&
                    _state.value.session !is StreamSessionState.IncomingRequest &&
                    _state.value.session !is StreamSessionState.AwaitingApproval,
            ) {
                context.getString(
                    com.epiccrown.directstream.R.string.ds_session_finish_the_current_request_first,
                )
            }
            check(visible) {
                context.getString(
                    com.epiccrown.directstream.R.string.ds_session_open_the_app_to_start_a_call,
                )
            }
            val normalized = DeviceCode.normalize(code)
            check(normalized != _state.value.device?.code) {
                context.getString(
                    com.epiccrown.directstream.R.string.ds_session_enter_the_other_device_s_code,
                )
            }
            snapshotCodecs()
            if (retryCode != normalized) {
                retryCode = normalized
                retryRequestId = UUID.randomUUID().toString()
            }
            attempt =
                CallAttempt(
                    normalized,
                    retryRequestId!!,
                    System.currentTimeMillis(),
                    CallDirection.OUTGOING,
                    CallOutcome.CALLING,
                )
            saveAttempt(attempt!!)
            _state.update {
                it.copy(
                    session = StreamSessionState.RequestingConnection,
                    outcome = null,
                    peerDisplayName = null,
                    peerCode = normalized,
                )
            }
            try {
                val request = api.create(normalized, retryRequestId!!, callMode)
                rememberRequest(request)
                if (!visible) {
                    if (request.status == "approved") request.roomId?.let { api.end(it) }
                    else cancelRequestSafely(request)
                    rememberOutcome(CallOutcome.CANCELLED)
                    _state.update {
                        it.copy(
                            session = StreamSessionState.Idle,
                            outcome =
                                context.getString(
                                    com.epiccrown.directstream.R.string.ds_session_call_cancelled,
                                ),
                        )
                    }
                    return@action
                }
                _state.update { it.copy(session = StreamSessionState.AwaitingApproval(request)) }
                if (request.status == "approved") handleRequest(request)
            } catch (error: Exception) {
                rememberOutcome(CallOutcome.FAILED)
                _state.update { it.copy(session = StreamSessionState.Idle) }
                if (error is BackendException && error.status in listOf(400, 404, 409, 410)) {
                    retryCode = null
                    retryRequestId = null
                }
                throw error
            }
        }

    override suspend fun approveConnection() =
        action("Approve connection") {
            check(visible) {
                context.getString(
                    com.epiccrown.directstream.R.string
                        .ds_session_open_the_app_to_approve_and_start_the_camera,
                )
            }
            val request =
                (_state.value.session as? StreamSessionState.IncomingRequest)?.request
                    ?: error(
                        context.getString(
                            com.epiccrown.directstream.R.string.ds_session_no_incoming_request,
                        ),
                    )
            configureMode(request.mode)
            snapshotCodecs()
            rememberRequest(request)
            val id = api.approve(request.requestId)
            rememberOutcome(CallOutcome.ACCEPTED)
            // Remember authorization before any further asynchronous work so failures/crashes can
            // end it.
            prefs.edit().putString("room", id).apply()
            try {
                check(visible) {
                    context.getString(
                        com.epiccrown.directstream.R.string
                            .ds_session_call_cancelled_because_the_app_was_left_during_setup,
                    )
                }
                beginSession(id, Role.SENDER)
            } catch (error: Exception) {
                if (prefs.getString("room", null) == id) {
                    roomId = id
                    failSession(
                        error.message
                            ?: context.getString(
                                com.epiccrown.directstream.R.string.ds_session_could_not_start_call,
                            ),
                    )
                }
                throw error
            }
        }

    override suspend fun rejectConnection() = rejectRequest(null)

    override suspend fun rejectRequest(requestId: String?) {
        val requested = (_state.value.session as? StreamSessionState.IncomingRequest)?.request
        action("Reject connection") {
            if (
                (requestId ?: requested?.requestId) != null &&
                    roomId != null &&
                    attempt?.requestId == (requestId ?: requested?.requestId)
            ) {
                rtc.sendHangup()
                finishCall(
                    context.getString(
                        com.epiccrown.directstream.R.string.ds_session_call_cancelled,
                    ),
                )
                return@action
            }
            val request =
                requested
                    ?: (_state.value.session as? StreamSessionState.IncomingRequest)?.request
                    ?: return@action
            if (requestId != null && request.requestId != requestId) return@action
            retireRequest(request.requestId)
            discardedRequests[request.requestId] = request
            try {
                api.reject(request.requestId)
                discardedRequests.remove(request.requestId)
            } finally {
                rememberRequest(request.copy(status = "rejected"))
                _state.update {
                    it.copy(
                        session = StreamSessionState.Idle,
                        outcome =
                            context.getString(
                                com.epiccrown.directstream.R.string.ds_session_call_declined,
                            ),
                    )
                }
            }
        }
        if (discardedRequests.isNotEmpty()) reconcileOnEvent()
    }

    override suspend fun cancelConnection() = cancelRequest(null)

    override suspend fun cancelRequest(requestId: String?) {
        val requested = (_state.value.session as? StreamSessionState.AwaitingApproval)?.request
        val expectedId = requestId ?: requested?.requestId ?: retryRequestId
        action("Cancel connection") {
            // Preserve the cancellation intent while an approval/status operation owns the mutex.
            if (expectedId != null && roomId != null && attempt?.requestId == expectedId) {
                rtc.sendHangup()
                finishCall(
                    context.getString(
                        com.epiccrown.directstream.R.string.ds_session_call_cancelled,
                    ),
                )
                return@action
            }
            val request =
                requested
                    ?: (_state.value.session as? StreamSessionState.AwaitingApproval)?.request
                    ?: return@action
            if (expectedId != null && request.requestId != expectedId) return@action
            retireRequest(request.requestId)
            discardedRequests[request.requestId] = request
            try {
                cancelRequestSafely(request)
                discardedRequests.remove(request.requestId)
            } finally {
                rememberRequest(request.copy(status = "cancelled"))
                retryCode = null
                retryRequestId = null
                _state.update {
                    it.copy(
                        session = StreamSessionState.Idle,
                        outcome =
                            context.getString(
                                com.epiccrown.directstream.R.string.ds_session_call_cancelled,
                            ),
                    )
                }
            }
        }
        if (discardedRequests.isNotEmpty()) reconcileOnEvent()
    }

    override fun configureMicrophone(allowed: Boolean) {
        microphoneAllowed = allowed
    }

    private suspend fun cancelRequestSafely(request: PairingRequest) {
        try {
            api.cancel(request.requestId)
        } catch (error: BackendException) {
            if (error.status !in listOf(404, 409, 410)) throw error
            val latest = runCatching { api.status(request.requestId) }.getOrNull()
            if (latest?.status == "approved") latest.roomId?.let { api.end(it) }
        }
    }

    private fun videoVisible(): Boolean =
        visible &&
            callScreenVisible &&
            context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    override fun setVisible(visible: Boolean) {
        Timber.tag("StreamSession").d("Visibility: %s", visible)
        this.visible = visible
        if (visible) {
            recovery.wake()
            startAdmissionEvents()
            reconcileOnEvent()
        } else {
            // Registration remains receptive while this process is alive. Push handles cold starts.
            reconciliation?.cancel()
            reconciliation = null
        }
        scope.launch {
            if (established && roomId != null) updateVideoVisibility()
            else if (!this@StreamController.visible) {
                if (roomId != null || starting) disconnect() else cancelConnection()
            }
        }
    }

    private suspend fun updateVideoVisibility() {
        try {
            rtc.setVideoPaused(!videoVisible())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag("StreamSession")
                .w("Camera visibility update failed (%s)", error.javaClass.simpleName)
            failSession(
                context.getString(
                    com.epiccrown.directstream.R.string
                        .ds_session_camera_unavailable_or_permission_changed_start_a_new,
                ),
            )
        }
    }

    override fun setCallScreenVisible(visible: Boolean) {
        callScreenVisible = visible
        scope.launch { if (established && roomId != null) updateVideoVisibility() }
    }

    override suspend fun setMuted(muted: Boolean) =
        action("Set microphone mute") { rtc.setMuted(muted) }

    override suspend fun selectAudioOutput(id: Int) =
        action("Select audio output") { rtc.selectAudioOutput(id) }

    override suspend fun refreshAudioOutputs() =
        action("Refresh audio outputs") { rtc.refreshAudioOutputs() }

    override fun onCallServiceStopped() {
        scope.launch {
            finishCall(
                context.getString(
                    com.epiccrown.directstream.R.string.ds_session_call_service_stopped,
                ),
            )
        }
    }

    override suspend fun reviewNotification(requestId: String) =
        action("Review incoming call") {
            check(
                _state.value.session !is StreamSessionState.AwaitingApproval &&
                    _state.value.session != StreamSessionState.RequestingConnection,
            ) {
                context.getString(
                    com.epiccrown.directstream.R.string
                        .ds_session_finish_your_current_call_request_first,
                )
            }
            val request = api.status(requestId)
            check(request.role == Role.SENDER) {
                context.getString(
                    com.epiccrown.directstream.R.string
                        .ds_session_this_call_request_is_no_longer_available,
                )
            }
            // Record an authorized missed/terminal notification without reopening it or replacing
            // the live attempt.
            rememberRequest(
                if (request.status == "pending" && request.expiresAt <= System.currentTimeMillis())
                    request.copy(status = "expired")
                else request,
                track = false,
            )
            check(request.status == "pending" && request.expiresAt > System.currentTimeMillis()) {
                context.getString(
                    com.epiccrown.directstream.R.string
                        .ds_session_this_call_request_is_no_longer_available,
                )
            }
            check(roomId == null) {
                context.getString(
                    com.epiccrown.directstream.R.string.ds_session_finish_your_current_call_first,
                )
            }
            rememberRequest(request)
            _state.update {
                it.copy(session = StreamSessionState.IncomingRequest(request), outcome = null)
            }
        }

    override fun refreshPushToken(token: String) {
        scope.launch {
            if (provider.validate().valid)
                runCatching { provider.push?.registerToken(token) }
                    .onSuccess { Timber.tag("Push").d("Refreshed push registration saved") }
                    .onFailure {
                        Timber.tag("Push").w("Push refresh failed (%s)", it.javaClass.simpleName)
                    }
        }
    }

    private fun currentRequest(): PairingRequest? =
        when (val session = _state.value.session) {
            is StreamSessionState.AwaitingApproval -> session.request
            is StreamSessionState.IncomingRequest -> session.request
            else -> null
        }

    private fun eventStreamHealthy(name: String) {
        failedEventStreams.remove(name)
        if (failedEventStreams.isEmpty()) {
            _state.update {
                if (admissionError != null && it.error == admissionError) it.copy(error = null)
                else it
            }
            admissionError = null
        }
    }

    private suspend fun admissionEvents(
        name: String,
        expectedRequest: String? = null,
        block: suspend () -> Unit,
    ) {
        try {
            recovery.retry(name) {
                try {
                    block()
                    eventStreamHealthy(name)
                } catch (error: Exception) {
                    if (ServerRecovery.retryable(error)) {
                        failedEventStreams.add(name)
                        admissionError =
                            context.getString(
                                com.epiccrown.directstream.R.string
                                    .ds_session_connection_unavailable_retrying,
                            )
                        _state.update { it.copy(error = admissionError) }
                    }
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag("AdmissionEvents").w("%s stopped (%s)", name, error.javaClass.simpleName)
            if (
                error is BackendException &&
                    error.status in listOf(404, 410) &&
                    name in listOf("Request events", "Request update")
            ) {
                mutex.withLock {
                    currentRequest()
                        ?.takeIf { it.requestId == expectedRequest }
                        ?.let { handleRequest(it.copy(status = "expired")) }
                }
            } else reportFailure(error.callFailure(context.resources))
        } finally {
            failedEventStreams.remove(name)
        }
    }

    private fun startAdmissionEvents() {
        if (
            !initializationRequested || !provider.validate().valid || admissionJob?.isActive == true
        )
            return
        if (
            !initialized &&
                _state.value.failure?.kind in
                    listOf(
                        FailureKind.CONFIGURATION,
                        FailureKind.UNAUTHORIZED,
                        FailureKind.PERMISSION,
                        FailureKind.INTERNAL,
                        FailureKind.INVALID_INPUT,
                    )
        )
            return
        admissionJob =
            scope.launch(start = CoroutineStart.LAZY) {
                var failures = 0
                while (!initialized) {
                    val observed = recovery.version
                    initializeServices()
                    if (!initialized) {
                        if (_state.value.failure?.kind != FailureKind.UNAVAILABLE) return@launch
                        recovery.pause(ServerRecovery.backoff(++failures), observed)
                    }
                }
                launch {
                    admissionEvents("Incoming events") {
                        combine(
                                api.incomingEvents(),
                                state
                                    .map {
                                        it.session is StreamSessionState.Idle ||
                                            it.session is StreamSessionState.Failed
                                    }
                                    .distinctUntilChanged(),
                            ) { request, eligible ->
                                if (eligible) request else null
                            }
                            .collect { request ->
                                eventStreamHealthy("Incoming events")
                                if (request != null)
                                    mutex.withLock {
                                        if (
                                            roomId == null &&
                                                !starting &&
                                                (_state.value.session is StreamSessionState.Idle ||
                                                    _state.value.session is
                                                        StreamSessionState.Failed)
                                        ) {
                                            if (
                                                request.requestId !in terminalRequests &&
                                                    request.status == "pending" &&
                                                    request.expiresAt > System.currentTimeMillis()
                                            ) {
                                                rememberRequest(request)
                                                _state.update {
                                                    it.copy(
                                                        session =
                                                            StreamSessionState.IncomingRequest(
                                                                request,
                                                            ),
                                                        outcome = null,
                                                    )
                                                }
                                            }
                                        }
                                    }
                            }
                    }
                }
                launch {
                    state
                        .map { currentRequest() }
                        .distinctUntilChangedBy { it?.requestId }
                        .collectLatest { request ->
                            if (request == null) return@collectLatest
                            coroutineScope {
                                launch {
                                    admissionEvents("Request events", request.requestId) {
                                        api.requestEvents(request.requestId).collect { update ->
                                            eventStreamHealthy("Request events")
                                            // Session startup outlives the request subscription it
                                            // replaces.
                                            scope.launch {
                                                admissionEvents(
                                                    "Request update",
                                                    request.requestId,
                                                ) {
                                                    mutex.withLock {
                                                        // Admission remains live in the background:
                                                        // cancellation/expiry must dismiss incoming
                                                        // alerts even while the host is stopped.
                                                        // handleRequest gates media startup on
                                                        // visibility.
                                                        if (
                                                            roomId == null &&
                                                                currentRequest()?.requestId ==
                                                                    request.requestId
                                                        ) {
                                                            // A replayed approval cannot start
                                                            // media without fresh server
                                                            // validation.
                                                            val latest =
                                                                if (
                                                                    update.status == "approved" &&
                                                                        update.role == Role.RECEIVER
                                                                ) {
                                                                    val confirmed =
                                                                        api.status(update.requestId)
                                                                    confirmed
                                                                } else update
                                                            handleRequest(latest)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                launch {
                                    delay(
                                        (request.expiresAt - System.currentTimeMillis())
                                            .coerceAtLeast(0),
                                    )
                                    mutex.withLock {
                                        if (
                                            currentRequest()?.requestId == request.requestId &&
                                                roomId == null
                                        ) {
                                            handleRequest(request.copy(status = "expired"))
                                        }
                                    }
                                }
                            }
                        }
                }
                launch {
                    admissionEvents("Admission transport") {
                        signaling.connectionStates().distinctUntilChanged().collect { connected ->
                            if (connected) {
                                eventStreamHealthy("Admission transport")
                                recovery.wake()
                                reconcileOnEvent()
                            } else {
                                failedEventStreams.add("Admission transport")
                                admissionError =
                                    context.getString(
                                        com.epiccrown.directstream.R.string
                                            .ds_session_connection_unavailable_retrying,
                                    )
                                _state.update { it.copy(error = admissionError) }
                            }
                        }
                    }
                }
                reconcileOnEvent()
                awaitCancellation()
            }
        admissionJob?.start()
    }

    /** One-shot reconciliation after resume/network restoration; no successful-operation timer. */
    private fun reconcileOnEvent() {
        if (!visible || !initialized || reconciliation?.isActive == true) return
        reconciliation =
            scope.launch(start = CoroutineStart.LAZY) {
                admissionEvents("Admission reconciliation") {
                    mutex.withLock {
                        if (!visible) return@withLock
                        for ((id, discarded) in discardedRequests.toMap()) {
                            val latest =
                                try {
                                    api.status(id)
                                } catch (error: BackendException) {
                                    if (error.status in listOf(404, 410)) {
                                        discardedRequests.remove(id)
                                        continue
                                    }
                                    throw error
                                }
                            when (latest.status) {
                                "approved" -> latest.roomId?.let { api.end(it) }
                                "pending" ->
                                    if (discarded.role == Role.SENDER) api.reject(id)
                                    else api.cancel(id)
                            }
                            discardedRequests.remove(id)
                            _state.update {
                                if (it.failure?.kind == FailureKind.UNAVAILABLE)
                                    it.copy(error = null, failure = null)
                                else it
                            }
                        }
                        val active = roomId
                        if (active != null) {
                            try {
                                api.sessionActive(active)
                            } catch (error: BackendException) {
                                if (error.status in listOf(401, 403, 404, 410)) {
                                    finishCall(
                                        context.getString(
                                            R.string
                                                .ds_session_the_other_participant_ended_the_call,
                                        ),
                                    )
                                    return@withLock
                                }
                                throw error
                            }
                            if (
                                _state.value.session is StreamSessionState.Reconnecting &&
                                    activeRole == Role.SENDER
                            )
                                requestIceRestart()
                        } else {
                            prefs.getString("room", null)?.let { abandoned ->
                                try {
                                    api.end(abandoned)
                                } catch (error: BackendException) {
                                    if (error.status !in listOf(404, 410)) throw error
                                }
                                prefs.edit().remove("room").apply()
                            }
                            val request = currentRequest()
                            if (request != null) {
                                val latest =
                                    try {
                                        api.status(request.requestId)
                                    } catch (error: BackendException) {
                                        if (error.status in listOf(404, 410)) {
                                            handleRequest(request.copy(status = "expired"))
                                            return@withLock
                                        }
                                        throw error
                                    }
                                handleRequest(latest)
                            } else if (
                                _state.value.session is StreamSessionState.Idle ||
                                    _state.value.session is StreamSessionState.Failed
                            ) {
                                api.incoming()
                                    ?.takeIf {
                                        it.requestId !in terminalRequests &&
                                            it.status == "pending" &&
                                            it.expiresAt > System.currentTimeMillis()
                                    }
                                    ?.let {
                                        rememberRequest(it)
                                        _state.update { state ->
                                            state.copy(
                                                session = StreamSessionState.IncomingRequest(it),
                                                outcome = null,
                                            )
                                        }
                                    }
                            }
                        }
                        eventStreamHealthy("Admission reconciliation")
                    }
                }
            }
        reconciliation?.start()
    }

    private suspend fun handleRequest(value: PairingRequest) {
        val request =
            if (value.status == "pending" && value.expiresAt <= System.currentTimeMillis())
                value.copy(status = "expired")
            else value
        rememberRequest(request)
        when (request.status) {
            "pending" ->
                _state.update {
                    it.copy(
                        session =
                            if (request.role == Role.SENDER)
                                StreamSessionState.IncomingRequest(request)
                            else StreamSessionState.AwaitingApproval(request),
                    )
                }
            "approved" ->
                if (request.role == Role.RECEIVER && visible && roomId == null && !starting) {
                    val id = checkNotNull(request.roomId)
                    prefs.edit().putString("room", id).apply()
                    try {
                        beginSession(id, Role.RECEIVER)
                    } catch (error: Exception) {
                        if (prefs.getString("room", null) == id) {
                            roomId = id
                            failSession(
                                error.message
                                    ?: context.getString(
                                        com.epiccrown.directstream.R.string
                                            .ds_session_connection_failed,
                                    ),
                            )
                        }
                    }
                }
            else -> {
                retryCode = null
                retryRequestId = null
                _state.update {
                    it.copy(
                        session = StreamSessionState.Idle,
                        outcome =
                            when (request.status) {
                                "rejected" ->
                                    context.getString(
                                        com.epiccrown.directstream.R.string
                                            .ds_session_call_declined,
                                    )
                                "expired" ->
                                    context.getString(
                                        com.epiccrown.directstream.R.string
                                            .ds_session_call_request_expired,
                                    )
                                "cancelled" ->
                                    context.getString(
                                        com.epiccrown.directstream.R.string
                                            .ds_session_call_cancelled,
                                    )
                                else ->
                                    context.getString(
                                        com.epiccrown.directstream.R.string
                                            .ds_session_call_request_ended,
                                    )
                            },
                    )
                }
            }
        }
    }

    private suspend fun beginSession(id: String, role: Role) {
        check(visible) {
            context.getString(
                com.epiccrown.directstream.R.string.ds_session_open_the_app_to_start_streaming,
            )
        }
        if (starting || roomId != null) return
        starting = true
        Timber.tag("StreamSession").i("Starting approved session as %s", role)
        roomId = id
        activeRole = role
        established = false
        ending = false
        _state.update {
            it.copy(session = StreamSessionState.Negotiating, error = null, outcome = null)
        }
        val job = SupervisorJob(scope.coroutineContext[Job])
        sessionJob = job
        val owner = CoroutineScope(job + Dispatchers.Main.immediate)
        sessionScope = owner
        owner.launch {
            try {
                // All entry paths, including idempotent create/approve responses, validate the live
                // lease.
                api.sessionActive(id)
                val room = signaling.getRoom(id)
                configureMode(room.mode)
                val user = provider.identity.signIn()
                check((if (role == Role.SENDER) room.hostUid else room.guestUid) == user) {
                    context.getString(
                        com.epiccrown.directstream.R.string.ds_session_session_not_authorized,
                    )
                }
                attempt =
                    attempt?.copy(
                        peerUid = if (role == Role.SENDER) room.guestUid else room.hostUid,
                    )
                attempt?.let { saveAttempt(it) }
                Timber.tag("StreamSession")
                    .d("Room membership verified; obtaining ICE configuration")
                var credentials =
                    (relayOverride ?: provider.relay)?.iceServers(id)
                        ?: TurnConfiguration(
                            listOf(IceServer(listOf("stun:stun.l.google.com:19302"))),
                            Long.MAX_VALUE,
                        )
                val relayAvailable =
                    credentials.servers.any { server ->
                        server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
                    }
                Timber.tag("WebRtc").i("Relay available: %s", relayAvailable)
                fun routingFailure(): String =
                    if (relayAvailable)
                        context.getString(
                            R.string
                                .ds_session_no_working_webrtc_route_between_devices_check_network,
                        )
                    else
                        context.getString(
                            com.epiccrown.directstream.R.string
                                .ds_session_no_direct_webrtc_route_between_devices_allow_nearby,
                        )
                check(
                    !forceRelay ||
                        credentials.servers.any { server ->
                            server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
                        },
                ) {
                    context.getString(
                        com.epiccrown.directstream.R.string
                            .ds_session_relay_only_testing_needs_explicitly_enabled_turn,
                    )
                }
                suspend fun guarded(block: suspend () -> Unit) {
                    try {
                        recovery.retry("Session server operation", block)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Timber.tag("StreamSession")
                            .w("Session operation failed (%s)", error.javaClass.simpleName)
                        scope.launch {
                            if (roomId != id || ending) return@launch
                            if (error is BackendException && error.status in listOf(404, 410))
                                finishCall(
                                    context.getString(
                                        com.epiccrown.directstream.R.string
                                            .ds_session_the_other_participant_ended_the_call,
                                    ),
                                )
                            else
                                failSession(
                                    error.message
                                        ?: context.getString(
                                            com.epiccrown.directstream.R.string
                                                .ds_session_connection_failed,
                                        ),
                                )
                        }
                    }
                }
                check(visible) {
                    context.getString(
                        com.epiccrown.directstream.R.string
                            .ds_session_open_the_app_to_start_streaming,
                    )
                }
                val microphone =
                    microphoneAllowed &&
                        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                val negotiation =
                    CodecNegotiator(
                        mode = callMode,
                        scope = owner,
                        signaling = signaling,
                        room = id,
                        role = role,
                        localCapabilities =
                            _state.value.codecs.capabilities ?: rtc.codecCapabilities(),
                        initialPreferences = callCodecs,
                        configure = { choice, transaction, rollback ->
                            rtc.configureCodecs(choice, transaction, rollback)
                        },
                        offer = { restart -> rtc.offerCodecs(restart) },
                        applyDescription = rtc::remoteDescription,
                        update = { codecState ->
                            if (roomId == id)
                                _state.update {
                                    it.copy(codecs = codecState.copy(nextCall = it.codecs.nextCall))
                                }
                        },
                        fatal = { error ->
                            scope.launch {
                                if (roomId == id) {
                                    // Room read revocation on a normal hangup can close codec
                                    // listeners before the admission-end event is delivered.
                                    if (established) {
                                        try {
                                            api.sessionActive(id)
                                        } catch (ended: BackendException) {
                                            if (ended.status in listOf(401, 403, 404, 410)) {
                                                if (roomId == id)
                                                    finishCall(
                                                        context.getString(
                                                            R.string
                                                                .ds_session_the_other_participant_ended_the_call,
                                                        ),
                                                    )
                                                return@launch
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            /* Existing transport/end observers still own recovery. */
                                        }
                                    }
                                    if (roomId != id) return@launch
                                    val failure = error.callFailure(context.resources)
                                    failSession(failure.message)
                                    _state.update {
                                        it.copy(
                                            failure = failure,
                                            codecs =
                                                it.codecs.copy(
                                                    phase = CodecPhase.FAILED,
                                                    failure = error.reason,
                                                ),
                                        )
                                    }
                                }
                            }
                        },
                    )
                codecNegotiator = negotiation
                val choice = negotiation.prepareInitial()
                // The capability handshake can wait for a peer; revalidate the live lease before
                // capture.
                api.sessionActive(id)
                check(visible && roomId == id && !ending) { "Call setup was cancelled" }
                owner.launch {
                    rtc.negotiatedCodecs.collect { transaction ->
                        if (transaction != null) negotiation.nativeApplied(transaction)
                    }
                }
                callService.start(microphone)
                platform?.start(
                    owner,
                    role,
                    callMode,
                    _state.value.peerDisplayName ?: _state.value.peerCode?.let(DeviceCode::format),
                ) {
                    scope.launch { if (roomId == id) disconnect() }
                }
                check(visible && roomId == id && !ending) {
                    context.getString(
                        com.epiccrown.directstream.R.string.ds_session_call_cancelled_during_setup,
                    )
                }
                rtc.start(
                    role,
                    credentials.servers,
                    forceRelay,
                    onCandidate = { candidate ->
                        owner.launch { guarded { signaling.publishCandidate(id, role, candidate) } }
                    },
                    onDescription = { description ->
                        owner.launch { negotiation.publishDescription(description) }
                    },
                    onState = { connection ->
                        owner.launch {
                            if (roomId != id || ending) return@launch
                            when (connection) {
                                "CONNECTED" -> {
                                    established = true
                                    rememberOutcome(CallOutcome.CONNECTED)
                                    _state.update {
                                        it.copy(session = StreamSessionState.Connected(role))
                                    }
                                    guarded { rtc.setVideoPaused(!videoVisible()) }
                                }
                                "DISCONNECTED",
                                "FAILED" -> {
                                    _state.update {
                                        it.copy(session = StreamSessionState.Reconnecting)
                                    }
                                    if (role == Role.SENDER) guarded { requestIceRestart() }
                                }
                            }
                        }
                    },
                    mode = callMode,
                    microphone = microphone,
                    manageAudio = platform == null,
                    onHangup = {
                        scope.launch {
                            if (roomId == id)
                                finishCall(
                                    context.getString(
                                        com.epiccrown.directstream.R.string
                                            .ds_session_the_other_participant_ended_the_call,
                                    ),
                                )
                        }
                    },
                    onPermanentFocusLoss = {
                        scope.launch {
                            if (roomId == id)
                                finishCall(
                                    context.getString(
                                        R.string
                                            .ds_session_call_ended_because_another_app_took_audio_focus,
                                    ),
                                )
                        }
                    },
                    allowVideoCapture = {
                        visible &&
                            callScreenVisible &&
                            roomId == id &&
                            !ending &&
                            context
                                .getSystemService(android.os.PowerManager::class.java)
                                .isInteractive &&
                            !context
                                .getSystemService(android.app.KeyguardManager::class.java)
                                .isKeyguardLocked
                    },
                    codecs = choice,
                    negotiationId = negotiation.initialTransactionId(),
                    initialVideoPaused = !videoVisible(),
                )
                negotiation.markMediaReady()
                check(roomId == id && !ending) {
                    context.getString(
                        com.epiccrown.directstream.R.string.ds_session_call_cancelled_during_setup,
                    )
                }
                owner.launch {
                    rtc.media.collect { media -> _state.update { it.copy(media = media) } }
                }
                owner.launch {
                    val seen = mutableSetOf<SignalCandidate>()
                    guarded {
                        signaling.candidates(id, role).collect {
                            if (it !in seen) {
                                rtc.remoteCandidate(it)
                                seen.add(it)
                            }
                        }
                    }
                }
                owner.launch {
                    rtc.stats.collect { stats -> _state.update { it.copy(stats = stats) } }
                }
                owner.launch {
                    guarded {
                        signaling.ended(id).collect { ended ->
                            if (ended)
                                scope.launch {
                                    if (roomId == id)
                                        finishCall(
                                            context.getString(
                                                R.string
                                                    .ds_session_the_other_participant_ended_the_call,
                                            ),
                                        )
                                }
                        }
                    }
                }
                owner.launch { guarded { signaling.presence(id, role, true) } }
                owner.launch {
                    guarded {
                        signaling.connectionStates().distinctUntilChanged().collect { connected ->
                            Timber.tag("Signaling")
                                .i(
                                    "Server transport: %s",
                                    if (connected) "connected" else "disconnected",
                                )
                            if (connected) {
                                recovery.wake()
                                api.sessionActive(id)
                                signaling.presence(id, role, true)
                                if (
                                    _state.value.session is StreamSessionState.Reconnecting &&
                                        role == Role.SENDER
                                )
                                    requestIceRestart()
                            }
                        }
                    }
                }
                Timber.tag("StreamSession")
                    .d("Signaling listeners started; presence registration scheduled")
                val networkCallback =
                    object : ConnectivityManager.NetworkCallback() {
                        private var previous: Network? = null

                        override fun onAvailable(network: Network) {
                            val changed = previous != null && previous != network
                            previous = network
                            if (changed) Timber.tag("WebRtc").i("Default network changed")
                            if (changed && role == Role.SENDER)
                                owner.launch { guarded { requestIceRestart() } }
                        }
                    }
                connectivity.registerDefaultNetworkCallback(networkCallback)
                job.invokeOnCompletion {
                    runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
                }
                owner.launch {
                    guarded {
                        api.sessionEvents(id).collect { status ->
                            if (!status.active || status.expiresAt <= System.currentTimeMillis()) {
                                scope.launch {
                                    if (roomId == id)
                                        finishCall(
                                            context.getString(
                                                R.string
                                                    .ds_session_the_other_participant_ended_the_call,
                                            ),
                                        )
                                }
                            }
                        }
                    }
                }
                owner.launch {
                    delay((room.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                    scope.launch {
                        if (roomId == id)
                            finishCall(
                                context.getString(
                                    R.string
                                        .ds_session_session_time_limit_reached_start_a_new_request,
                                ),
                            )
                    }
                }
                if (relayAvailable)
                    owner.launch {
                        while (isActive && credentials.expiresAt < room.expiresAt) {
                            delay(
                                (credentials.expiresAt - System.currentTimeMillis() - 120_000)
                                    .coerceAtLeast(1_000),
                            )
                            guarded {
                                api.sessionActive(id)
                                credentials =
                                    requireNotNull(relayOverride ?: provider.relay).iceServers(id)
                                rtc.refreshServers(credentials.servers)
                                if (role == Role.SENDER) requestIceRestart()
                            }
                        }
                    }
                owner.launch {
                    val deadline = System.currentTimeMillis() + 45_000
                    var disconnectedAt = 0L
                    while (isActive) {
                        rtc.collectStats()
                        delay(2_000)
                        val session = _state.value.session
                        if (
                            session is StreamSessionState.Negotiating &&
                                System.currentTimeMillis() > deadline
                        ) {
                            Timber.tag("StreamSession")
                                .w(
                                    "Negotiation timed out after 45 s; relay available=%s",
                                    relayAvailable,
                                )
                            scope.launch { failSession(routingFailure()) }
                            break
                        }
                        if (session is StreamSessionState.Reconnecting) {
                            if (disconnectedAt == 0L) disconnectedAt = System.currentTimeMillis()
                            if (System.currentTimeMillis() - disconnectedAt > 30_000) {
                                Timber.tag("StreamSession")
                                    .w(
                                        "Reconnection timed out after 30 s; relay available=%s",
                                        relayAvailable,
                                    )
                                scope.launch { failSession(routingFailure()) }
                                break
                            }
                        } else disconnectedAt = 0L
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val failure = error.callFailure(context.resources)
                scope.launch {
                    if (roomId == id) {
                        failSession(failure.message)
                        _state.update {
                            it.copy(
                                failure = failure,
                                codecs =
                                    it.codecs.copy(
                                        phase = CodecPhase.FAILED,
                                        failure = (error as? CodecException)?.reason,
                                    ),
                            )
                        }
                    }
                }
            } finally {
                if (roomId == id) starting = false
            }
        }
    }

    private suspend fun failSession(message: String) {
        if (failureInProgress || ending || roomId == null) return
        failureInProgress = true
        Timber.tag("StreamSession").w("Session failed; releasing resources")
        try {
            rememberOutcome(CallOutcome.FAILED)
            endLocal()
            _state.update { it.copy(session = StreamSessionState.Failed(message), error = message) }
        } finally {
            failureInProgress = false
        }
    }

    private suspend fun endLocal() {
        Timber.tag("StreamSession").d("Ending local session")
        attempt?.requestId?.let(::retireRequest)
        val id = roomId
        roomId = null
        established = false
        platform?.close()
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            codecNegotiator?.close()
            codecNegotiator = null
            starting = false
            sessionJob?.cancel()
            sessionJob = null
            sessionScope = null
        }
        rtc.close()
        callService.stop()
        if (id != null) {
            runCatching { withTimeout(2_000) { signaling.presence(id, activeRole, false) } }
                .onFailure {
                    Timber.tag("Signaling")
                        .w("Presence cleanup failed (%s)", it.javaClass.simpleName)
                }
            try {
                withTimeout(3_000) { api.end(id) }
                prefs.edit().remove("room").apply()
            } catch (error: BackendException) {
                if (error.status in listOf(404, 410)) prefs.edit().remove("room").apply()
            } catch (error: Exception) {
                Timber.tag("StreamSession")
                    .w("Remote session cleanup deferred (%s)", error.javaClass.simpleName)
                // Retry the persisted end operation on next initialization.
            }
        }
        retryCode = null
        retryRequestId = null
        _state.update {
            it.copy(
                stats = StreamStats(),
                media = CallMediaState(),
                codecs =
                    CallCodecState(
                        capabilities = it.codecs.capabilities,
                        nextCall = it.codecs.nextCall,
                    ),
            )
        }
    }

    private suspend fun finishCall(outcome: String) {
        if (ending || roomId == null) return
        ending = true
        try {
            rememberOutcome(if (established) CallOutcome.COMPLETED else CallOutcome.CANCELLED)
            endLocal()
            _state.update { it.copy(session = StreamSessionState.Idle, outcome = outcome) }
        } finally {
            ending = false
        }
    }

    override suspend fun dismissOutcome() =
        action("Dismiss outcome") {
            if (
                roomId == null &&
                    (_state.value.session == StreamSessionState.Idle ||
                        _state.value.session is StreamSessionState.Failed)
            ) {
                attempt = null
                _state.update {
                    it.copy(
                        session = StreamSessionState.Idle,
                        latestAttempt = null,
                        outcome = null,
                        failure = null,
                        error = null,
                    )
                }
            }
        }

    override suspend fun disconnect() =
        action("Disconnect") {
            rtc.sendHangup()
            finishCall(context.getString(com.epiccrown.directstream.R.string.ds_session_call_ended))
        }

    override suspend fun suspendRegistration() {
        initializationRequested = false
        visible = false
        admissionJob?.cancel()
        admissionJob = null
        reconciliation?.cancel()
        reconciliation = null
        sessionJob?.cancel()
        platform?.close()
        rtc.close()
        callService.stop()
        mutex.withLock {
            if (roomId != null) endLocal()
            else {
                rtc.close()
                callService.stop()
            }
            initialized = false
            participantName = null
            _state.update {
                it.copy(ready = false, device = null, session = StreamSessionState.Idle)
            }
        }
    }

    override suspend fun dispose() {
        initializationRequested = false
        admissionJob?.cancel()
        reconciliation?.cancel()
        mutex.withLock {
            val pending =
                when (val session = _state.value.session) {
                    is StreamSessionState.AwaitingApproval -> session.request
                    is StreamSessionState.IncomingRequest -> session.request
                    else -> null
                }
            pending?.let { prefs.edit().putString("pending-request", it.requestId).commit() }
            try {
                if (roomId != null) {
                    rtc.sendHangup()
                    endLocal()
                } else {
                    platform?.close()
                    rtc.close()
                    callService.stop()
                }
                if (pending != null)
                    runCatching {
                        withTimeout(3_000) {
                            if (pending.role == Role.SENDER) api.reject(pending.requestId)
                            else cancelRequestSafely(pending)
                        }
                        prefs.edit().remove("pending-request").commit()
                    }
            } finally {
                visible = false
                initialized = false
                networkObserver?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
                networkObserver = null
                scope.cancel()
                _state.update { CallSnapshot(showDebugIdentifiers = showDebugIdentifiers) }
            }
        }
    }

    override suspend fun switchCamera() =
        action("Switch camera") { if (roomId != null) rtc.switchCamera() }
}
