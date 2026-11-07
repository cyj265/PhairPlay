package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Test

class MiracastAudioTrimTest {

    /** One AAC PES is ~21.3ms at 48kHz. */
    private val msPerPes = 1024.0 * 1000.0 / 48_000.0

    @Test
    fun `no trim while within the target`() {
        assertEquals(0, MiracastAudioPlayer.trimCount(150L, 150L, msPerPes, 3, 10))
        assertEquals(0, MiracastAudioPlayer.trimCount(100L, 150L, msPerPes, 3, 10))
    }

    @Test
    fun `trims only the excess over the target`() {
        // 170ms lag, 20ms over target → one PES is enough.
        assertEquals(1, MiracastAudioPlayer.trimCount(170L, 150L, msPerPes, 3, 10))
        // 180ms lag needs two PES.
        assertEquals(2, MiracastAudioPlayer.trimCount(180L, 150L, msPerPes, 3, 10))
    }

    @Test
    fun `large lag is capped per event`() {
        assertEquals(3, MiracastAudioPlayer.trimCount(900L, 150L, msPerPes, 3, 40))
    }

    @Test
    fun `never drops more than the queue holds`() {
        assertEquals(2, MiracastAudioPlayer.trimCount(900L, 150L, msPerPes, 3, 2))
        assertEquals(0, MiracastAudioPlayer.trimCount(900L, 150L, msPerPes, 3, 0))
    }

    @Test
    fun `invalid frame duration never trims`() {
        assertEquals(0, MiracastAudioPlayer.trimCount(900L, 150L, 0.0, 3, 10))
    }
}
