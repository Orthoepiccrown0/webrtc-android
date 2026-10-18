package com.epiccrown.directstream.internal.media

/** Used only on the RTC dispatcher. Times come from a monotonic clock. */
internal class SessionLogGate {
    private val values = mutableMapOf<String, Any>()
    private val times = mutableMapOf<String, Long>()

    fun changed(key: String, value: Any): Boolean {
        if (values[key] == value) return false
        values[key] = value
        return true
    }

    fun once(key: String): Boolean = changed(key, true)

    fun due(key: String, nowMs: Long, intervalMs: Long): Boolean {
        val previous = times[key]
        if (previous != null && nowMs - previous < intervalMs) return false
        times[key] = nowMs
        return true
    }

    fun reset() {
        values.clear()
        times.clear()
    }
}
