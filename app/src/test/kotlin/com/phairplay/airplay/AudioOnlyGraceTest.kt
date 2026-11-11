package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AudioOnlyGraceTest — the AirPlay 2 audio-only teardown fix, driven by synthetic RTSP messages.
 *
 * WHY THIS TEST EXISTS INSTEAD OF A DEVICE RUN: the bug only shows up when an Apple sender closes
 * the RTSP control connection while RTP audio keeps arriving, and reproducing it needs an iPhone or
 * Mac. The decision itself is plain code, so it is exercised here with a crafted plist SETUP (the
 * exact shape an audio-only sender sends) and a simulated control-socket EOF.
 *
 * WHAT IT PINS DOWN:
 * - an audio-only session (one type-96 stream, no screen flag) survives the control EOF and is NOT
 *   torn down while RTP keeps flowing;
 * - it is torn down once the RTP stream has been silent for the whole grace window;
 * - screen mirroring, which declares the screen flag, keeps the old immediate teardown;
 * - a real TEARDOWN ends the session exactly once — the guard must not fire a second teardown.
 */
class AudioOnlyGraceTest {

    private var stoppedCount = 0
    private var rtpFlowing = true

    /** Shrunk so the guard's timing does not slow the suite down. */
    private val tickMs = 20L
    private val silenceMs = 80L

    private fun handler(audioEnabled: Boolean = true) = GraceTestHandler(
        audioEnabled = audioEnabled,
        flowing = { rtpFlowing },
        tickMs = tickMs,
        silenceMs = silenceMs,
        onStopped = { stoppedCount++ }
    )

    /** Audio-only SETUP: a single type-96 stream, no session-level or stream-level screen flag. */
    private val audioOnlySetup = arrayOf(mapOf<String, Any?>("type" to 96L, "usingScreen" to false))

    @Test
    fun `audio-only session survives control EOF while RTP keeps flowing`() {
        val handler = handler()
        handler.running = true
        handler.plistSetup(*audioOnlySetup)

        handler.closeControlConnection()
        Thread.sleep(tickMs * 8)

        assertEquals("audio must keep playing after the control socket closes", 0, stoppedCount)
    }

    @Test
    fun `audio-only session ends once the grace window expires with no RTP`() {
        val handler = handler()
        handler.running = true
        handler.plistSetup(*audioOnlySetup)
        rtpFlowing = false

        handler.closeControlConnection()
        awaitStopped(1, timeoutMs = 2_000)
        Thread.sleep(tickMs * 10)

        assertEquals("the deferred teardown must run exactly once", 1, stoppedCount)
    }

    @Test
    fun `mirror session still tears down immediately on control EOF`() {
        val handler = handler()
        handler.running = true
        // Mirroring declares the screen flag; the type-96 stream carries it on the stream level.
        handler.plistSetup(mapOf<String, Any?>("type" to 96L, "usingScreen" to true))

        handler.closeControlConnection()
        Thread.sleep(tickMs * 3)

        assertEquals("screen mirroring must keep the immediate teardown", 1, stoppedCount)
    }

    @Test
    fun `TEARDOWN during the grace window ends the session only once`() {
        val handler = handler()
        handler.running = true
        handler.plistSetup(*audioOnlySetup)
        rtpFlowing = false

        handler.closeControlConnection()
        handler.teardown()
        Thread.sleep(silenceMs * 4)

        assertEquals("a sender TEARDOWN must not be followed by a second teardown", 1, stoppedCount)
    }

    private fun awaitStopped(expected: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && stoppedCount < expected) {
            Thread.sleep(10)
        }
    }
}

/**
 * GraceTestHandler — drives the private network path from the test source set.
 *
 * [RtspHandler.handleMirrorSetup] and [RtspHandler.handleControlConnectionClosed] are `internal`
 * precisely so this subclass can simulate "sender sent SETUP" and "sender closed the socket"
 * without binding port 7000.
 */
private class GraceTestHandler(
    audioEnabled: Boolean,
    flowing: () -> Boolean,
    tickMs: Long,
    silenceMs: Long,
    onStopped: () -> Unit
) : RtspHandler(
    context = mockk(relaxed = true),
    audioEnabled = audioEnabled,
    videoSurfaceProvider = { null },
    onStreamingStarted = {},
    onStreamingStopped = onStopped,
    isAudioOnlyFlowing = flowing,
    audioOnlyGraceTickMs = tickMs,
    audioOnlyGraceSilenceMs = silenceMs
) {
    /** Sends a binary-plist SETUP body naming the given streams, e.g. `{streams:[{type:96}]}`. */
    fun plistSetup(vararg streams: Map<String, Any?>) {
        handleMirrorSetup(
            RtspRequest(
                method = "SETUP",
                uri = "",
                headers = emptyMap(),
                body = "",
                bodyBytes = PlistCodec.encode(mapOf("streams" to streams.toList()))
            )
        )
    }

    /** Simulates the sender closing the RTSP control connection. */
    fun closeControlConnection() = handleControlConnectionClosed()

    /** Sends a session-level TEARDOWN (no streams named in the body). */
    fun teardown() {
        handleTeardownInternal(RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = ""))
    }
}
