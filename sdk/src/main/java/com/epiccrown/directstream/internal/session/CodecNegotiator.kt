package com.epiccrown.directstream.internal.session

import com.epiccrown.directstream.api.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** All protocol state is owned by the session scope (Main), not by native callbacks or UI. */
internal class CodecNegotiator(
    private val scope: CoroutineScope,
    private val signaling: SignalingProvider,
    private val room: String,
    private val role: Role,
    private val localCapabilities: CodecCapabilities,
    initialPreferences: CallCodecPreferences,
    private val configure: suspend (CodecSelection, String, Boolean) -> Unit,
    private val offer: suspend (Boolean) -> Unit,
    private val applyDescription: suspend (SignalDescription) -> Unit,
    private val update: (CallCodecState) -> Unit,
    private val fatal: (CodecException) -> Unit,
    private val timeoutMs: Long = 15_000,
    private val mode: CallMode = CallMode.Video,
) {
    private data class Transaction(
        val id: String,
        val proposal: CodecProposal,
        val initial: Boolean,
        var committed: Boolean = false,
        var configured: Boolean = false,
        var localApplied: Boolean = false,
        var remoteApplied: Boolean = false,
        var timeout: Job? = null,
    )

    private val remote = CompletableDeferred<CodecEndpoint>()
    private val initialSelection = CompletableDeferred<CodecSelection>()
    private var localPreferences = initialPreferences
    private var remoteEndpoint: CodecEndpoint? = null
    private var selected: CodecSelection? = null
    private var current: Transaction? = null
    private var pendingRequest: String? = null
    private var requestTimer: Job? = null
    private var recovering: CodecFailureReason? = null
    private var mediaReady = false
    private var closed = false
    private var restartPending = false
    private var lastRestartAt = 0L
    private val retired = linkedSetOf<String>()
    private val seen = mutableMapOf<Pair<String, CodecSignalKind>, CodecSignal>()
    private val ownedJobs = mutableListOf<Job>()
    private var earlyPrepare: CodecSignal? = null
    private val descriptions = mutableMapOf<String, SignalDescription>()
    private var lastDescription: SignalDescription? = null

    private var lastLog: Triple<CodecPhase, CodecSelection?, CodecFailureReason?>? = null

    private fun state(phase: CodecPhase, failure: CodecFailureReason? = null) {
        val key = Triple(phase, selected, failure)
        if (lastLog != key) {
            lastLog = key
            timber.log.Timber.tag("CodecNegotiation")
                .i(
                    "%s: video=%s audio=%s failure=%s",
                    phase,
                    selected?.video?.name ?: "pending",
                    selected?.audio?.name ?: "pending",
                    failure ?: "none",
                )
        }
        update(
            CallCodecState(
                capabilities = localCapabilities,
                active = if (selected != null) localPreferences else null,
                requested =
                    current?.proposal?.let { if (role == Role.SENDER) it.sender else it.receiver }
                        ?: pendingRequest?.let(seenLocalPreferences::get)
                        ?: localPreferences,
                peer = remoteEndpoint,
                selection = selected,
                transactionId = current?.id ?: pendingRequest,
                phase = phase,
                failure = failure,
            ),
        )
    }

    private suspend fun send(signal: CodecSignal) =
        withTimeout(timeoutMs) { signaling.publishCodecSignal(room, role, signal) }

    private fun retire(id: String) {
        retired.add(id)
        if (retired.size > 256) retired.remove(retired.first())
        descriptions.remove(id)
    }

    private fun fail(error: Exception) {
        if (closed || error is CancellationException) return
        val typed =
            error as? CodecException
                ?: CodecException(
                    if (selected == null) CodecFailureReason.PEER_UNAVAILABLE
                    else CodecFailureReason.NEGOTIATION_FAILED,
                )
        initialSelection.completeExceptionally(typed)
        remote.completeExceptionally(typed)
        fatal(typed)
    }

    private fun launch(block: suspend () -> Unit): Job {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (error: Exception) {
                    fail(error)
                }
            }
        ownedJobs.removeAll { it.isCompleted }
        ownedJobs.add(job)
        job.start()
        return job
    }

    suspend fun prepareInitial(): CodecSelection {
        CodecSelector.validateLocal(localCapabilities, localPreferences, mode)
        state(CodecPhase.PREPARING)
        launch {
            signaling.codecSignals(room, role).collect { signal ->
                if (closed || signal.transactionId in retired) return@collect
                val key = signal.transactionId to signal.kind
                if (seen[key] == signal) return@collect
                if (seen.size >= 4096) throw CodecException(CodecFailureReason.PEER_UNAVAILABLE)
                seen[key] = signal
                handle(signal)
            }
        }
        launch {
            signaling.descriptions(room, role).collect { description ->
                val id =
                    description.negotiationId
                        ?: throw CodecException(CodecFailureReason.PEER_UNAVAILABLE)
                if (id in retired || description == lastDescription) return@collect
                if (descriptions.size >= 16 && id !in descriptions) return@collect
                descriptions[id] = description
                drain()
            }
        }
        try {
            return withTimeout(timeoutMs) {
                send(
                    CodecSignal(
                        UUID.randomUUID().toString(),
                        CodecSignalKind.CAPABILITIES,
                        endpoint = CodecEndpoint(localCapabilities, localPreferences, mode),
                    ),
                )

                val endpoint = remote.await()
                if (role == Role.SENDER)
                    begin(UUID.randomUUID().toString(), localPreferences, endpoint.preferences)
                initialSelection.await()
            }
        } catch (error: TimeoutCancellationException) {
            throw CodecException(CodecFailureReason.PEER_UNAVAILABLE)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw error as? CodecException ?: CodecException(CodecFailureReason.PEER_UNAVAILABLE)
        }
    }

    fun markMediaReady() {
        mediaReady = true
        current?.configured = true
        launch { drain() }
    }

    fun publishDescription(description: SignalDescription) {
        val id = description.negotiationId ?: return
        launch {
            if (!closed && current?.id == id && current?.committed == true)
                signaling.publishDescription(room, role, description)
        }
    }

    fun nativeApplied(id: String) = launch {
        val tx = current?.takeIf { it.id == id && it.committed } ?: return@launch
        if (tx.localApplied) return@launch
        tx.localApplied = true
        send(CodecSignal(id, CodecSignalKind.APPLIED))
        maybeComplete(tx)
    }

    fun request(preferences: CallCodecPreferences) {
        if (closed || !mediaReady || selected == null)
            throw CodecException(CodecFailureReason.NO_ACTIVE_CALL)
        if (current != null || pendingRequest != null || recovering != null)
            throw CodecException(CodecFailureReason.BUSY)
        CodecSelector.validateLocal(localCapabilities, preferences, mode)
        val id = UUID.randomUUID().toString()
        pendingRequest = id
        seenLocalPreferences[id] = preferences
        state(CodecPhase.PREPARING)
        requestTimer = scope.launch {
            delay(timeoutMs)
            if (pendingRequest == id && current == null) {
                retire(id)
                pendingRequest = null
                state(CodecPhase.ACTIVE, CodecFailureReason.TIMEOUT)
            }
        }
        launch {
            if (role == Role.SENDER) begin(id, preferences, remoteEndpoint!!.preferences)
            else send(CodecSignal(id, CodecSignalKind.REQUEST, preferences = preferences))
        }
    }

    fun restartIce() {
        if (role != Role.SENDER || closed) return
        if (current != null || pendingRequest != null || !mediaReady || selected == null) {
            restartPending = true
            return
        }
        val now = System.nanoTime() / 1_000_000
        if (now - lastRestartAt < 5_000) return
        lastRestartAt = now
        launch {
            begin(
                UUID.randomUUID().toString(),
                localPreferences,
                remoteEndpoint!!.preferences,
                restart = true,
            )
        }
    }

    private fun endpoints(
        local: CallCodecPreferences,
        peer: CallCodecPreferences,
    ): Pair<CodecEndpoint, CodecEndpoint> {
        val a = CodecEndpoint(localCapabilities, local, mode)
        val b = remoteEndpoint!!.copy(preferences = peer)
        return if (role == Role.SENDER) a to b else b to a
    }

    private suspend fun begin(
        id: String,
        host: CallCodecPreferences,
        guest: CallCodecPreferences,
        restart: Boolean = false,
        rollback: Boolean = false,
    ) {
        if (closed) return
        if (current != null) {
            send(CodecSignal(id, CodecSignalKind.REJECT, failure = CodecFailureReason.BUSY))
            return
        }
        val choice =
            try {
                val (a, b) = endpoints(host, guest)
                CodecSelector.select(a, b)
            } catch (error: CodecException) {
                rejectRequest(id, error.reason)
                return
            }
        val tx =
            Transaction(id, CodecProposal(host, guest, choice, restart, rollback), selected == null)
        current = tx
        pendingRequest = null
        requestTimer?.cancel()
        startTimeout(tx)
        state(if (rollback) CodecPhase.ROLLING_BACK else CodecPhase.PREPARING, recovering)
        send(CodecSignal(id, CodecSignalKind.PREPARE, proposal = tx.proposal))
    }

    private fun startTimeout(tx: Transaction) {
        tx.timeout = scope.launch {
            delay(timeoutMs)
            // Abort in a separate job: abort cancels the transaction timer, and must remain
            // cancellable by session end without cancelling its own Firebase write/rollback.
            if (current === tx)
                this@CodecNegotiator.launch {
                    if (current === tx) abort(tx, CodecFailureReason.TIMEOUT)
                }
        }
    }

    private suspend fun rejectRequest(id: String, reason: CodecFailureReason) {
        retire(id)
        if (pendingRequest == id) {
            pendingRequest = null
            requestTimer?.cancel()
            state(CodecPhase.ACTIVE, reason)
        }
        send(CodecSignal(id, CodecSignalKind.REJECT, failure = reason))
        if (selected == null) throw CodecException(reason)
    }

    private suspend fun handle(signal: CodecSignal) {
        val id = signal.transactionId
        when (signal.kind) {
            CodecSignalKind.CAPABILITIES -> {
                val value = requireNotNull(signal.endpoint)
                if (remoteEndpoint == null) {
                    remoteEndpoint = value
                    remote.complete(value)
                    earlyPrepare?.let {
                        earlyPrepare = null
                        handle(it)
                    }
                }
            }
            CodecSignalKind.REQUEST -> {
                if (
                    role != Role.SENDER ||
                        selected == null ||
                        current != null ||
                        pendingRequest != null ||
                        recovering != null
                ) {
                    rejectRequest(id, CodecFailureReason.BUSY)
                } else begin(id, localPreferences, requireNotNull(signal.preferences))
            }
            CodecSignalKind.PREPARE -> {
                if (role != Role.RECEIVER) return
                if (remoteEndpoint == null) {
                    earlyPrepare = signal
                    return
                }
                val proposal = requireNotNull(signal.proposal)
                if (current != null || (pendingRequest != null && pendingRequest != id)) {
                    rejectRequest(id, CodecFailureReason.BUSY)
                    return
                }
                // A coordinator cannot silently relax this participant's own enforcement.
                val expected =
                    if (pendingRequest == id) seenLocalPreferences[id] else localPreferences
                if (proposal.receiver != expected || (recovering != null && !proposal.rollback)) {
                    rejectRequest(id, CodecFailureReason.INCOMPATIBLE_PEER)
                    return
                }
                val choice =
                    try {
                        CodecSelector.select(
                            CodecEndpoint(
                                remoteEndpoint!!.capabilities,
                                proposal.sender,
                                remoteEndpoint!!.mode,
                            ),
                            CodecEndpoint(localCapabilities, proposal.receiver, mode),
                        )
                    } catch (error: CodecException) {
                        rejectRequest(id, error.reason)
                        return
                    }
                if (choice != proposal.selection) {
                    rejectRequest(id, CodecFailureReason.INCOMPATIBLE_PEER)
                    return
                }
                val tx = Transaction(id, proposal, selected == null)
                current = tx
                pendingRequest = null
                requestTimer?.cancel()
                startTimeout(tx)
                state(
                    if (proposal.rollback) CodecPhase.ROLLING_BACK else CodecPhase.PREPARING,
                    recovering,
                )
                send(CodecSignal(id, CodecSignalKind.READY))
            }
            CodecSignalKind.READY -> {
                val tx = current?.takeIf { it.id == id && role == Role.SENDER } ?: return
                send(CodecSignal(id, CodecSignalKind.COMMIT))
                commit(tx)
            }
            CodecSignalKind.COMMIT ->
                current?.takeIf { it.id == id && role == Role.RECEIVER }?.let { commit(it) }
            CodecSignalKind.APPLIED -> {
                val tx = current?.takeIf { it.id == id && it.committed } ?: return
                tx.remoteApplied = true
                maybeComplete(tx)
            }
            CodecSignalKind.COMPLETE ->
                current
                    ?.takeIf { it.id == id && it.localApplied && role == Role.RECEIVER }
                    ?.let { complete(it) }
            CodecSignalKind.REJECT,
            CodecSignalKind.ABORT -> {
                val reason = signal.failure ?: CodecFailureReason.NEGOTIATION_FAILED
                val tx = current?.takeIf { it.id == id }
                if (tx != null) abort(tx, reason, notify = false)
                else if (pendingRequest == id) {
                    retire(id)
                    pendingRequest = null
                    requestTimer?.cancel()
                    state(CodecPhase.ACTIVE, reason)
                } else {
                    retire(id)
                    if (selected == null) {
                        val failure = CodecException(reason)
                        initialSelection.completeExceptionally(failure)
                        remote.completeExceptionally(failure)
                    }
                }
            }
        }
    }

    private val seenLocalPreferences = mutableMapOf<String, CallCodecPreferences>()

    private suspend fun commit(tx: Transaction) {
        if (tx.committed || current !== tx) return
        tx.committed = true
        state(
            if (tx.proposal.rollback) CodecPhase.ROLLING_BACK else CodecPhase.APPLYING,
            recovering,
        )
        if (tx.initial) initialSelection.complete(tx.proposal.selection)
        else {
            try {
                configure(tx.proposal.selection, tx.id, tx.proposal.rollback)
                tx.configured = true
                drain()
                if (role == Role.SENDER && current === tx) offer(tx.proposal.restartIce)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                abort(tx, CodecFailureReason.NEGOTIATION_FAILED)
            }
        }
    }

    private suspend fun drain() {
        val tx = current ?: return
        if (!mediaReady || !tx.configured || !tx.committed) return
        val description = descriptions.remove(tx.id) ?: return
        if (description == lastDescription) return
        try {
            applyDescription(description)
            lastDescription = description
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            abort(tx, CodecFailureReason.NEGOTIATION_FAILED)
        }
    }

    private suspend fun maybeComplete(tx: Transaction) {
        if (role == Role.SENDER && current === tx && tx.localApplied && tx.remoteApplied) {
            send(CodecSignal(tx.id, CodecSignalKind.COMPLETE))
            complete(tx)
        }
    }

    private fun complete(tx: Transaction) {
        if (current !== tx) return
        selected = tx.proposal.selection
        localPreferences = if (role == Role.SENDER) tx.proposal.sender else tx.proposal.receiver
        remoteEndpoint =
            remoteEndpoint!!.copy(
                preferences = if (role == Role.SENDER) tx.proposal.receiver else tx.proposal.sender,
            )
        tx.timeout?.cancel()
        current = null
        retire(tx.id)
        state(CodecPhase.ACTIVE, recovering)
        recovering = null
        seenLocalPreferences.remove(tx.id)
        if (restartPending) {
            restartPending = false
            restartIce()
        }
    }

    private suspend fun abort(tx: Transaction, reason: CodecFailureReason, notify: Boolean = true) {
        if (current !== tx) return
        tx.timeout?.cancel()
        current = null
        pendingRequest = null
        retire(tx.id)
        if (notify)
            launch {
                // A disconnected signaling transport must not delay local rollback or call cleanup.
                try {
                    withTimeout(2_000) {
                        send(CodecSignal(tx.id, CodecSignalKind.ABORT, failure = reason))
                    }
                } catch (_: TimeoutCancellationException) {} catch (
                    cancelled: CancellationException,) {
                    throw cancelled
                } catch (_: Exception) {}
            }
        if (tx.initial || tx.proposal.rollback) {
            fail(CodecException(reason))
            return
        }
        if (!tx.committed) {
            state(CodecPhase.ACTIVE, reason)
            return
        }
        recovering = reason
        state(CodecPhase.ROLLING_BACK, reason)
        if (role == Role.SENDER)
            begin(
                UUID.randomUUID().toString(),
                localPreferences,
                remoteEndpoint!!.preferences,
                rollback = true,
            )
        else
            scope.launch {
                delay(timeoutMs)
                if (recovering != null && current == null)
                    fail(CodecException(CodecFailureReason.NEGOTIATION_FAILED))
            }
    }

    fun initialTransactionId(): String = requireNotNull(current).id

    fun close() {
        closed = true
        current?.timeout?.cancel()
        requestTimer?.cancel()
        ownedJobs.toList().forEach { it.cancel() }
        ownedJobs.clear()
        initialSelection.cancel()
        remote.cancel()
        current = null
        descriptions.clear()
        seen.clear()
        seenLocalPreferences.clear()
    }
}
