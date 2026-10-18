package com.epiccrown.directstream.firebase.internal

import com.epiccrown.directstream.api.BackendException
import com.epiccrown.directstream.api.CallMode
import com.epiccrown.directstream.api.CodecProtocol
import com.epiccrown.directstream.api.CodecSignal
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.Room
import com.epiccrown.directstream.api.SignalCandidate
import com.epiccrown.directstream.api.SignalDescription
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class FirebaseSignaling(private val clients: FirebaseClients) {
    fun connectionStates(): Flow<Boolean> = callbackFlow {
        val ref = clients.database.getReference(".info/connected")
        val listener =
            object : ValueEventListener {
                override fun onDataChange(data: DataSnapshot) {
                    trySend(data.getValue(Boolean::class.java) == true)
                }

                override fun onCancelled(error: DatabaseError) {
                    close(error.providerFailure())
                }
            }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private fun room(id: String) = clients.database.getReference("rooms").child(id)

    suspend fun getRoom(id: String): Room {
        Timber.tag("Signaling").d("Reading approved room metadata")
        val data = room(id).child("metadata").get().await()
        check(data.child("status").value != "ended") { "This stream has ended." }
        return Room(
            id,
            data.child("hostUid").getValue(String::class.java)!!,
            data.child("guestUid").getValue(String::class.java)!!,
            data.child("expiresAt").getValue(Long::class.java)!!,
            CallMode.valueOf(data.child("mode").getValue(String::class.java) ?: "Video"),
        )
    }

    suspend fun publishDescription(id: String, role: Role, description: SignalDescription) {
        Timber.tag("Signaling")
            .d("Publishing %s description (%d chars)", description.type, description.sdp.length)
        room(id)
            .child("signaling")
            .child(if (role == Role.SENDER) "offer" else "answer")
            .setValue(
                mapOf(
                    "type" to description.type,
                    "sdp" to description.sdp,
                    "negotiationId" to description.negotiationId,
                ),
            )
            .await()
    }

    fun descriptions(id: String, role: Role): Flow<SignalDescription> = callbackFlow {
        Timber.tag("Signaling").d("Subscribing to remote descriptions as %s", role)
        val ref = room(id).child("signaling").child(if (role == Role.SENDER) "answer" else "offer")
        val listener =
            object : ValueEventListener {
                override fun onDataChange(data: DataSnapshot) {
                    val sdp = data.child("sdp").getValue(String::class.java) ?: return
                    val type = data.child("type").getValue(String::class.java) ?: return
                    Timber.tag("Signaling")
                        .d("Received remote %s description (%d chars)", type, sdp.length)
                    trySend(
                        SignalDescription(
                            type,
                            sdp,
                            data.child("negotiationId").getValue(String::class.java),
                        ),
                    )
                }

                override fun onCancelled(error: DatabaseError) {
                    Timber.tag("Signaling").w("Database listener cancelled (code=%d)", error.code)
                    close(error.providerFailure())
                }
            }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun publishCodecSignal(id: String, role: Role, signal: CodecSignal) {
        room(id)
            .child("signaling/codecs")
            .child(if (role == Role.SENDER) "host" else "guest")
            .child("${signal.transactionId}_${signal.kind.name}")
            .setValue(
                mapOf(
                    "version" to CodecProtocol.VERSION,
                    "payload" to CodecProtocol.encode(signal),
                ),
            )
            .await()
    }

    fun codecSignals(id: String, role: Role): Flow<CodecSignal> = callbackFlow {
        val ref =
            room(id).child("signaling/codecs").child(if (role == Role.SENDER) "guest" else "host")
        val listener =
            object : com.google.firebase.database.ChildEventListener {
                private fun receive(data: com.google.firebase.database.DataSnapshot) {
                    try {
                        require(
                            data.child("version").getValue(Int::class.java) ==
                                CodecProtocol.VERSION,
                        )
                        val value = data.child("payload").getValue(String::class.java) ?: return
                        trySend(CodecProtocol.decode(value))
                    } catch (error: Exception) {
                        close(error)
                    }
                }

                override fun onChildAdded(
                    data: com.google.firebase.database.DataSnapshot,
                    previous: String?,
                ) = receive(data)

                override fun onChildChanged(
                    data: com.google.firebase.database.DataSnapshot,
                    previous: String?,
                ) = receive(data)

                override fun onChildRemoved(data: com.google.firebase.database.DataSnapshot) = Unit

                override fun onChildMoved(
                    data: com.google.firebase.database.DataSnapshot,
                    previous: String?,
                ) = Unit

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    close(error.providerFailure())
                }
            }
        ref.addChildEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun publishCandidate(id: String, role: Role, candidate: SignalCandidate) {
        room(id)
            .child("candidates")
            .child(if (role == Role.SENDER) "host" else "guest")
            .push()
            .setValue(
                mapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.candidate,
                ),
            )
            .await()
    }

    fun candidates(id: String, role: Role): Flow<SignalCandidate> = callbackFlow {
        Timber.tag("Signaling").d("Subscribing to remote ICE candidates as %s", role)
        val ref = room(id).child("candidates").child(if (role == Role.SENDER) "guest" else "host")
        val listener =
            object : ChildEventListener {
                override fun onChildAdded(data: DataSnapshot, previousChildName: String?) {
                    val candidate = data.child("candidate").getValue(String::class.java) ?: return
                    val index = data.child("sdpMLineIndex").getValue(Int::class.java) ?: return
                    trySend(
                        SignalCandidate(
                            data.key!!,
                            data.child("sdpMid").getValue(String::class.java),
                            index,
                            candidate,
                        ),
                    )
                }

                override fun onCancelled(error: DatabaseError) {
                    Timber.tag("Signaling").w("Database listener cancelled (code=%d)", error.code)
                    close(error.providerFailure())
                }

                override fun onChildChanged(data: DataSnapshot, previousChildName: String?) = Unit

                override fun onChildRemoved(data: DataSnapshot) = Unit

                override fun onChildMoved(data: DataSnapshot, previousChildName: String?) = Unit
            }
        ref.addChildEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun ended(id: String): Flow<Boolean> = callbackFlow {
        val ref = room(id).child("metadata/status")
        val listener =
            object : ValueEventListener {
                override fun onDataChange(data: DataSnapshot) {
                    trySend(data.value == "ended" || !data.exists())
                }

                override fun onCancelled(error: DatabaseError) {
                    Timber.tag("Signaling").w("Database listener cancelled (code=%d)", error.code)
                    if (error.code == DatabaseError.PERMISSION_DENIED) {
                        trySend(true)
                        close()
                    } else close(error.providerFailure())
                }
            }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun presence(id: String, role: Role, connected: Boolean) {
        Timber.tag("Signaling").d("Setting %s presence: %s", role, connected)
        val ref = room(id).child("presence").child(if (role == Role.SENDER) "host" else "guest")
        if (connected) ref.onDisconnect().setValue(false).await()
        else ref.onDisconnect().cancel().await()
        ref.setValue(connected).await()
    }
}

internal fun DatabaseError.providerFailure(): Exception =
    when (code) {
        DatabaseError.PERMISSION_DENIED -> BackendException(403, "Signaling access denied.")
        DatabaseError.EXPIRED_TOKEN,
        DatabaseError.INVALID_TOKEN -> BackendException(401, "Signaling authentication expired.")
        DatabaseError.DISCONNECTED,
        DatabaseError.NETWORK_ERROR,
        DatabaseError.UNAVAILABLE ->
            java.io.IOException("Signaling connection unavailable.", toException())
        else -> toException()
    }
