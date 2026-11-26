package com.phairplay.dlna

import com.phairplay.dlna.transport.GenaNotifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GenaTimeoutTest — the timeout a control point is told must be the timeout we actually enforce.
 *
 * Captured on the p230 on 2026-11-26: a SUBSCRIBE asking for `Second-120` was answered
 * `Second-1800`, and a `Second-30` subscription stopped delivering NOTIFYs at 30.2 s. A control
 * point that believes the header renews after 30 minutes and loses every event in between.
 */
class GenaTimeoutTest {

    @Test
    fun `requested timeout inside the accepted range is echoed back`() {
        assertEquals(60, GenaNotifier.effectiveTimeoutSeconds(60))
        assertEquals(120, GenaNotifier.effectiveTimeoutSeconds(120))
        assertEquals(1800, GenaNotifier.effectiveTimeoutSeconds(1800))
    }

    @Test
    fun `values below the floor are raised to 30 seconds`() {
        assertEquals(30, GenaNotifier.effectiveTimeoutSeconds(30))
        assertEquals(30, GenaNotifier.effectiveTimeoutSeconds(1))
        assertEquals(30, GenaNotifier.effectiveTimeoutSeconds(29))
    }

    @Test
    fun `values above the ceiling are capped at 30 minutes`() {
        assertEquals(1800, GenaNotifier.effectiveTimeoutSeconds(1801))
        assertEquals(1800, GenaNotifier.effectiveTimeoutSeconds(86_400))
    }

    @Test
    fun `missing or infinite timeout falls back to the default`() {
        // parseTimeoutSeconds() maps a missing / "infinite" header to 0.
        assertEquals(1800, GenaNotifier.effectiveTimeoutSeconds(0))
        assertEquals(1800, GenaNotifier.effectiveTimeoutSeconds(-1))
    }
}
