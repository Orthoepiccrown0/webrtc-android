package com.epiccrown.directstream.showcase

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.epiccrown.directstream.DirectStreamClient
import org.junit.Assert.assertSame
import org.junit.Test

class HostInjectionTest {
    @Test
    fun applicationAndActivityUseTheConfiguredProcessClient() {
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as StreamingApplication
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertSame(application.client, activity.client)
                assertSame(DirectStreamClient.current(), activity.client)
                assertSame(application.client.configuration, activity.client.configuration)
            }
        }
    }
}
