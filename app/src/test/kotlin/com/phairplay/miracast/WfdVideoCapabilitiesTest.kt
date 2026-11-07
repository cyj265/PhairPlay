package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WfdVideoCapabilitiesTest {

    private val mode1080p30 = WfdCustomVideoMode(1920, 1080, 30, 0x10)

    @Test
    fun `standard format is always advertised`() {
        val lines = wfdVideoCapabilityLines(emptySet(), null)
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("wfd_video_formats: 00 00 02 10"))
    }

    @Test
    fun `custom mode is only advertised when the source asks`() {
        val without = wfdVideoCapabilityLines(setOf("wfd_video_formats"), mode1080p30)
        assertEquals(1, without.size)

        val with = wfdVideoCapabilityLines(
            setOf("wfdx_video_formats", "microsoft_custom_video_formats"),
            mode1080p30
        )
        assertEquals(3, with.size)
        assertTrue(with[1].startsWith("wfdx_video_formats: "))
        assertTrue(with[2] == "microsoft_custom_video_formats: 0780 0438 001E")
    }

    @Test
    fun `microsoft format falls back to none when only the extended mode would fit`() {
        val highMode = WfdCustomVideoMode(1920, 1080, 60, 0x20)
        val lines = wfdVideoCapabilityLines(setOf("microsoft_custom_video_formats"), highMode)
        assertTrue(lines.last() == "microsoft_custom_video_formats: none")
    }

    @Test
    fun `microsoft format is declared for a level 4_2 mode`() {
        val lines = wfdVideoCapabilityLines(setOf("microsoft_custom_video_formats"), mode1080p30)
        assertTrue(lines.last() == "microsoft_custom_video_formats: 0780 0438 001E")
    }

    @Test
    fun `standard format advertises 1080p30 and 720p30 only`() {
        val line = wfdVideoCapabilityLines(emptySet(), null).single()
        assertTrue(line.contains("000000A0"))
        assertFalse(line.contains("00000001"))
        assertFalse(line.contains("000001C0"))
        assertFalse(line.contains("00008120"))
    }

    @Test
    fun `custom mode matches a 1080p panel`() {
        val mode = chooseWfdCustomVideoMode(1920, 1080, false, true) { _, _, _, _ -> true }
        assertEquals(WfdCustomVideoMode(1920, 1080, 30, 0x10), mode)
    }

    @Test
    fun `custom mode format uses four hex digits per field`() {
        assertEquals("0780 0438 003C", WfdCustomVideoMode(1920, 1080, 60, 0x10).customFormat())
    }

    @Test
    fun `extended format carries the negotiated level bit`() {
        assertTrue(mode1080p30.extendedFormats().startsWith("0040 00 0002 0010 "))
    }

    @Test
    fun `selected custom mode must match the advertised one exactly`() {
        assertTrue(matchesWfdCustomMode("0780 0438 001E", mode1080p30))
        assertTrue(matchesWfdCustomMode("0780 0438 001E trailing", mode1080p30))
        assertFalse(matchesWfdCustomMode("0780 0438 003C", mode1080p30))
        assertFalse(matchesWfdCustomMode("0780", mode1080p30))
        assertFalse(matchesWfdCustomMode("0780 0438 zzzz", mode1080p30))
    }
}
