package com.epiccrown.directstream

import android.content.Context
import android.content.Intent
import com.epiccrown.directstream.api.CallServiceBridge
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class ForegroundCallBridge(private val context: Context) : CallServiceBridge {
    var token: String? = null
        private set

    private var ready: CompletableDeferred<Unit>? = null

    override suspend fun start(microphone: Boolean) {
        check(token == null)
        val id = UUID.randomUUID().toString()
        token = id
        val pending = CompletableDeferred<Unit>()
        ready = pending
        try {
            context.startForegroundService(
                Intent(context, CallService::class.java)
                    .putExtra("token", id)
                    .putExtra("microphone", microphone),
            )
            withTimeout(5_000) { pending.await() }
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    fun acknowledge(id: String, error: Throwable? = null) {
        if (token != id) return
        if (error == null) ready?.complete(Unit) else ready?.completeExceptionally(error)
    }

    override fun stop() {
        token = null
        ready?.cancel()
        ready = null
        context.stopService(Intent(context, CallService::class.java))
    }
}
