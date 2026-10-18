package com.epiccrown.directstream.api

data class MediaMetrics(
    val codec: String? = null,
    val bitrateKbps: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val packetsLost: Long? = null,
    val droppedFrames: Long? = null,
    val frames: Long? = null,
    val audioLevel: Double? = null,
    val codecProfile: String? = null,
    val implementation: String? = null,
)

data class AudioOutput(val id: Int, val label: String, val bluetooth: Boolean = false)

data class CallMediaState(
    val microphoneAvailable: Boolean = false,
    val muted: Boolean = true,
    val interrupted: Boolean = false,
    val systemMuted: Boolean = false,
    val localVideoPaused: Boolean = false,
    val remoteVideoPaused: Boolean = false,
    val remoteMuted: Boolean? = null,
    val output: AudioOutput? = null,
    val outputs: List<AudioOutput> = emptyList(),
)

interface CallServiceBridge {
    suspend fun start(microphone: Boolean)

    fun stop()
}
