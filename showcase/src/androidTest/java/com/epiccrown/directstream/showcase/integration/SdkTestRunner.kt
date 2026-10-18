package com.epiccrown.directstream.showcase.integration

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.DirectStreamConfiguration
import com.epiccrown.directstream.testing.InMemoryCallProvider

/** Instrumentation never activates the demo's live provider. */
class SdkTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, name: String, context: Context): Application =
        super.newApplication(loader, SdkTestApplication::class.java.name, context)
}

class SdkTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DirectStreamClient.initialize(
            this,
            DirectStreamConfiguration(
                InMemoryCallProvider(),
                returnActivity = SdkTestActivity::class.java,
            ),
        )
    }
}
