package com.epiccrown.directstream.internal.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.epiccrown.directstream.api.*
import com.epiccrown.directstream.api.CallMediaState
import com.epiccrown.directstream.api.ConnectionType
import com.epiccrown.directstream.api.IceServer
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.SignalCandidate
import com.epiccrown.directstream.api.SignalDescription
import com.epiccrown.directstream.api.StreamPermissions
import com.epiccrown.directstream.api.StreamStats
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.AddIceObserver
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber

/** One process-owned engine; all PeerConnection/capturer mutations run on one dispatcher. */
class WebRtcClient(
    private val context: Context,
    private val cameraOverride: CameraVideoCapturer? = null,
) {
    private val dispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "stream-rtc").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    private val callbacks = CoroutineScope(SupervisorJob() + dispatcher)
    private val eglResource = lazy { EglBase.create() }
    private val egl
        get() = eglResource.value

    val eglContext: EglBase.Context
        get() = egl.eglBaseContext

    private val encoder by lazy { DefaultVideoEncoderFactory(eglContext, true, true) }
    private val decoder by lazy { DefaultVideoDecoderFactory(eglContext) }
    private val audioDeviceResource = lazy {
        JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(
                JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
            )
            .setUseHardwareNoiseSuppressor(
                JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported(),
            )
            .setAudioRecordErrorCallback(
                object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(message: String) = audioFailure(true)

                    override fun onWebRtcAudioRecordStartError(
                        code: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                        message: String,
                    ) = audioFailure(true)

                    override fun onWebRtcAudioRecordError(message: String) = audioFailure(true)
                },
            )
            .setAudioTrackErrorCallback(
                object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(message: String) = audioFailure(false)

                    override fun onWebRtcAudioTrackStartError(
                        code: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                        message: String,
                    ) = audioFailure(false)

                    override fun onWebRtcAudioTrackError(message: String) = audioFailure(false)
                },
            )
            .createAudioDeviceModule()
    }
    private val audioDevice
        get() = audioDeviceResource.value

    private val factoryResource = lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }
    private val factory
        get() = factoryResource.value

    private val negotiationMutex = Mutex()
    private var callMode = CallMode.Video
    private var codecSelection: CodecSelection? = null
    private var codecTransaction: String? = null
    private val _negotiatedCodecs = MutableStateFlow<String?>(null)
    val negotiatedCodecs = _negotiatedCodecs.asStateFlow()
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var remoteAudio: AudioTrack? = null
    private var audioRouting: CallAudioManager? = null
    private var control: DataChannel? = null
    private var hangupCallback: () -> Unit = {}
    private var focusLossCallback: () -> Unit = {}
    private val _media = MutableStateFlow(CallMediaState())
    val media = _media.asStateFlow()
    private val statsTracker = RtcStatsTracker()
    private var iceState = "NEW"
    private var peerState = "NEW"
    private var videoProgressFrames = 0L
    private var capturer: CameraVideoCapturer? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var localTrack: VideoTrack? = null
    private val displaySinks = mutableMapOf<VideoFeed, VideoSink>()
    private val _localPreview = MutableStateFlow<VideoTrack?>(null)
    val localPreview = _localPreview.asStateFlow()
    private val _frontCamera = MutableStateFlow(true)
    val frontCamera = _frontCamera.asStateFlow()
    private var generation = 0L
    private var remoteReady = false
    private val buffered = mutableListOf<IceCandidate>()
    private val _track = MutableStateFlow<VideoTrack?>(null)
    val track = _track.asStateFlow()
    private val _stats = MutableStateFlow(StreamStats())
    val stats = _stats.asStateFlow()
    private var candidateCallback: (SignalCandidate) -> Unit = {}
    private var stateCallback: (String) -> Unit = {}
    private var descriptionCallback: (SignalDescription) -> Unit = {}
    private var role = Role.RECEIVER
    private var relayOnly = false
    private val logs = SessionLogGate()
    private val candidateCounts = sortedMapOf<String, Int>()
    private var startedAt = 0L
    private var lastVideoProgressAt = 0L
    private var videoStalled = false

    private fun elapsedMs() = SystemClock.elapsedRealtime() - startedAt

    private fun candidateEvent(event: String, sdp: String) {
        val type = candidateType(sdp)
        val key = "$event/$type"
        candidateCounts[key] = (candidateCounts[key] ?: 0) + 1
        if (logs.once(key)) {
            if (event == "rejected")
                Timber.tag("WebRtc")
                    .w(
                        "Remote ICE candidate rejected: type=%s (further events counted in summaries)",
                        type,
                    )
            else Timber.tag("WebRtc").d("ICE candidate milestone: %s type=%s", event, type)
        }
    }

    private fun config(servers: List<IceServer>) =
        PeerConnection.RTCConfiguration(
                servers.map {
                    PeerConnection.IceServer.builder(it.urls)
                        .setUsername(it.username)
                        .setPassword(it.credential)
                        .createIceServer()
                },
            )
            .apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType =
                    if (relayOnly) PeerConnection.IceTransportsType.RELAY
                    else PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

    suspend fun start(
        role: Role,
        servers: List<IceServer>,
        forceRelay: Boolean,
        onCandidate: (SignalCandidate) -> Unit,
        onState: (String) -> Unit,
        onDescription: (SignalDescription) -> Unit,
        microphone: Boolean = false,
        manageAudio: Boolean = false,
        onHangup: () -> Unit = {},
        onPermanentFocusLoss: () -> Unit = {},
        allowVideoCapture: () -> Boolean = { true },
        codecs: CodecSelection? = null,
        negotiationId: String? = null,
        initialVideoPaused: Boolean = false,
        mode: CallMode = CallMode.Video,
    ) =
        withContext(dispatcher) {
            val missing =
                StreamPermissions.required(Build.VERSION.SDK_INT, role, mode).filter {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
            check(missing.isEmpty()) {
                "Grant Nearby devices / local network permission before connecting. Sending video also requires camera permission."
            }
            val platformState = if (platformOutput != null) _media.value else null
            closeInternal()
            callMode = mode
            codecSelection = codecs
            codecTransaction = negotiationId
            startedAt = SystemClock.elapsedRealtime()
            lastVideoProgressAt = startedAt
            this@WebRtcClient.role = role
            relayOnly = forceRelay
            Timber.tag("WebRtc")
                .i(
                    "Starting %s; ICE policy=%s; server count=%d",
                    role,
                    if (forceRelay) "RELAY" else "ALL",
                    servers.size,
                )
            val mic =
                microphone &&
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
            _media.value =
                CallMediaState(
                    microphoneAvailable = mic,
                    muted = !mic,
                    localVideoPaused = initialVideoPaused,
                    outputs = platformState?.outputs.orEmpty(),
                    output = platformState?.output,
                    systemMuted = platformState?.systemMuted ?: false,
                    interrupted = platformState?.interrupted ?: false,
                )
            hangupCallback = onHangup
            focusLossCallback = onPermanentFocusLoss
            val audioGeneration = generation
            if (manageAudio) {
                audioRouting =
                    CallAudioManager(
                        context,
                        { output, outputs ->
                            callbacks.launch {
                                if (generation == audioGeneration)
                                    _media.value =
                                        _media.value.copy(output = output, outputs = outputs)
                            }
                        },
                        { focus ->
                            callbacks.launch {
                                if (generation != audioGeneration) return@launch
                                if (focus == AudioManager.AUDIOFOCUS_LOSS) focusLossCallback()
                                else {
                                    _media.value =
                                        _media.value.copy(
                                            interrupted = focus != AudioManager.AUDIOFOCUS_GAIN,
                                        )
                                    applyAudioState()
                                }
                            }
                        },
                    )
                audioRouting!!.start()
            }
            if (mic) {
                audioSource = factory.createAudioSource(MediaConstraints())
                localAudio = factory.createAudioTrack("call-audio", audioSource)
                applyAudioState()
            }
            candidateCallback = onCandidate
            stateCallback = onState
            descriptionCallback = onDescription
            val sessionGeneration = generation
            fun dispatch(action: () -> Unit) {
                callbacks.launch { if (generation == sessionGeneration && peer != null) action() }
            }
            val observer =
                object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {
                        dispatch {
                            if (logs.changed("signaling", state))
                                Timber.tag("WebRtc")
                                    .d("Signaling state: %s (+%d ms)", state, elapsedMs())
                        }
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        dispatch {
                            iceState = state.name
                            if (logs.changed("ice", state))
                                Timber.tag("WebRtc")
                                    .i("ICE connection state: %s (+%d ms)", state, elapsedMs())
                        }
                    }

                    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                        dispatch {
                            peerState = state.name
                            if (logs.changed("peer", state)) {
                                Timber.tag("WebRtc")
                                    .i("Peer connection state: %s (+%d ms)", state, elapsedMs())
                                stateCallback(state.name)
                            }
                        }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                        dispatch {
                            if (logs.changed("gathering", state))
                                Timber.tag("WebRtc")
                                    .d("ICE gathering state: %s (+%d ms)", state, elapsedMs())
                        }
                    }

                    override fun onIceCandidate(candidate: IceCandidate) {
                        dispatch {
                            candidateEvent("local", candidate.sdp)
                            candidateCallback(
                                SignalCandidate(
                                    "",
                                    candidate.sdpMid,
                                    candidate.sdpMLineIndex,
                                    candidate.sdp,
                                ),
                            )
                        }
                    }

                    override fun onIceCandidateError(event: IceCandidateErrorEvent) {
                        dispatch {
                            if (
                                logs.due(
                                    "ice-error-${event.errorCode}",
                                    SystemClock.elapsedRealtime(),
                                    15_000,
                                )
                            )
                                Timber.tag("WebRtc")
                                    .w(
                                        "ICE gathering error (code=%d; at most once per code per 15 s)",
                                        event.errorCode,
                                    )
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

                    override fun onAddStream(stream: MediaStream) = Unit

                    override fun onRemoveStream(stream: MediaStream) = Unit

                    override fun onDataChannel(channel: DataChannel) {
                        dispatch { bindControl(channel) }
                    }

                    override fun onRenegotiationNeeded() = Unit

                    override fun onTrack(transceiver: RtpTransceiver) {
                        dispatch {
                            Timber.tag("WebRtc").d("Remote track received")
                            when (val track = transceiver.receiver.track()) {
                                is VideoTrack -> displayTrack(VideoFeed.REMOTE, track)
                                is AudioTrack -> {
                                    remoteAudio = track
                                    applyAudioState()
                                }
                            }
                        }
                    }
                }
            peer =
                checkNotNull(factory.createPeerConnection(config(servers), observer)) {
                    "WebRTC initialization failed."
                }
            peer!!.setBitrate(300_000, 2_000_000, 3_000_000)
            if (mode == CallMode.Video)
                run {
                    if (!initialVideoPaused)
                        check(allowVideoCapture()) {
                            "Open the call screen to start camera capture."
                        }
                    val enumerator = Camera2Enumerator(context)
                    val name =
                        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                            ?: enumerator.deviceNames.first()
                    _frontCamera.value = enumerator.isFrontFacing(name)
                    capturer =
                        cameraOverride
                            ?: checkNotNull(
                                enumerator.createCapturer(
                                    name,
                                    object : CameraVideoCapturer.CameraEventsHandler {
                                        override fun onCameraOpening(cameraName: String) = Unit

                                        override fun onFirstFrameAvailable() {
                                            dispatch {
                                                if (logs.once("camera-first-frame"))
                                                    Timber.tag("WebRtc")
                                                        .i(
                                                            "First camera frame available (+%d ms)",
                                                            elapsedMs(),
                                                        )
                                            }
                                        }

                                        override fun onCameraError(errorDescription: String) {
                                            dispatch {
                                                if (
                                                    logs.due(
                                                        "camera-error",
                                                        SystemClock.elapsedRealtime(),
                                                        15_000,
                                                    )
                                                )
                                                    Timber.tag("WebRtc").w("Camera capture error")
                                            }
                                        }

                                        override fun onCameraDisconnected() {
                                            dispatch {
                                                if (logs.once("camera-disconnected"))
                                                    Timber.tag("WebRtc").w("Camera disconnected")
                                            }
                                        }

                                        override fun onCameraFreezed(errorDescription: String) {
                                            dispatch {
                                                if (
                                                    logs.due(
                                                        "camera-freeze",
                                                        SystemClock.elapsedRealtime(),
                                                        15_000,
                                                    )
                                                )
                                                    Timber.tag("WebRtc").w("Camera capture frozen")
                                            }
                                        }

                                        override fun onCameraClosed() {
                                            dispatch { Timber.tag("WebRtc").d("Camera closed") }
                                        }
                                    },
                                ),
                            ) {
                                "Camera unavailable."
                            }
                    source = factory.createVideoSource(false)
                    helper = SurfaceTextureHelper.create("stream-camera", eglContext)
                    capturer!!.initialize(helper, context, source!!.capturerObserver)
                    if (!initialVideoPaused) {
                        check(allowVideoCapture()) {
                            "Open the call screen to start camera capture."
                        }
                        capturer!!.startCapture(1280, 720, 30)
                        Timber.tag("WebRtc").i("Camera capture requested: 1280x720 at 30 FPS")
                    }
                    localTrack = factory.createVideoTrack("camera-video", source)
                    localTrack?.setEnabled(!initialVideoPaused)
                    displayTrack(VideoFeed.LOCAL, localTrack)
                }
            if (role == Role.SENDER) {
                if (mode == CallMode.Video) {
                    val transceiver =
                        peer!!.addTransceiver(
                            localTrack,
                            RtpTransceiver.RtpTransceiverInit(
                                RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                            ),
                        )
                    preferCodecs(transceiver)
                }
                val audio =
                    if (localAudio != null)
                        peer!!.addTransceiver(
                            localAudio,
                            RtpTransceiver.RtpTransceiverInit(
                                RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                            ),
                        )
                    else
                        peer!!.addTransceiver(
                            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                            RtpTransceiver.RtpTransceiverInit(
                                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
                            ),
                        )
                preferAudio(audio)
                bindControl(
                    peer!!.createDataChannel(
                        "call-control",
                        DataChannel.Init().apply { ordered = true },
                    ),
                )
                offer(false)
            }
        }

    /** Discovery allocates the native factory but never starts recording, playback or a camera. */
    suspend fun codecCapabilities(): CodecCapabilities =
        withContext(dispatcher) {
            val videoEnc =
                factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val videoDec =
                factory
                    .getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                    .codecs
            val audioEnc =
                factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs
            val audioDec =
                factory
                    .getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
                    .codecs
            val hardwareEnc =
                HardwareVideoEncoderFactory(eglContext, true, true)
                    .supportedCodecs
                    .map { it.name.lowercase() }
                    .toSet()
            val hardwareDec =
                HardwareVideoDecoderFactory(eglContext)
                    .supportedCodecs
                    .map { it.name.lowercase() }
                    .toSet()
            val softwareEnc =
                org.webrtc
                    .SoftwareVideoEncoderFactory()
                    .supportedCodecs
                    .map { it.name.lowercase() }
                    .toSet()
            val softwareDec =
                org.webrtc
                    .SoftwareVideoDecoderFactory()
                    .supportedCodecs
                    .map { it.name.lowercase() }
                    .toSet()
            val enc = videoEnc + audioEnc
            val dec = videoDec + audioDec
            fun id(c: org.webrtc.RtpCapabilities.CodecCapability) = c.mimeType.lowercase()
            fun formats(rows: List<org.webrtc.RtpCapabilities.CodecCapability>, mime: String) =
                rows
                    .filter { id(it) == mime }
                    .map {
                        CodecFormat(it.clockRate ?: 90000, it.numChannels, it.parameters.toMap())
                    }
                    .distinct()
            CodecCapabilities(
                (enc + dec)
                    .map(::id)
                    .distinct()
                    .filter { it.substringAfter('/') !in CodecId.auxiliaryCodecs }
                    .map { mime ->
                        val family = mime.substringAfter('/')
                        CodecCapability(
                            CodecId(mime),
                            formats(enc, mime),
                            formats(dec, mime),
                            when {
                                mime.startsWith("audio/") -> CodecImplementation.UNKNOWN
                                family in hardwareEnc -> CodecImplementation.HARDWARE
                                family in softwareEnc -> CodecImplementation.SOFTWARE
                                else -> CodecImplementation.UNKNOWN
                            },
                            when {
                                mime.startsWith("audio/") -> CodecImplementation.UNKNOWN
                                family in hardwareDec -> CodecImplementation.HARDWARE
                                family in softwareDec -> CodecImplementation.SOFTWARE
                                else -> CodecImplementation.UNKNOWN
                            },
                        )
                    },
            )
        }

    private fun preferCodecs(transceiver: RtpTransceiver) = prefer(transceiver, true)

    private fun prefer(transceiver: RtpTransceiver, video: Boolean) {
        val type =
            if (video) MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            else MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        val codecs = factory.getRtpSenderCapabilities(type).codecs
        val selected = if (video) codecSelection?.video else codecSelection?.audio
        val primary =
            if (selected == null) codecs.filter { it.name.lowercase() !in CodecId.auxiliaryCodecs }
            else codecs.filter { it.mimeType.equals(selected.mimeType, true) }
        check(primary.isNotEmpty()) { "Selected codec is unavailable" }
        val payloads = primary.map { it.preferredPayloadType.toString() }.toSet()
        val auxiliary =
            codecs
                .filter { it.name.lowercase() in CodecId.auxiliaryCodecs }
                .filter {
                    !it.name.equals("rtx", true) || it.parameters["apt"] in payloads
                }
        transceiver.setCodecPreferences(primary + auxiliary)
    }

    suspend fun configureCodecs(
        selection: CodecSelection,
        transaction: String,
        rollback: Boolean = false,
    ) =
        withContext(dispatcher) {
            negotiationMutex.withLock {
                if (
                    rollback &&
                        peer != null &&
                        peer!!.signalingState() != PeerConnection.SignalingState.STABLE
                ) {
                    val local =
                        peer!!.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER
                    set(SessionDescription(SessionDescription.Type.ROLLBACK, ""), local)
                }
                codecSelection = selection
                codecTransaction = transaction
                _negotiatedCodecs.value = null
                peer?.transceivers?.forEach {
                    prefer(it, it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                }
            }
        }

    suspend fun offerCodecs(restartIce: Boolean) =
        withContext(dispatcher) {
            negotiationMutex.withLock {
                check(
                    role == Role.SENDER &&
                        peer?.signalingState() == PeerConnection.SignalingState.STABLE,
                )
                offer(restartIce)
            }
        }

    private fun confirmCodecs() {
        val selected = codecSelection ?: return
        val local = peer?.localDescription?.description ?: return
        val remote = peer?.remoteDescription?.description ?: return
        if (peer?.signalingState() != PeerConnection.SignalingState.STABLE) return
        CodecSdp.validate(local, remote, selected)
        _negotiatedCodecs.value = codecTransaction
    }

    private suspend fun create(answer: Boolean, restart: Boolean = false): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val observer =
                object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        if (continuation.isActive) continuation.resume(description)
                    }

                    override fun onCreateFailure(reason: String) {
                        if (continuation.isActive)
                            continuation.resumeWithException(IllegalStateException(reason))
                    }

                    override fun onSetSuccess() = Unit

                    override fun onSetFailure(reason: String) = Unit
                }
            val constraints =
                MediaConstraints().apply {
                    if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                }
            if (answer) peer!!.createAnswer(observer, constraints)
            else peer!!.createOffer(observer, constraints)
        }

    private suspend fun set(description: SessionDescription, local: Boolean): Unit =
        suspendCancellableCoroutine { continuation ->
            val observer =
                object : SdpObserver {
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onSetFailure(reason: String) {
                        if (continuation.isActive)
                            continuation.resumeWithException(IllegalStateException(reason))
                    }

                    override fun onCreateSuccess(description: SessionDescription) = Unit

                    override fun onCreateFailure(reason: String) = Unit
                }
            if (local) peer!!.setLocalDescription(observer, description)
            else peer!!.setRemoteDescription(observer, description)
        }

    private suspend fun offer(restart: Boolean) {
        val description = create(false, restart)
        set(description, true)
        Timber.tag("WebRtc").d("Local offer ready; ICE restart=%s (+%d ms)", restart, elapsedMs())
        descriptionCallback(SignalDescription("offer", description.description, codecTransaction))
    }

    suspend fun remoteDescription(description: SignalDescription) =
        withContext(dispatcher) {
            negotiationMutex.withLock {
                check(peer != null)
                if (codecTransaction != null && description.negotiationId != codecTransaction)
                    return@withLock
                Timber.tag("WebRtc").d("Applying remote %s description", description.type)
                remoteReady = false
                set(
                    SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(description.type),
                        description.sdp,
                    ),
                    false,
                )
                remoteReady = true
                Timber.tag("WebRtc")
                    .d(
                        "Remote %s applied; buffered candidates=%d (+%d ms)",
                        description.type,
                        buffered.size,
                        elapsedMs(),
                    )
                val pendingCandidates = buffered.toList()
                buffered.clear()
                pendingCandidates.forEach { addRemoteCandidate(it) }
                if (description.type == "offer") {
                    peer!!
                        .transceivers
                        .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                        .forEach {
                            check(it.sender.setTrack(localTrack, false)) {
                                "Could not attach local camera to call."
                            }
                            it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                            preferCodecs(it)
                        }
                    peer!!
                        .transceivers
                        .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
                        .forEach {
                            if (localAudio != null)
                                check(it.sender.setTrack(localAudio, false)) {
                                    "Could not attach microphone."
                                }
                            it.direction =
                                if (localAudio != null)
                                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                            preferAudio(it)
                        }
                    val answer = create(true)
                    set(answer, true)
                    Timber.tag("WebRtc").d("Local answer ready (+%d ms)", elapsedMs())
                    descriptionCallback(
                        SignalDescription("answer", answer.description, codecTransaction),
                    )
                }
                confirmCodecs()
            }
        }

    suspend fun remoteCandidate(candidate: SignalCandidate) =
        withContext(dispatcher) {
            val ice = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
            if (peer == null) return@withContext
            if (remoteReady) addRemoteCandidate(ice)
            else {
                buffered.add(ice)
                candidateEvent("buffered", ice.sdp)
            }
            Unit
        }

    private fun candidateType(sdp: String): String =
        Regex("\\btyp\\s+(host|srflx|prflx|relay)\\b").find(sdp)?.groupValues?.get(1) ?: "unknown"

    private fun addRemoteCandidate(candidate: IceCandidate) {
        val currentGeneration = generation
        peer?.addIceCandidate(
            candidate,
            object : AddIceObserver {
                override fun onAddSuccess() {
                    callbacks.launch {
                        if (generation == currentGeneration && peer != null)
                            candidateEvent("accepted", candidate.sdp)
                    }
                }

                override fun onAddFailure(reason: String) {
                    callbacks.launch {
                        if (generation == currentGeneration && peer != null)
                            candidateEvent("rejected", candidate.sdp)
                    }
                }
            },
        )
    }

    suspend fun refreshServers(servers: List<IceServer>) =
        withContext(dispatcher) {
            check(peer?.setConfiguration(config(servers)) == true) {
                "Could not refresh connection credentials."
            }
        }

    suspend fun restartIce() =
        withContext(dispatcher) {
            if (role == Role.SENDER && peer != null) {
                if (logs.due("ice-restart", SystemClock.elapsedRealtime(), 15_000))
                    Timber.tag("WebRtc").i("Restarting ICE")
                offer(true)
            }
        }

    suspend fun switchCamera() =
        withContext(dispatcher) {
            Timber.tag("WebRtc").d("Switching camera")
            val sessionGeneration = generation
            capturer?.switchCamera(
                object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        callbacks.launch {
                            if (generation == sessionGeneration) _frontCamera.value = isFrontCamera
                        }
                    }

                    override fun onCameraSwitchError(errorDescription: String) {
                        callbacks.launch {
                            if (generation == sessionGeneration)
                                Timber.tag("WebRtc").w("Camera switch failed")
                        }
                    }
                },
            )
        }

    suspend fun collectStats() =
        withContext(dispatcher) {
            val currentGeneration = generation
            peer?.getStats { report ->
                callbacks.launch {
                    if (generation == currentGeneration && peer != null) parseStats(report)
                }
            }
        }

    private fun parseStats(report: RTCStatsReport) {
        val now = SystemClock.elapsedRealtime()
        val stats = statsTracker.parse(report, now, elapsedMs(), iceState, peerState)
        val frames = stats.videoIn.frames ?: 0
        if (frames > videoProgressFrames) {
            lastVideoProgressAt = now
            if (logs.once("first-video-rtp"))
                Timber.tag("WebRtc").i("First video RTP received (+%d ms)", elapsedMs())
        }
        videoProgressFrames = frames
        if (
            stats.transport != ConnectionType.UNKNOWN &&
                logs.changed("route", listOf(stats.transport, stats.videoIn.codec ?: "Unknown"))
        )
            Timber.tag("WebRtc")
                .i(
                    "Selected route: %s codec=%s (+%d ms)",
                    stats.transport,
                    stats.codec,
                    elapsedMs(),
                )
        val stalled =
            callMode == CallMode.Video &&
                !_media.value.remoteVideoPaused &&
                peerState == "CONNECTED" &&
                now - lastVideoProgressAt >= 10_000
        if (videoStalled != stalled) {
            videoStalled = stalled
            if (stalled) Timber.tag("WebRtc").w("No video progress for 10 s; ICE=%s", iceState)
            else Timber.tag("WebRtc").i("Video progressing or intentionally paused")
        }
        if (logs.due("stats", now, 15_000))
            Timber.tag("WebRtcStats")
                .d(
                    "%s route=%s video in/out=%s/%s kbps audio in/out=%s/%s kbps ICE=%s muted=%s paused=%s candidates=%s",
                    role,
                    stats.transport,
                    stats.videoIn.bitrateKbps,
                    stats.videoOut.bitrateKbps,
                    stats.audioIn.bitrateKbps,
                    stats.audioOut.bitrateKbps,
                    iceState,
                    _media.value.muted,
                    _media.value.localVideoPaused,
                    candidateCounts.toString(),
                )
        _stats.value = stats
    }

    private fun preferAudio(transceiver: RtpTransceiver) = prefer(transceiver, false)

    private fun audioFailure(recording: Boolean) {
        callbacks.launch {
            if (peer == null) return@launch
            if (
                logs.due(
                    if (recording) "audio-record-error" else "audio-playout-error",
                    SystemClock.elapsedRealtime(),
                    15_000,
                )
            )
                Timber.tag("WebRtc")
                    .w("Audio %s unavailable", if (recording) "capture" else "playout")
            _media.value =
                if (recording) _media.value.copy(microphoneAvailable = false, muted = true)
                else _media.value.copy(interrupted = true)
            applyAudioState()
            sendMediaState()
        }
    }

    private fun applyAudioState() {
        localAudio?.setEnabled(
            !_media.value.muted && !_media.value.systemMuted && !_media.value.interrupted,
        )
        remoteAudio?.setEnabled(!_media.value.interrupted)
    }

    suspend fun setMuted(muted: Boolean) =
        withContext(dispatcher) {
            if (peer == null || !_media.value.microphoneAvailable) return@withContext
            _media.value = _media.value.copy(muted = muted)
            applyAudioState()
            sendMediaState()
            Timber.tag("WebRtc").d("Microphone muted: %s", muted)
        }

    internal var platformOutput: (suspend (Int) -> Unit)? = null

    internal fun platformOutputs(outputs: List<AudioOutput>, current: AudioOutput?) {
        _media.value = _media.value.copy(outputs = outputs, output = current)
    }

    internal suspend fun platformInterrupted(value: Boolean) =
        withContext(dispatcher) {
            _media.value = _media.value.copy(interrupted = value)
            applyAudioState()
        }

    internal suspend fun platformMuted(value: Boolean) =
        withContext(dispatcher) {
            _media.value = _media.value.copy(systemMuted = value)
            applyAudioState()
            sendMediaState()
        }

    suspend fun selectAudioOutput(id: Int) =
        withContext(dispatcher) {
            if (platformOutput != null) platformOutput?.invoke(id) else audioRouting?.select(id)
        }

    suspend fun refreshAudioOutputs() = withContext(dispatcher) { audioRouting?.refresh() }

    suspend fun setVideoPaused(paused: Boolean) =
        withContext(dispatcher) {
            if (
                callMode == CallMode.Audio ||
                    peer == null ||
                    _media.value.localVideoPaused == paused
            )
                return@withContext
            if (paused) {
                localTrack?.setEnabled(false)
                capturer?.stopCapture()
            } else {
                capturer?.startCapture(1280, 720, 30)
                localTrack?.setEnabled(true)
            }
            _media.value = _media.value.copy(localVideoPaused = paused)
            sendMediaState()
            Timber.tag("WebRtc").i("Camera paused: %s", paused)
        }

    private fun bindControl(channel: DataChannel) {
        if (channel.label() != "call-control" || control != null) {
            channel.dispose()
            return
        }
        control = channel
        val current = generation
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    callbacks.launch { if (generation == current) sendMediaState() }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary || buffer.data.remaining() > 512) return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    callbacks.launch {
                        if (generation != current || peer == null) return@launch
                        runCatching {
                            val message = JSONObject(String(bytes, Charsets.UTF_8))
                            if (message.optInt("v") != 1) return@runCatching
                            when (message.optString("type")) {
                                "hangup" -> hangupCallback()
                                "media" ->
                                    if (
                                        message.opt("muted") is Boolean &&
                                            message.opt("videoPaused") is Boolean
                                    )
                                        _media.value =
                                            _media.value.copy(
                                                remoteMuted = message.getBoolean("muted"),
                                                remoteVideoPaused =
                                                    message.getBoolean("videoPaused"),
                                            )
                            }
                        }
                    }
                }
            },
        )
        sendMediaState()
    }

    private fun sendControl(message: JSONObject) {
        if (control?.state() == DataChannel.State.OPEN)
            control?.send(
                DataChannel.Buffer(
                    ByteBuffer.wrap(message.toString().toByteArray(Charsets.UTF_8)),
                    false,
                ),
            )
    }

    private fun sendMediaState() =
        sendControl(
            JSONObject()
                .put("v", 1)
                .put("type", "media")
                .put("muted", _media.value.muted || !_media.value.microphoneAvailable)
                .put("videoPaused", _media.value.localVideoPaused),
        )

    suspend fun sendHangup() =
        withContext(dispatcher) { sendControl(JSONObject().put("v", 1).put("type", "hangup")) }

    private fun displayTrack(feed: VideoFeed, track: VideoTrack?) {
        val flow = if (feed == VideoFeed.LOCAL) _localPreview else _track
        displaySinks[feed]?.let { flow.value?.removeSink(it) }
        flow.value = track
        displaySinks[feed]?.let { track?.addSink(it) }
    }

    fun attachSink(sink: VideoSink, feed: VideoFeed = VideoFeed.REMOTE) {
        callbacks.launch {
            val track = if (feed == VideoFeed.LOCAL) _localPreview.value else _track.value
            displaySinks.put(feed, sink)?.let { track?.removeSink(it) }
            track?.addSink(sink)
        }
    }

    fun detachSink(sink: VideoSink, released: () -> Unit) {
        callbacks.launch {
            VideoFeed.entries.forEach { feed ->
                if (displaySinks[feed] === sink) {
                    (if (feed == VideoFeed.LOCAL) _localPreview.value else _track.value)
                        ?.removeSink(sink)
                    displaySinks.remove(feed)
                }
            }
            withContext(Dispatchers.Main) { released() }
        }
    }

    suspend fun dispose() {
        withContext(dispatcher) {
            closeInternal()
            if (factoryResource.isInitialized()) factory.dispose()
            if (audioDeviceResource.isInitialized()) audioDevice.release()
            if (eglResource.isInitialized()) egl.release()
        }
        callbacks.cancel()
        dispatcher.close()
    }

    suspend fun close() = withContext(dispatcher) { closeInternal() }

    private fun closeInternal() {
        if (peer != null || capturer != null)
            Timber.tag("WebRtc")
                .i(
                    "Releasing session after %d ms; candidates=%s",
                    elapsedMs(),
                    candidateCounts.toString(),
                )
        generation++
        displayTrack(VideoFeed.LOCAL, null)
        displayTrack(VideoFeed.REMOTE, null)
        capturer?.let {
            runCatching { it.stopCapture() }
            it.dispose()
        }
        capturer = null
        _negotiatedCodecs.value = null
        codecTransaction = null
        codecSelection = null
        control?.unregisterObserver()
        control?.close()
        control?.dispose()
        control = null
        peer?.close()
        peer?.dispose()
        peer = null
        localAudio?.dispose()
        localAudio = null
        remoteAudio = null
        audioSource?.dispose()
        audioSource = null
        audioRouting?.close()
        audioRouting = null
        _media.value = CallMediaState()
        statsTracker.reset()
        iceState = "NEW"
        peerState = "NEW"
        videoProgressFrames = 0L
        localTrack?.dispose()
        localTrack = null
        source?.dispose()
        source = null
        helper?.dispose()
        helper = null
        logs.reset()
        candidateCounts.clear()
        videoStalled = false
        remoteReady = false
        buffered.clear()
        _stats.value = StreamStats()
    }
}

enum class VideoFeed {
    LOCAL,
    REMOTE,
}
