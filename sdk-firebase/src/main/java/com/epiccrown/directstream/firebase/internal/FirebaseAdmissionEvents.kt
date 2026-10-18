package com.epiccrown.directstream.firebase.internal

import com.epiccrown.directstream.api.BackendException
import com.epiccrown.directstream.api.CallMode
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.SessionStatus
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * No directory reads and no client writes: the Worker atomically publishes each participant's view.
 */
class FirebaseAdmissionEvents(private val clients: FirebaseClients) {
    private fun request(data: DataSnapshot): PairingRequest {
        fun text(key: String) = requireNotNull(data.child(key).getValue(String::class.java))
        return PairingRequest(
            text("requestId"),
            text("verificationValue"),
            requireNotNull(data.child("expiresAt").getValue(Long::class.java)),
            text("status"),
            data.child("roomId").getValue(String::class.java),
            Role.valueOf(text("role")),
            data.child("peerCode").getValue(String::class.java),
            data.child("createdAt").getValue(Long::class.java),
            CallMode.valueOf(data.child("mode").getValue(String::class.java) ?: "Video"),
            data.child("peerName").getValue(String::class.java),
        )
    }

    private fun <T> observe(path: String, parse: (DataSnapshot) -> T): Flow<T> = flow {
        val uid = clients.signIn()
        emitAll(
            callbackFlow {
                val ref = clients.database.getReference("control/events").child(uid).child(path)
                val listener =
                    object : ValueEventListener {
                        override fun onDataChange(data: DataSnapshot) {
                            try {
                                trySend(parse(data))
                            } catch (error: Exception) {
                                close(error)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            close(error.providerFailure())
                        }
                    }
                ref.addValueEventListener(listener)
                awaitClose { ref.removeEventListener(listener) }
            },
        )
    }
        .distinctUntilChanged()

    fun incoming(): Flow<PairingRequest?> =
        observe("incoming") { if (it.exists()) request(it) else null }

    fun request(id: String): Flow<PairingRequest> =
        observe("requests/$id") {
            if (!it.exists()) throw BackendException(404, "Request unavailable.")
            request(it)
        }

    fun session(id: String): Flow<SessionStatus> =
        observe("sessions/$id") {
            if (!it.exists()) throw BackendException(404, "Session unavailable.")
            SessionStatus(
                it.child("active").getValue(Boolean::class.java) == true,
                requireNotNull(it.child("expiresAt").getValue(Long::class.java)),
            )
        }
}
