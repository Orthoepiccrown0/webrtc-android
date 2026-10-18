package com.epiccrown.directstream.testing

import com.epiccrown.directstream.api.*
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-process demonstration/test server. Not a production service or an authorization boundary. */
class InMemoryCallServer(private val clock: () -> Long = System::currentTimeMillis) {
    internal data class Entry(
        val caller: String,
        val target: String,
        var request: PairingRequest,
        val callerName: String? = null,
        val targetName: String? = null,
    )

    internal val mutex = Mutex()
    internal val changes = MutableStateFlow(0L)

    internal fun changed() {
        changes.update { it + 1 }
    }

    internal val names = mutableMapOf<String, String?>()
    internal val codes = mutableMapOf<String, String>()
    internal val requests = mutableMapOf<String, Entry>()
    internal val codecSignals =
        mutableMapOf<Pair<String, Role>, MutableStateFlow<Map<String, CodecSignal>>>()
    internal val rooms = mutableMapOf<String, Room>()
    internal val descriptions =
        mutableMapOf<Pair<String, Role>, MutableSharedFlow<SignalDescription>>()
    internal val candidates = mutableMapOf<Pair<String, Role>, MutableSharedFlow<SignalCandidate>>()
    internal val ended = mutableMapOf<String, MutableStateFlow<Boolean>>()

    internal fun now() = clock()

    internal fun clean() {
        var changed = false
        requests.values
            .filter { it.request.status == "pending" && it.request.expiresAt <= now() }
            .forEach {
                it.request = it.request.copy(status = "expired")
                changed = true
            }
        rooms.values
            .filter { it.expiresAt <= now() && ended.getValue(it.roomId).value == false }
            .forEach {
                ended.getValue(it.roomId).value = true
                changed = true
            }
        if (changed) changed()
    }

    internal fun busy(uid: String) =
        requests.values.any {
            (it.caller == uid || it.target == uid) &&
                (it.request.status == "pending" ||
                    it.request.status == "approved" && ended[it.request.roomId]?.value == false)
        }

    internal fun room(id: String, uid: String): Room {
        val room = rooms[id] ?: throw BackendException(404, "Room unavailable")
        if (uid !in listOf(room.hostUid, room.guestUid))
            throw BackendException(403, "Participant required")
        if (ended.getValue(id).value) throw BackendException(410, "Room ended")
        return room
    }
}

