package com.epiccrown.directstream.incoming

import android.app.KeyguardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.api.StreamSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Activity-scoped window integration; removes flags when the validated incoming request ends. */
class IncomingCallPresentation(
    private val activity: ComponentActivity,
    private val client: DirectStreamClient,
) {
    private val locked = MutableStateFlow(false)
    val lockScreenOnly = locked.asStateFlow()

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.state.collect { state ->
                    val request = (state.session as? StreamSessionState.IncomingRequest)?.request
                    val incoming = request != null && IncomingCallNotifications.valid(request)
                    val keyguard = activity.getSystemService(KeyguardManager::class.java)
                    val finish =
                        locked.value &&
                            !incoming &&
                            keyguard.isKeyguardLocked &&
                            (state.session is StreamSessionState.Idle ||
                                state.session is StreamSessionState.Failed)
                    locked.value = incoming && keyguard.isKeyguardLocked
                    activity.setShowWhenLocked(incoming)
                    activity.setTurnScreenOn(incoming)
                    if (incoming)
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (!incoming && state.ready) IncomingCallNotifications.cancel(activity)
                    if (finish) activity.finish()
                }
            }
        }
    }

    fun unlock(completed: (Boolean) -> Unit) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            completed(true)
            return
        }
        keyguard.requestDismissKeyguard(
            activity,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    locked.value = false
                    completed(true)
                }

                override fun onDismissCancelled() {
                    completed(false)
                }

                override fun onDismissError() {
                    completed(false)
                }
            },
        )
    }
}
