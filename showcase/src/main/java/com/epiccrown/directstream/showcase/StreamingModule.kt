package com.epiccrown.directstream.showcase

import android.content.Context
import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.DirectStreamConfiguration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StreamingModule {
    @Provides
    @Singleton
    fun configuration(@ApplicationContext context: Context): DirectStreamConfiguration =
        sampleConfiguration(context)

    @Provides
    @Singleton
    fun client(
        @ApplicationContext context: Context,
        configuration: DirectStreamConfiguration,
    ): DirectStreamClient = DirectStreamClient.initialize(context, configuration)
}
