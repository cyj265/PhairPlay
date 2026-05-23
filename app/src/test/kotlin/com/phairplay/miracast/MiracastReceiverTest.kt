package com.phairplay.miracast

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Looper
import com.phairplay.service.ProtocolState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiracastReceiverTest — verifies Wi-Fi Direct service advertisement behavior.
 */
class MiracastReceiverTest {

    @Test
    fun `start advertises WFD service and emits advertising state`() {
        val context = mockk<Context>()
        val manager = mockk<WifiP2pManager>(relaxed = true)
        val channel = mockk<WifiP2pManager.Channel>(relaxed = true)
        val actionListener = slot<WifiP2pManager.ActionListener>()
        val states = mutableListOf<ProtocolState>()

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns manager
        every { context.mainLooper } returns Looper.getMainLooper()
        every { manager.initialize(eq(context), any(), any()) } returns channel
        every {
            manager.addLocalService(
                eq(channel),
                any<WifiP2pDnsSdServiceInfo>(),
                capture(actionListener)
            )
        } answers {
            actionListener.captured.onSuccess()
            Unit
        }

        MiracastReceiver(context) { states.add(it) }.start()

        verify(exactly = 1) {
            manager.addLocalService(eq(channel), any<WifiP2pDnsSdServiceInfo>(), any())
        }
        assertEquals(ProtocolState.ADVERTISING, states.last())
    }

    @Test
    fun `start emits error when WifiP2pManager is unavailable`() {
        val context = mockk<Context>()
        val states = mutableListOf<ProtocolState>()

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns null

        MiracastReceiver(context) { states.add(it) }.start()

        assertTrue(states.contains(ProtocolState.ERROR))
    }

    @Test
    fun `WFD RTSP port uses Miracast default`() {
        assertEquals(7236, MiracastReceiver.WFD_RTSP_PORT)
    }
}
