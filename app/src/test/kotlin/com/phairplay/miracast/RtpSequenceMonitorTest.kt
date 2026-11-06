package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Test

class RtpSequenceMonitorTest {

    @Test
    fun `sequential packets are in order`() {
        val monitor = RtpSequenceMonitor()
        assertEquals(RtpSequenceEvent.IN_ORDER, monitor.onSequence(100))
        assertEquals(RtpSequenceEvent.IN_ORDER, monitor.onSequence(101))
        assertEquals(RtpSequenceEvent.IN_ORDER, monitor.onSequence(102))
        assertEquals(0L, monitor.lost)
        assertEquals(0L, monitor.lateCount)
    }

    @Test
    fun `forward gap reports the skipped packet count`() {
        val monitor = RtpSequenceMonitor()
        monitor.onSequence(100)
        assertEquals(RtpSequenceEvent.LOSS, monitor.onSequence(103))
        assertEquals(2, monitor.takeLost())
        assertEquals(2L, monitor.lost)
        assertEquals(0, monitor.takeLost())
    }

    @Test
    fun `late packet is not counted as fifty thousand lost`() {
        val monitor = RtpSequenceMonitor()
        monitor.onSequence(100)
        monitor.onSequence(102)              // 101 lost
        assertEquals(RtpSequenceEvent.LATE, monitor.onSequence(101))
        assertEquals(1L, monitor.lost)
        assertEquals(1L, monitor.lateCount)
    }

    @Test
    fun `duplicate packet is late and does not move the window back`() {
        val monitor = RtpSequenceMonitor()
        monitor.onSequence(100)
        monitor.onSequence(101)
        assertEquals(RtpSequenceEvent.LATE, monitor.onSequence(101))
        assertEquals(RtpSequenceEvent.IN_ORDER, monitor.onSequence(102))
        assertEquals(0L, monitor.lost)
    }

    @Test
    fun `far backward jump restarts the stream instead of adding loss`() {
        val monitor = RtpSequenceMonitor()
        monitor.onSequence(40000)
        assertEquals(RtpSequenceEvent.RESTART, monitor.onSequence(300))
        assertEquals(0L, monitor.lost)
        assertEquals(RtpSequenceEvent.IN_ORDER, monitor.onSequence(301))
    }

    @Test
    fun `sequence wrap is handled as in order`() {
        val monitor = RtpSequenceMonitor()
        monitor.onSequence(65534)
        monitor.onSequence(65535)
        assertEquals(RtpSequenceEvent.IN_ORDER, monitor.onSequence(0))
        assertEquals(RtpSequenceEvent.IN_ORDER, monitor.onSequence(1))
        assertEquals(0L, monitor.lost)
    }
}
