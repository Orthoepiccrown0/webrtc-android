package com.epiccrown.directstream.incoming

import android.content.Context
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.R
import com.epiccrown.directstream.api.DeviceCode
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.StreamSessionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IncomingCallOptions(
    val appName: String = "DirectStream SDK",
    val notificationIcon: Int = android.R.drawable.presence_video_online,
)

/**
 * Core incoming-call dispatcher. Host FCM/custom push code forwards data; no Firebase messaging
 * service is registered here.
 */
object IncomingCalls {
    internal var options = IncomingCallOptions()
    private val alertLock = Mutex()
    private var presentedRequest: String? = null

    fun install(context: Context, options: IncomingCallOptions = IncomingCallOptions()) {
        this.options = options
        presentedRequest = null
        IncomingCallNotifications.createChannel(context.applicationContext)
    }

    /**
     * True means an authorized request was handled; Android permission/policy can still suppress
     * its alert.
     */
    suspend fun handlePush(context: Context, data: Map<String, String>): Boolean {
        val client = DirectStreamClient.currentOrNull() ?: return false
        if (!client.isActivated) return false
        val id = data["requestId"] ?: return false
        val expiry = data["expiresAt"]?.toLongOrNull()
        if (expiry != null && expiry <= System.currentTimeMillis()) return false
        val request =
            client.validateIncoming(id)
                ?: run {
                    IncomingCallNotifications.cancel(context, id)
                    return false
                }
        if (!client.isActivated) return false
        val session = client.state.value.session
        if (
            session is StreamSessionState.Connected ||
                session is StreamSessionState.Negotiating ||
                session is StreamSessionState.Reconnecting ||
                session is StreamSessionState.AwaitingApproval ||
                session == StreamSessionState.RequestingConnection
        )
            return false
        return showValidated(context, client, request)
    }

    /** Both realtime events and push use this dispatcher after authenticated status validation. */
    internal suspend fun showValidated(
        context: Context,
        client: DirectStreamClient,
        request: PairingRequest,
    ): Boolean = alertLock.withLock {
        if (
            !client.isActivated ||
                DirectStreamClient.currentOrNull() !== client ||
                !IncomingCallNotifications.valid(request)
        )
            return@withLock false
        val session = client.state.value.session
        if (
            session !is StreamSessionState.Idle &&
                session !is StreamSessionState.Failed &&
                (session !is StreamSessionState.IncomingRequest ||
                    session.request.requestId != request.requestId)
        )
            return@withLock false
        if (presentedRequest == request.requestId) return@withLock true
        val name =
            request.peerName
                ?: request.peerCode?.let(DeviceCode::format)
                ?: context.getString(R.string.ds_incoming_caller, options.appName)
        if (IncomingCallNotifications.show(context, request, name))
            presentedRequest = request.requestId
        true
    }

    internal fun clear(context: Context) {
        presentedRequest = null
        IncomingCallNotifications.cancel(context)
    }
}
