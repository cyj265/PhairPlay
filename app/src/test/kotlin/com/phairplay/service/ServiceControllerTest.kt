package com.phairplay.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ServiceControllerTest — Unit tests for [ServiceController].
 *
 * WHY: [ServiceController] is the single point through which the UI controls the
 * background service. Bugs here mean the user can't start/stop/restart the receiver.
 * We verify that each method sends the correct Intent action to the service.
 *
 * WHAT WE TEST:
 * - [ServiceController.start] dispatches ACTION_START to PhairPlayService
 * - [ServiceController.stop] dispatches ACTION_STOP to PhairPlayService
 * - [ServiceController.restart] dispatches ACTION_RESTART to PhairPlayService
 * - Intent target component is PhairPlayService
 *
 * HOW: Context and ContextCompat are mocked with MockK. We capture the Intent
 * passed to startForegroundService / startService and assert its action and target.
 */
class ServiceControllerTest {

    private lateinit var context: Context
    private val intentSlot = slot<Intent>()

    @Before
    fun setup() {
        context = mockk(relaxed = true)

        // Mock ContextCompat.startForegroundService so it captures the Intent
        mockkStatic(ContextCompat::class)
        every { ContextCompat.startForegroundService(any(), capture(intentSlot)) } returns mockk()
    }

    @Test
    fun `start() sends ACTION_START intent via startForegroundService`() {
        ServiceController.start(context)

        verify { ContextCompat.startForegroundService(context, any()) }
        assertEquals(PhairPlayService.ACTION_START, intentSlot.captured.action)
    }

    @Test
    fun `stop() sends ACTION_STOP intent via startService`() {
        val stopSlot = slot<Intent>()
        every { context.startService(capture(stopSlot)) } returns mockk()

        ServiceController.stop(context)

        verify { context.startService(any()) }
        assertEquals(PhairPlayService.ACTION_STOP, stopSlot.captured.action)
    }

    @Test
    fun `restart() sends ACTION_RESTART intent via startForegroundService`() {
        ServiceController.restart(context)

        verify { ContextCompat.startForegroundService(context, any()) }
        assertEquals(PhairPlayService.ACTION_RESTART, intentSlot.captured.action)
    }

    @Test
    fun `start() intent targets PhairPlayService class`() {
        ServiceController.start(context)

        // The intent component class name should point to PhairPlayService
        assertEquals(
            PhairPlayService::class.java.name,
            intentSlot.captured.component?.className
        )
    }
}
