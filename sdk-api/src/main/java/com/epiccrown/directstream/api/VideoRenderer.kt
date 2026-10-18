package com.epiccrown.directstream.api

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

enum class VideoStream {
    LOCAL,
    REMOTE,
}

/** Android view boundary: native tracks and EGL are owned by the runtime, never by the host UI. */
interface CallVideoRenderer {
    val localAvailable: StateFlow<Boolean>
    val remoteAvailable: StateFlow<Boolean>
    val frontCamera: StateFlow<Boolean>

    fun createView(context: Context, stream: VideoStream, mirror: Boolean): View

    fun updateView(view: View, mirror: Boolean)

    fun releaseView(view: View)
}
