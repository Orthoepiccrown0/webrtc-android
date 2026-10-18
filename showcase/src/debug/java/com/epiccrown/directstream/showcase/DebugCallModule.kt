package com.epiccrown.directstream.showcase

import com.epiccrown.directstream.DirectStreamClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Test-only bridge access; production sample integration uses the SDK public facade. */
@Module
@InstallIn(SingletonComponent::class)
object DebugCallModule {
    @Provides
    @Singleton
    fun callBridge(client: DirectStreamClient) =
        com.epiccrown.directstream.testing.SdkTestAccess.bridge(client)
}