class InMemoryCallProvider(
    private val server: InMemoryCallServer = InMemoryCallServer(),
    private val deviceKey: String = UUID.randomUUID().toString(),
) : CallProvider {
    override val name = "In-memory demo (no cloud)"
    override val configurationKey: Any = server to deviceKey

    override fun validate() = ConfigurationCheck()

    override val identity =
        object : IdentityProvider {
            override suspend fun signIn() = deviceKey
        }

    private fun code() = server.codes.entries.firstOrNull { it.value == deviceKey }?.key

    private fun allocate(): String {
        val random = SecureRandom()
        var next: String
        do {
            next =
                (1..6)
                    .map { DeviceCode.ALPHABET[random.nextInt(DeviceCode.ALPHABET.length)] }
                    .joinToString("")
        } while (next in server.codes)
        server.codes[next] = deviceKey
        return next
    }

    private fun entry(id: String): InMemoryCallServer.Entry {
        val entry = server.requests[id] ?: throw BackendException(404, "Request unavailable")
        if (deviceKey !in listOf(entry.caller, entry.target))
            throw BackendException(403, "Participant required")
        return entry
    }

    private fun response(entry: InMemoryCallServer.Entry) =
        entry.request.copy(
            role = if (entry.target == deviceKey) Role.SENDER else Role.RECEIVER,
            peerName = if (entry.target == deviceKey) entry.callerName else entry.targetName,
            peerCode =
                server.codes.entries
                    .firstOrNull {
                        it.value == if (entry.target == deviceKey) entry.caller else entry.target
                    }
                    ?.key,
        )

    override val admission =
        object : AdmissionProvider {
            override fun incomingEvents(): Flow<PairingRequest?> =
                server.changes
                    .map {
                        server.mutex.withLock {
                            server.clean()
                            server.requests.values
                                .firstOrNull {
                                    it.target == deviceKey && it.request.status == "pending"
                                }
                                ?.let(::response)
                        }
                    }
                    .distinctUntilChanged()

            override fun requestEvents(id: String): Flow<PairingRequest> =
                server.changes
                    .map {
                        server.mutex.withLock {
                            server.clean()
                            response(entry(id))
                        }
                    }
                    .distinctUntilChanged()

            override fun sessionEvents(roomId: String): Flow<SessionStatus> =
                server.changes
                    .map {
                        server.mutex.withLock {
                            server.clean()
                            val room =
                                server.rooms[roomId]
                                    ?: throw BackendException(404, "Room unavailable")
                            if (deviceKey !in listOf(room.hostUid, room.guestUid))
                                throw BackendException(403, "Participant required")
                            SessionStatus(
                                server.ended.getValue(roomId).value == false,
                                room.expiresAt,
                            )
                        }
                    }
                    .distinctUntilChanged()

            override suspend fun register(participantName: String?): Device {
                val name = ParticipantName.normalize(participantName)
                server.mutex.withLock { server.names[deviceKey] = name }
                return register()
            }

            override suspend fun register() =
                server.mutex
                    .withLock {
                        server.clean()
                        Device(
                            code() ?: allocate(),
                            participantName = server.names[deviceKey],
                            activeRoomId =
                                server.rooms.values
                                    .firstOrNull {
                                        deviceKey in listOf(it.hostUid, it.guestUid) &&
                                            server.ended[it.roomId]?.value == false
                                    }
                                    ?.roomId,
                        )
                    }
                    .also { server.changed() }

            override suspend fun reset() =
                server.mutex
                    .withLock {
                        server.codes.entries.removeAll { it.value == deviceKey }
                        server.requests.values
                            .filter {
                                deviceKey in listOf(it.caller, it.target) &&
                                    it.request.status == "pending"
                            }
                            .forEach { it.request = it.request.copy(status = "cancelled") }
                        Device(allocate(), participantName = server.names[deviceKey])
                    }
                    .also { server.changed() }

            override suspend fun revoke() {
                server.mutex.withLock {
                    server.names.remove(deviceKey)
                    server.codes.entries.removeAll { it.value == deviceKey }
                    server.requests.values
                        .filter {
                            deviceKey in listOf(it.caller, it.target) &&
                                it.request.status == "pending"
                        }
                        .forEach { it.request = it.request.copy(status = "cancelled") }
                    server.rooms.values
                        .filter { deviceKey in listOf(it.hostUid, it.guestUid) }
                        .forEach { server.ended[it.roomId]?.value = true }
                    server.changed()
                }
            }

            override suspend fun create(code: String, requestId: String) =
                create(code, requestId, CallMode.Video)

            override suspend fun create(code: String, requestId: String, mode: CallMode) =
                server.mutex
                    .withLock {
                        server.clean()
                        if (code() == null) allocate()
                        val target =
                            server.codes[DeviceCode.normalize(code)]
                                ?: throw BackendException(404, "Device unavailable")
                        if (target == deviceKey) throw BackendException(400, "Self call")
                        server.requests[requestId]?.let {
                            if (
                                it.caller != deviceKey ||
                                    it.target != target ||
                                    it.request.mode != mode
                            )
                                throw BackendException(409, "Request conflict")
                            return@withLock response(it)
                        }
                        if (server.busy(deviceKey) || server.busy(target))
                            throw BackendException(409, "Device busy")
                        val request =
                            PairingRequest(
                                requestId,
                                (100000..999999).random().toString(),
                                server.now() + 300_000,
                                peerCode = code(),
                                createdAt = server.now(),
                                mode = mode,
                            )
                        val entry =
                            InMemoryCallServer.Entry(
                                deviceKey,
                                target,
                                request,
                                server.names[deviceKey],
                                server.names[target],
                            )
                        server.requests[requestId] = entry
                        response(entry)
                    }
                    .also { server.changed() }

            override suspend fun incoming() =
                server.mutex.withLock {
                    server.clean()
                    server.requests.values
                        .firstOrNull { it.target == deviceKey && it.request.status == "pending" }
                        ?.let(::response)
                }

            override suspend fun status(id: String, timeoutSeconds: Long) =
                server.mutex.withLock {
                    server.clean()
                    response(entry(id))
                }

            override suspend fun approve(id: String) =
                server.mutex
                    .withLock {
                        server.clean()
                        val entry = entry(id)
                        if (entry.target != deviceKey)
                            throw BackendException(403, "Receiver approval required")
                        if (
                            entry.request.status == "approved" &&
                                server.ended[entry.request.roomId]?.value == false
                        )
                            return@withLock entry.request.roomId!!
                        if (entry.request.status != "pending")
                            throw BackendException(410, "Request ended")
                        val roomId = UUID.randomUUID().toString()
                        server.rooms[roomId] =
                            Room(
                                roomId,
                                entry.target,
                                entry.caller,
                                server.now() + 3_600_000,
                                entry.request.mode,
                            )
                        server.ended[roomId] = MutableStateFlow(false)
                        Role.entries.forEach { role ->
                            server.codecSignals[roomId to role] = MutableStateFlow(emptyMap())
                            server.descriptions[roomId to role] =
                                MutableSharedFlow(
                                    replay = 1,
                                    onBufferOverflow =
                                        kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
                                )
                            server.candidates[roomId to role] =
                                MutableSharedFlow(
                                    replay = 128,
                                    onBufferOverflow =
                                        kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
                                )
                        }
                        entry.request = entry.request.copy(status = "approved", roomId = roomId)
                        roomId
                    }
                    .also { server.changed() }

            override suspend fun reject(id: String) =
                server.mutex
                    .withLock {
                        server.clean()
                        val entry = entry(id)
                        if (entry.target != deviceKey)
                            throw BackendException(403, "Receiver required")
                        if (entry.request.status == "approved")
                            throw BackendException(409, "Already approved")
                        if (entry.request.status == "pending")
                            entry.request = entry.request.copy(status = "rejected")
                    }
                    .also { server.changed() }

            override suspend fun cancel(id: String) =
                server.mutex
                    .withLock {
                        server.clean()
                        val entry = entry(id)
                        if (entry.caller != deviceKey)
                            throw BackendException(403, "Caller required")
                        if (entry.request.status == "approved")
                            throw BackendException(409, "Already approved")
                        if (entry.request.status == "pending")
                            entry.request = entry.request.copy(status = "cancelled")
                    }
                    .also { server.changed() }

            override suspend fun end(roomId: String) =
                server.mutex
                    .withLock {
                        val room =
                            server.rooms[roomId] ?: throw BackendException(404, "Room unavailable")
                        if (deviceKey !in listOf(room.hostUid, room.guestUid))
                            throw BackendException(403, "Participant required")
                        server.ended.getValue(roomId).value = true
                    }
                    .also { server.changed() }

            override suspend fun sessionActive(roomId: String) {
                server.mutex.withLock {
                    server.clean()
                    server.room(roomId, deviceKey)
                }
                Unit
            }
        }
    override val signaling =
        object : SignalingProvider {
            private fun ownRole(id: String, role: Role) {
                val room = server.room(id, deviceKey)
                if (
                    (role == Role.SENDER && room.hostUid != deviceKey) ||
                        (role == Role.RECEIVER && room.guestUid != deviceKey)
                )
                    throw BackendException(403, "Wrong role")
            }

            override suspend fun getRoom(id: String) =
                server.mutex.withLock {
                    server.clean()
                    server.room(id, deviceKey)
                }

            override suspend fun publishCodecSignal(id: String, role: Role, signal: CodecSignal) {
                CodecProtocol.encode(signal)
                server.mutex.withLock {
                    ownRole(id, role)
                    val source = server.codecSignals.getValue(id to role)
                    source.value =
                        source.value + ("${signal.transactionId}_${signal.kind}" to signal)
                }
            }

            override fun codecSignals(id: String, role: Role): Flow<CodecSignal> = flow {
                val source =
                    server.mutex.withLock {
                        ownRole(id, role)
                        server.codecSignals.getValue(
                            id to if (role == Role.SENDER) Role.RECEIVER else Role.SENDER,
                        )
                    }
                val seen = mutableMapOf<String, CodecSignal>()
                source.collect { rows ->
                    rows.forEach { (key, signal) ->
                        if (seen.put(key, signal) != signal) emit(signal)
                    }
                }
            }

            override suspend fun publishDescription(
                id: String,
                role: Role,
                description: SignalDescription,
            ) {
                server.mutex.withLock {
                    ownRole(id, role)
                    server.descriptions.getValue(id to role).emit(description)
                }
            }

            override fun descriptions(id: String, role: Role): Flow<SignalDescription> = flow {
                val source =
                    server.mutex.withLock {
                        ownRole(id, role)
                        server.descriptions.getValue(
                            id to if (role == Role.SENDER) Role.RECEIVER else Role.SENDER,
                        )
                    }
                emitAll(source)
            }

            override suspend fun publishCandidate(
                id: String,
                role: Role,
                candidate: SignalCandidate,
            ) {
                server.mutex.withLock {
                    ownRole(id, role)
                    server.candidates.getValue(id to role).emit(candidate)
                }
            }

            override fun candidates(id: String, role: Role): Flow<SignalCandidate> = flow {
                val source =
                    server.mutex.withLock {
                        ownRole(id, role)
                        server.candidates.getValue(
                            id to if (role == Role.SENDER) Role.RECEIVER else Role.SENDER,
                        )
                    }
                emitAll(source)
            }

            override fun ended(id: String): Flow<Boolean> = flow {
                val source =
                    server.mutex.withLock {
                        val room =
                            server.rooms[id] ?: throw BackendException(404, "Room unavailable")
                        if (deviceKey !in listOf(room.hostUid, room.guestUid))
                            throw BackendException(403, "Participant required")
                        server.ended.getValue(id)
                    }
                emitAll(source)
            }

            override suspend fun presence(id: String, role: Role, connected: Boolean) {
                server.mutex.withLock { ownRole(id, role) }
                Unit
            }
        }
}
