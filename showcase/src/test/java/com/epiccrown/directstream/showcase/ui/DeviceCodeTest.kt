package com.epiccrown.directstream.showcase.ui

import com.epiccrown.directstream.api.DeviceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodeTest {
    @Test
    fun normalizesHumanInput() {
        assertEquals("ABC234", DeviceCode.normalize(" aBc - 234 "))
    }

    @Test
    fun formatsCode() {
        assertEquals("ABC-234", DeviceCode.format("abc234"))
    }

    @Test
    fun linkUsesSameNormalization() {
        assertEquals("ABC234", DeviceLinks.fromInput("webrtcstream://pair/abc-234"))
    }

    @Test
    fun qrAndAppLinksDisplayOnlyTheFormattedCode() {
        for (value in
            listOf(
                "webrtcstream://pair/abc234",
                "webrtcstream://pair/ABC-234",
                "WEBRTCSTREAM://PAIR/ABC234",
                " abc-234 ",
            )) {
            assertEquals("ABC-234", DeviceCode.format(DeviceLinks.fromInput(value)))
        }
    }

    @Test
    fun rejectsUnrelatedOrMalformedQrLinks() {
        for (value in
            listOf(
                "https://example.com/ABC234",
                "webrtcstream://other/ABC234",
                "junkwebrtcstream://pair/ABC234",
                "webrtcstream://pair/",
                "webrtcstream://pair/ABC234/extra",
                "webrtcstream://pair/ABC234?unexpected=true",
                "webrtcstream://user@pair/ABC234",
            )) {
            assertTrue(value, runCatching { DeviceLinks.fromInput(value) }.isFailure)
        }
    }

    @Test
    fun rejectsAmbiguousAndInvalidCharacters() {
        listOf("ABC23O", "ABC23I", "ABC23L", "ABC230", "ABC231", "ABC23!", "ABC23", "ABC2345")
            .forEach {
                assertTrue(runCatching { DeviceCode.normalize(it) }.isFailure)
            }
    }
}
