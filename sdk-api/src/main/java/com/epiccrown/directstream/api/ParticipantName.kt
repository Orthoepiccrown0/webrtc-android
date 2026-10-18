package com.epiccrown.directstream.api

/** A host-assigned display name, never a verified identity or routing identifier. */
object ParticipantName {
    fun normalize(value: String?): String? {
        require(value == null || value.none { Character.isISOControl(it) }) {
            "Participant name contains control characters."
        }
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                require(it.codePointCount(0, it.length) <= 40) {
                    "Participant names may contain up to 40 characters."
                }
            }
    }
}
