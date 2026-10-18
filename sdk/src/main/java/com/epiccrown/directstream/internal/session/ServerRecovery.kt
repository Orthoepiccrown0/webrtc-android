package com.epiccrown.directstream.internal.session

import com.epiccrown.directstream.api.BackendException
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Shared wake-up signal; each waiter observes it independently, including wakes during an
 * operation.
 */
internal class ServerRecovery {
    private val revision = MutableStateFlow(0L)
    val version
        get() = revision.value

    fun wake() {
        revision.update { it + 1 }
    }

    suspend fun pause(milliseconds: Long, observed: Long = version) {
        withTimeoutOrNull(milliseconds) { revision.first { it != observed } }
    }

    suspend fun <T> retry(operation: String, block: suspend () -> T): T {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = version
            try {
                val result = block()
                if (failures > 0) Timber.tag("ServerRecovery").i("%s recovered", operation)
                return result
            } catch (error: Exception) {
                if (!retryable(error)) throw error
                failures++
                if (failures == 1 || failures % 6 == 0)
                    Timber.tag("ServerRecovery")
                        .w("%s unavailable; retrying (%s)", operation, error.javaClass.simpleName)
                pause(backoff(failures), observed)
            }
        }
    }

    companion object {
        fun retryable(error: Throwable) =
            error is IOException ||
                error is BackendException &&
                    (error.status == 408 || error.status == 429 || error.status in 500..599)

        fun backoff(failures: Int): Long =
            (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
    }
}
