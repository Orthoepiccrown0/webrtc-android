package com.epiccrown.directstream.showcase

import com.epiccrown.directstream.ForegroundCallBridge
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access for service integration tests; no substitute application or cloud identity. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CallTestEntryPoint {
    fun bridge(): ForegroundCallBridge
}
