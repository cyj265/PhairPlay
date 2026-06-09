package com.phairplay.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Verifies the AAC-ELD AudioSpecificConfig builder, which replaced a hardcoded 44.1 kHz/stereo
 * config. The 44.1 kHz/stereo case must reproduce the previously-hardcoded canonical bytes so the
 * working macOS mirroring path is unchanged.
 */
class AudioStreamServerTest {

    @Test
    fun `AAC-ELD ASC for 44_100 Hz stereo equals the canonical F8 E8 50 00`() {
        assertArrayEquals(
            byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00.toByte()),
            AudioStreamServer.buildAacEldAsc(44100, 2)
        )
    }

    @Test
    fun `AAC-ELD ASC encodes 48 kHz mono (freq index 3, channel config 1)`() {
        // AOT-escape(11111 000111) freqIdx=0011 chanCfg=0001 frameLenFlag=1 tail=0... → F8 E6 30 00
        assertArrayEquals(
            byteArrayOf(0xF8.toByte(), 0xE6.toByte(), 0x30.toByte(), 0x00.toByte()),
            AudioStreamServer.buildAacEldAsc(48000, 1)
        )
    }
}
