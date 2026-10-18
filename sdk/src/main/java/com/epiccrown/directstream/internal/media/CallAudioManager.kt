package com.epiccrown.directstream.internal.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.epiccrown.directstream.R
import com.epiccrown.directstream.api.AudioOutput
import timber.log.Timber

/** Communication routing and focus are owned by the call, never by a screen. */
@Suppress("DEPRECATION")
internal class CallAudioManager(
    private val context: Context,
    private val changed: (AudioOutput?, List<AudioOutput>) -> Unit,
    private val focus: (Int) -> Unit,
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var active = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var previousDevice: AudioDeviceInfo? = null
    private var chosen: Int? = null
    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) {
                refresh()
            }

            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
                refresh()
            }
        }
    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ focus(it) }, Handler(Looper.getMainLooper()))
            .build()
    private var routeListener: Any? = null

    fun start() {
        check(!active)
        previousMode = manager.mode
        previousSpeaker = manager.isSpeakerphoneOn
        if (Build.VERSION.SDK_INT >= 31) previousDevice = manager.communicationDevice
        check(manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio is busy in another app. Try the call again."
        }
        active = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener {
                if (active) publish()
            }
            routeListener = listener
            manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
        }
        refresh()
    }

    private fun devices(): List<AudioDeviceInfo> {
        val bluetoothAllowed =
            Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        val all =
            if (Build.VERSION.SDK_INT >= 31) manager.availableCommunicationDevices
            else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return all.filter { label(it.type) != null && (bluetoothAllowed || !bluetooth(it.type)) }
    }

    private fun bluetooth(type: Int) =
        type in
            listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )

    private fun label(type: Int): String? =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                context.getString(R.string.ds_audio_speakerphone)
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(R.string.ds_audio_earpiece)
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> context.getString(R.string.ds_audio_wired_headset)
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID ->
                context.getString(R.string.ds_audio_bluetooth_headset)
            else -> null
        }

    private fun output(device: AudioDeviceInfo) =
        AudioOutput(device.id, label(device.type)!!, bluetooth(device.type))

    fun refresh() {
        if (!active) return
        val available = devices()
        val device =
            available.firstOrNull { it.id == chosen }
                ?: available.firstOrNull {
                    it.type in
                        listOf(
                            AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                            AudioDeviceInfo.TYPE_USB_HEADSET,
                        )
                }
                ?: available.firstOrNull { bluetooth(it.type) }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (device != null) select(device.id, false) else publish()
    }

    fun select(id: Int, userInitiated: Boolean = true) {
        if (!active) return
        val device = devices().firstOrNull { it.id == id } ?: return
        val accepted =
            if (Build.VERSION.SDK_INT >= 31) manager.setCommunicationDevice(device)
            else {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    manager.startBluetoothSco()
                    manager.isBluetoothScoOn = true
                } else {
                    manager.stopBluetoothSco()
                    manager.isBluetoothScoOn = false
                }
                manager.isSpeakerphoneOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                true
            }
        if (accepted) {
            if (userInitiated) chosen = id
            Timber.tag("WebRtc").d("Audio output selected: %s", label(device.type))
        }
        publish()
    }

    private fun publish() {
        if (!active) return
        val available = devices()
        val current =
            if (Build.VERSION.SDK_INT >= 31) manager.communicationDevice
            else available.firstOrNull { it.id == chosen }
        changed(current?.takeIf { label(it.type) != null }?.let(::output), available.map(::output))
    }

    fun close() {
        if (!active) return
        active = false
        manager.unregisterAudioDeviceCallback(callback)
        if (Build.VERSION.SDK_INT >= 31) {
            (routeListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                manager.removeOnCommunicationDeviceChangedListener(it)
            }
            routeListener = null
            manager.clearCommunicationDevice()
            previousDevice
                ?.takeIf { old -> manager.availableCommunicationDevices.any { it.id == old.id } }
                ?.let { manager.setCommunicationDevice(it) }
        } else {
            manager.stopBluetoothSco()
            manager.isBluetoothScoOn = false
            manager.isSpeakerphoneOn = previousSpeaker
        }
        manager.abandonAudioFocusRequest(focusRequest)
        if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = previousMode
        chosen = null
    }
}
