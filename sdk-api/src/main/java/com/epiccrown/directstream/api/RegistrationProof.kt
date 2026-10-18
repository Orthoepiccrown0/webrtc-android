package com.epiccrown.directstream.api

/**
 * Short-lived credential for a host backend to verify device ownership. Never display or log it.
 */
class DeviceOwnershipProof(val value: String, val provider: String, val expiresAtMillis: Long) {
    override fun toString(): String = "DeviceOwnershipProof(redacted)"
}

sealed interface ValueResult<out T> {
    data class Success<T>(val value: T) : ValueResult<T>

    data class Failure(val failure: CallFailure) : ValueResult<Nothing>
}

enum class RelayReadiness {
    Unknown,
    DirectOnly,
    Configured,
}
