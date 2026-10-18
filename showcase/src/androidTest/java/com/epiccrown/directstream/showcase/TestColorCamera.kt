package com.epiccrown.directstream.showcase

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame

/**
 * A distinct camera image lets one emulator verify both routes without opening one camera twice.
 */
internal class TestColorCamera(private val borderPattern: Boolean = false) : CameraVideoCapturer {
    private var observer: CapturerObserver? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun initialize(
        helper: SurfaceTextureHelper?,
        context: Context?,
        observer: CapturerObserver?,
    ) {
        this.observer = observer
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        observer?.onCapturerStarted(true)
        job = scope.launch {
            while (isActive) {
                val buffer = JavaI420Buffer.allocate(640, 480)
                buffer.dataY.put(
                    ByteArray(buffer.dataY.remaining()) {
                        color(it % buffer.strideY, 82, 145, 41).toByte()
                    },
                )
                buffer.dataU.put(
                    ByteArray(buffer.dataU.remaining()) {
                        color((it % buffer.strideU) * 2, 90, 54, 240).toByte()
                    },
                )
                buffer.dataV.put(
                    ByteArray(buffer.dataV.remaining()) {
                        color((it % buffer.strideV) * 2, 240, 34, 110).toByte()
                    },
                )
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                observer?.onFrameCaptured(frame)
                frame.release()
                delay(33)
            }
        }
    }

    private fun color(x: Int, center: Int, left: Int, right: Int) =
        if (!borderPattern) center
        else
            when {
                x < 100 -> left
                x >= 540 -> right
                else -> center
            }

    override fun stopCapture() {
        runBlocking { job?.cancelAndJoin() }
        job = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit

    override fun dispose() {
        scope.cancel()
    }

    override fun isScreencast() = false

    override fun switchCamera(handler: CameraVideoCapturer.CameraSwitchHandler?) {
        handler?.onCameraSwitchDone(true)
    }

    override fun switchCamera(handler: CameraVideoCapturer.CameraSwitchHandler?, name: String?) {
        handler?.onCameraSwitchDone(true)
    }
}
