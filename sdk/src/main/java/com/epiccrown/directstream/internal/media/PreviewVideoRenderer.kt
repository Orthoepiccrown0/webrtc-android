package com.epiccrown.directstream.internal.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import timber.log.Timber

/**
 * A composited preview: rounded clipping and UI overlays work without a separate SurfaceView layer.
 */
class PreviewVideoRenderer(context: Context, sharedContext: EglBase.Context) :
    TextureView(context), VideoSink, TextureView.SurfaceTextureListener {
    private val renderer = EglRenderer("LocalPreview")
    private val hasVideo = AtomicBoolean(false)
    private var firstUpdate = true

    init {
        isOpaque = false
        renderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
    }

    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)

    override fun onFrame(frame: VideoFrame) {
        hasVideo.set(true)
        renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer.setLayoutAspectRatio(width.toFloat() / height.coerceAtLeast(1))
        renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer.setLayoutAspectRatio(width.toFloat() / height.coerceAtLeast(1))
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val released = CountDownLatch(1)
        renderer.releaseEglSurface { released.countDown() }
        ThreadUtils.awaitUninterruptibly(released)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (firstUpdate && hasVideo.get()) {
            firstUpdate = false
            Timber.tag("WebRtc")
                .d("Outgoing camera preview rendered: %dx%d (center crop)", width, height)
        }
    }

    /** Detach the track sink before calling this on the main thread. */
    fun release() {
        surfaceTextureListener = null
        renderer.release()
    }
}
