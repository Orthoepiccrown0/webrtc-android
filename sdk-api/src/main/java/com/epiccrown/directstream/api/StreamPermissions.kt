package com.epiccrown.directstream.api

/** Permission names are strings so this policy remains usable on Android versions before API 37. */
object StreamPermissions {
    const val CAMERA = "android.permission.CAMERA"
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun required(sdk: Int, role: Role, mode: CallMode = CallMode.Video): List<String> = buildList {
        if (mode == CallMode.Video) add(CAMERA)
        if (sdk >= 37) add(LOCAL_NETWORK)
    }
}
