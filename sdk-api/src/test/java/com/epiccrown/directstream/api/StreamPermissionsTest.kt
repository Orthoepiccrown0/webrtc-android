package com.epiccrown.directstream.api

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPermissionsTest {
    @Test
    fun android17ReceiverNeedsCameraAndLocalNetwork() {
        assertEquals(
            listOf(StreamPermissions.CAMERA, StreamPermissions.LOCAL_NETWORK),
            StreamPermissions.required(37, Role.RECEIVER),
        )
    }

    @Test
    fun android17SenderNeedsCameraAndLocalNetwork() {
        assertEquals(
            listOf(StreamPermissions.CAMERA, StreamPermissions.LOCAL_NETWORK),
            StreamPermissions.required(37, Role.SENDER),
        )
    }

    @Test
    fun earlierAndroidVersionsDoNotRequestUnsupportedNetworkPermission() {
        for (sdk in 30..36) {
            assertEquals(
                listOf(StreamPermissions.CAMERA),
                StreamPermissions.required(sdk, Role.RECEIVER),
            )
            assertEquals(
                listOf(StreamPermissions.CAMERA),
                StreamPermissions.required(sdk, Role.SENDER),
            )
        }
    }
}
