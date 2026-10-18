package com.epiccrown.directstream.testing

import com.epiccrown.directstream.DirectStreamClient
import com.epiccrown.directstream.ForegroundCallBridge

/**
 * Debug-only inspection for the showcase's SDK contract tests. The published AAR deliberately
 * excludes SDK debug sources. Keep this version-specific reflection out of production integration.
 */
object SdkTestAccess {
    fun bridge(client: DirectStreamClient): ForegroundCallBridge =
        DirectStreamClient::class.java.getDeclaredField("callBridge").run {
            isAccessible = true
            get(client) as ForegroundCallBridge
        }
}
