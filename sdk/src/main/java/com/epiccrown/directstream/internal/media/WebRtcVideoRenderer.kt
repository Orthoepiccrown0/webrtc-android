package com.epiccrown.directstream.internal.media

import android.content.Context
import android.view.View
import com.epiccrown.directstream.api.CallVideoRenderer
import com.epiccrown.directstream.api.VideoStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class WebRtcVideoRenderer(private val rtc: WebRtcClient, scope: CoroutineScope) :
    CallVideoRenderer {
    override val localAvailable =
        rtc.localPreview.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)
    override val remoteAvailable =
        rtc.track.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)
    override val frontCamera = rtc.frontCamera
    private val views = mutableSetOf<View>()
    private val releases = mutableSetOf<kotlinx.coroutines.CompletableDeferred<Unit>>()
    private var disposed = false

    override fun createView(context: Context, stream: VideoStream, mirror: Boolean): View {
        check(!disposed) { "Video renderer has been disposed." }
        val view =
            if (stream == VideoStream.LOCAL)
                PreviewVideoRenderer(context, rtc.eglContext).apply {
                    tag = "outgoing-preview-renderer"
                    setMirror(mirror)
                    rtc.attachSink(this, VideoFeed.LOCAL)
                }
            else
                SurfaceViewRenderer(context).apply {
                    init(rtc.eglContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setMirror(mirror)
                    rtc.attachSink(this, VideoFeed.REMOTE)
                }
        views.add(view)
        return view
    }

    override fun updateView(view: View, mirror: Boolean) {
        when (view) {
            is PreviewVideoRenderer -> view.setMirror(mirror)
            is SurfaceViewRenderer -> view.setMirror(mirror)
        }
    }

    override fun releaseView(view: View) {
        if (!views.remove(view)) return
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        releases.add(ready)
        fun complete() {
            releases.remove(ready)
            ready.complete(Unit)
        }
        when (view) {
            is PreviewVideoRenderer ->
                rtc.detachSink(view) {
                    view.release()
                    complete()
                }
            is SurfaceViewRenderer ->
                rtc.detachSink(view) {
                    view.release()
                    complete()
                }
            else -> complete()
        }
    }

    suspend fun releaseAll() {
        disposed = true
        views.toList().forEach(::releaseView)
        releases.toList().forEach { it.await() }
    }
}
