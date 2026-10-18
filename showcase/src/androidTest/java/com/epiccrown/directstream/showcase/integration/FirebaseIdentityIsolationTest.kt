package com.epiccrown.directstream.showcase.integration

import androidx.test.platform.app.InstrumentationRegistry
import com.epiccrown.directstream.firebase.FirebaseWorkerConfiguration
import com.epiccrown.directstream.firebase.FirebaseWorkerProvider
import com.epiccrown.directstream.firebase.internal.FirebaseClients
import com.epiccrown.directstream.firebase.internal.FirebaseConfiguration
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Test

/** Requires only the local Auth emulator on host port 9099; never signs in to production Auth. */
class FirebaseIdentityIsolationTest {
    @Test
    fun sdkIdentityAndProofPreserveHostAuthUser() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = FirebaseAuth.getInstance()
        host.useEmulator("10.0.2.2", 9099)
        val hostUser = host.signInAnonymously().await().user!!
        val config =
            FirebaseWorkerConfiguration(
                "demo-sdk-isolation",
                "1:123456:android:abcdef",
                "fake-emulator-api-key",
                "https://demo-sdk-isolation.firebaseio.com",
                "123456",
                "https://example.workers.dev",
            )
        val clients =
            FirebaseClients(
                context,
                FirebaseConfiguration(
                    config.projectId,
                    config.applicationId,
                    config.apiKey,
                    config.databaseUrl,
                    config.senderId,
                ),
            )
        clients.auth.useEmulator("10.0.2.2", 9099)
        val provider = FirebaseWorkerProvider(context, config)
        try {
            val sdkUid = provider.identity.signIn()
            assertNotEquals(hostUser.uid, sdkUid)
            assertEquals(hostUser.uid, host.currentUser!!.uid)
            assertEquals(sdkUid, provider.identity.signIn())
            val proof = provider.identity.ownershipProof()
            assertTrue(proof.expiresAtMillis > System.currentTimeMillis())
            assertEquals("DeviceOwnershipProof(redacted)", proof.toString())
            provider.dispose()
            assertEquals(hostUser.uid, host.currentUser!!.uid)
            assertTrue(
                runCatching {
                    requireNotNull(provider.push).registerToken("test-token-project-mismatch")
                }
                    .isFailure,
            )
            assertEquals(hostUser.uid, host.currentUser!!.uid)
        } finally {
            // These are emulator-only users created by this test, not a customer's account.
            clients.auth.signOut()
            host.signOut()
        }
    }
}
