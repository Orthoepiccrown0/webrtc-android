package com.epiccrown.directstream.api

import org.junit.Assert.*
import org.junit.Test

class ParticipantNameTest {
    @Test
    fun normalizesOptionalNamesAndRejectsInvalidInput() {
        assertNull(ParticipantName.normalize(null))
        assertNull(ParticipantName.normalize("   "))
        assertEquals("River Otter", ParticipantName.normalize("  River Otter  "))
        assertEquals("🦊".repeat(40), ParticipantName.normalize("🦊".repeat(40)))
        for (invalid in listOf("x".repeat(41), "name\n", "\tname", "name\u007f")) assertTrue(
            runCatching { ParticipantName.normalize(invalid) }.isFailure,
        )
    }
}
