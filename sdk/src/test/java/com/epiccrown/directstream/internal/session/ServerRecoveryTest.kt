package com.epiccrown.directstream.internal.session

import com.epiccrown.directstream.api.BackendException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerRecoveryTest {
    @Test
    fun onlyTemporaryTransportFailuresAreRetried() {
        assertTrue(ServerRecovery.retryable(IOException()))
        for (status in listOf(408, 429, 500, 502, 503, 504)) assertTrue(
            ServerRecovery.retryable(BackendException(status, "test")),
        )
        for (status in listOf(400, 401, 403, 404, 409, 410)) assertFalse(
            ServerRecovery.retryable(BackendException(status, "test")),
        )
        assertFalse(ServerRecovery.retryable(CancellationException()))
        assertEquals(
            listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (1..7).map(ServerRecovery::backoff),
        )
    }

    @Test
    fun resumeWakesEveryWaiterAndWakeDuringAnOperationIsNotLost() = runBlocking {
        val recovery = ServerRecovery()
        val oldVersion = recovery.version
        val first =
            async(start = CoroutineStart.UNDISPATCHED) { recovery.pause(30_000, oldVersion) }
        val second =
            async(start = CoroutineStart.UNDISPATCHED) { recovery.pause(30_000, oldVersion) }
        recovery.wake()
        withTimeout(500) {
            first.await()
            second.await()
            recovery.pause(30_000, oldVersion)
        }
    }

    @Test
    fun transientOperationsRecoverWithoutRepeatingTerminalFailures() = runBlocking {
        val recovery = ServerRecovery()
        var calls = 0
        assertEquals(
            "ok",
            recovery.retry("test") {
                calls++
                if (calls == 1) {
                    recovery.wake()
                    throw IOException()
                }
                "ok"
            },
        )
        assertEquals(2, calls)
        var rejected = 0
        try {
            recovery.retry("test") {
                rejected++
                throw BackendException(403, "test")
            }
            fail()
        } catch (_: BackendException) {
            assertEquals(1, rejected)
        }
    }

    @Test
    fun disposalCancelsRetryWithoutAnotherOperation() = runBlocking {
        val recovery = ServerRecovery()
        var calls = 0
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                recovery.retry("test") {
                    calls++
                    throw IOException()
                }
            }
        job.cancelAndJoin()
        recovery.wake()
        yield()
        assertEquals(1, calls)
    }
}
